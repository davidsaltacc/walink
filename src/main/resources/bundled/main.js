import makeWASocket, { Browsers, DisconnectReason, useMultiFileAuthState, makeCacheableSignalKeyStore } from "baileys";
import { existsSync, mkdirSync, openAsBlob, readFileSync, unlinkSync, writeFileSync } from "node:fs";
import P from "pino";
import NodeCache from "node-cache";
import QRCode from "qrcode";
import { sendIPCMessage, startReadingIPCMessages } from "./ipc.js";
import { createInterface } from "node:readline";

const { state, saveCreds } = await useMultiFileAuthState("auth_state");
const groupCache = new NodeCache();

const DEBUG = false;
const DEBUG_MANUAL_MESSAGES = false;
const DEBUG_GROUPCHAT_NAME = "test";

export const logger = P({
    transport: {
        target: "pino-pretty",
        options: {
            colorize: false,
            destination: "./app.log"
        }
    },
    level: "info"
});

let targetedGroupchatName;
let targetedGroupChatJid;

let allChats = [];
let allContacts = {};

let sock;

const makeSock = async () => {

    let socket = makeWASocket({
        auth: {
            creds: state.creds,
            keys: makeCacheableSignalKeyStore(state.keys, logger),
        },
        version: [
            ...JSON.parse(await (await fetch("https://raw.githubusercontent.com/wppconnect-team/wa-version/refs/heads/main/versions.json")).text())["currentVersion"].replace("-alpha", "").split("."), "alpha"
        ],
        logger,
        browser: Browsers.windows("Google Chrome"),
        markOnlineOnConnect: false,
        syncFullHistory: false,
        shouldSyncHistoryMessage: () => true, // this is required to not have a full sync be made, but still get messaging-history.set events
        cachedGroupMetadata: async (jid) => groupCache.get(jid)
    });
    
    socket.ev.on("connection.update", async update => {
        
        const { connection, lastDisconnect, qr } = update;

        if (connection === "close" && lastDisconnect?.error?.output?.statusCode === DisconnectReason.restartRequired) {
            sock = await makeSock();
        }
        
        if (qr) {
            await QRCode.toFile("qrcode.png", qr, { errorCorrectionLevel: "M" });
            const body = new FormData();
            body.set("files[]", await openAsBlob("qrcode.png"), "qrcode.png");
            let response = await fetch("https://uguu.se/upload?output=text", {
                method: "POST",
                body
            });
            let content = await response.text();
            logger.info("QR code available at " + content + " - please scan with the WhatsApp mobile app to login.");
            sendIPCMessage(process.stdout, "qrcd", content);
            unlinkSync("qrcode.png");
        }

        if (connection === "open") {
            if (existsSync("chats_state/contacts.json")) {
                allContacts = JSON.parse(readFileSync("chats_state/contacts.json")) ?? allContacts;
            }
            if (existsSync("chats_state/chats.json")) {
                allChats = JSON.parse(readFileSync("chats_state/chats.json")) ?? allChats;
                allChats.forEach(chat => {
                    if (chat.name === targetedGroupchatName && chat.id) {
                        targetedGroupChatJid = chat.id;
                    }
                });
                if (!targetedGroupChatJid) {
                    throw new Error("Targeted Group could not be found");
                }
                sendIPCMessage(process.stdout, "wrdy", "");
                logger.info("Ready");
            } 

        }
    
    });
    
    socket.ev.on("creds.update", saveCreds);
    
    socket.ev.on("messages.upsert", async ({type, messages}) => {
        try {
            if (type === "notify") {
                for (const msg of messages) {
                    if (msg.message?.conversation || msg.message?.extendedTextMessage?.text || msg.message?.imageMessage || msg.message?.documentMessage || msg.message?.audioMessage) {
                        
                        let text = msg.message?.conversation || msg.message?.extendedTextMessage?.text;

                        if (text) {
                            text = text.replace(/(@[0-9]+)/gm, (match, id) => "§7@<unknown>§r");
                        }
        
                        if (msg.key?.remoteJid !== targetedGroupChatJid) { return; }

                        let author = msg.pushName ?? "<unknown>";

                        let finalText = "§2[WhatsApp]§r §7" + author + "§r: ";
                        
                        if (msg.message?.commentMessage) { finalText += "§7(in reply to another message)§r "; } 
                        if (msg.message?.imageMessage) { finalText += "<image> "; } 
                        else if (msg.message?.documentMessage) { finalText += "<file> "; } 
                        else if (msg.message?.audioMessage) { finalText += "<voice message> "; }
                        finalText += text ?? "";

                        await messageReceivedWA(finalText);
                        
                    }
                }
            }
        } catch (e) {
            logger.error(e);
        }
    });

    socket.ev.on("messaging-history.set", async ({ chats, contacts, messages, isLatest, progress, syncType }) => {

        allChats = allChats.concat(chats);
        allChats = allChats.filter((chat, index) => {
            return index === allChats.findIndex(c => c.id === chat.id);
        });

        contacts.forEach(contact => {
            allContacts[contact.id] = contact;
        });

        logger.info("sync progress " + (progress ?? 0) + "%");
        sendIPCMessage(process.stdout, "sync", progress ?? 0);

        if (progress === 100) {
            allChats.forEach(chat => {
                if (chat.name === targetedGroupchatName && chat.id) {
                    targetedGroupChatJid = chat.id;
                }
            });
            if (!existsSync("chats_state")) {
                mkdirSync("chats_state");
            }
            if (!existsSync("chats_state/chats.json")) {
                sendIPCMessage(process.stdout, "wrdy", "");
                logger.info("Ready");
            }
            writeFileSync("chats_state/chats.json", JSON.stringify(allChats));
            writeFileSync("chats_state/contacts.json", JSON.stringify(allContacts));
        }

    });

    return socket;

};

const messageReceivedWA = async text => {
    sendIPCMessage(process.stdout, "nmsg", text);
};

const messageReceivedMC = async text => {
    await sock.sendMessage(targetedGroupChatJid, { text });
};

if (DEBUG && !DEBUG_MANUAL_MESSAGES) {
    targetedGroupchatName = DEBUG_GROUPCHAT_NAME;
    sock = await makeSock();
} else if (DEBUG && DEBUG_MANUAL_MESSAGES) {
    const rl = createInterface({
        input: process.stdin,
        output: process.stdout
    });
    const inp = () => {
        rl.question("type+content:", async msg => {
            const type = msg.substring(0, 4);
            const data = msg.substring(4);
            logger.info("received " + type + " message");
            if (type === "gcnm") {
                targetedGroupchatName = data;
            }
            if (type === "init") {
                sock = await makeSock();
            }
            if (type === "nmsg") {
                await messageReceivedMC(data);
            }
            inp();
        });
    };
    inp();
} else {
    try {
        startReadingIPCMessages(process.stdin, async msg => {
            const {type, data} = msg;
            logger.info("received " + type + " message");
            if (type === "gcnm") {
                targetedGroupchatName = data;
            }
            if (type === "init") {
                sock = await makeSock();
            }
            if (type === "nmsg") {
                await messageReceivedMC(data);
            }
        });
    } catch (e) {
        logger.error(e);
        throw e;
    }
}