import { existsSync, mkdirSync, openAsBlob, readdirSync, readFileSync, rmdirSync, unlinkSync, writeFileSync, writeSync } from "node:fs";
import { createInterface } from "node:readline";
import makeWASocket, { Browsers, DisconnectReason, useMultiFileAuthState, makeCacheableSignalKeyStore } from "baileys";
import dateFormat from "dateformat";
import NodeCache from "node-cache";
import QRCode from "qrcode";
import P from "pino";

const DEBUG = false;

const { state, saveCreds } = await useMultiFileAuthState("auth_state");
const groupCache = new NodeCache();

if (!existsSync("logs")) {
    mkdirSync("logs");
}

const logFile = dateFormat("yyyy-mm-dd-HH-MM-ss") + ".log";

const logger = P({
    transport: {
        target: "pino-pretty",
        options: {
            colorize: false,
            destination: "logs/" + logFile
        }
    },
    level: "info"
});

let targetedGroupchatName;
let targetedGroupChatJid;

let allChats = [];
let allContacts = {};

if (existsSync("chats_state/contacts.json")) {
    allContacts = JSON.parse(readFileSync("chats_state/contacts.json")) ?? allContacts;
}
if (existsSync("chats_state/chats.json")) {
    allChats = JSON.parse(readFileSync("chats_state/chats.json")) ?? allChats;
}

let globalSock;
let anySockExists = false;

async function makeSock(isForAuth) {

    const conf = {
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
        shouldSyncHistoryMessage: () => !isForAuth,
        cachedGroupMetadata: async (jid) => groupCache.get(jid)
    };

    const sock = makeWASocket(conf);
    
    sock.ev.on("creds.update", saveCreds);

    return sock;

}

async function authenticate(onQrCodeUrl, onFail) {

    if (anySockExists) {
        onFail("Another instance of a WhatsApp socket is already running. Please stop or wait for any other processes to stop.");
        return;
    }

    anySockExists = true;
    let sock = await makeSock(true);

    await new Promise((res, rej) => {
    
        const onUpdate = async update => {

            try {
            
                const { connection, lastDisconnect, qr } = update;

                if (connection === "close" && lastDisconnect?.error?.output?.statusCode === DisconnectReason.restartRequired) {
                    sock = await makeSock(true);
                    sock.ev.on("creds.update", saveCreds);
                    sock.ev.on("connection.update", onUpdate);
                } else if (connection === "close" && !!lastDisconnect?.error) {
                    onFail(lastDisconnect?.error?.output);
                    rej(lastDisconnect?.error?.output);
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
                    unlinkSync("qrcode.png");
                    onQrCodeUrl(content);
                }
                
                if (connection === "open") {
                    res();
                }

            } catch (e) {
                rej(e);
            }

        };

        sock.ev.on("connection.update", onUpdate);

    });

    await sock.end();
    anySockExists = false;

}

async function startFull(onFail, onSyncProgress) {

    if (targetedGroupchatName == null) {
        onFail("Cannot start without having a valid group chat name.");
        return;
    }

    if (anySockExists) {
        onFail("Another instance of a WhatsApp socket is already running. Please stop or wait for any other processes to stop.");
        return;
    }

    anySockExists = true;

    const makeFullSock = async (done, rej) => {

        let sock = globalSock = await makeSock(false);

        sock.ev.on("connection.update", async update => {

            try {
            
                const { connection, lastDisconnect, qr } = update;

                if (qr) {
                    onFail("Authentication not set up. Please link WALink before attempting a full start.");
                    await sock.end();
                    done();
                }

                if (connection === "close" && lastDisconnect?.error?.output?.statusCode === DisconnectReason.restartRequired) {
                    makeFullSock();
                } else if (connection === "close" && !!lastDisconnect?.error) {
                    onFail(lastDisconnect?.error?.output);
                    rej(lastDisconnect?.error?.output);
                }

                if (connection === "open") {
                    if (existsSync("chats_state/chats.json")) {
                        allChats.forEach(chat => {
                            if (chat.name === targetedGroupchatName && chat.id) {
                                targetedGroupChatJid = chat.id;
                            }
                        });
                        if (!targetedGroupChatJid) {
                            onFail("Targeted group could not be found");
                        }
                        done();
                    }
                }

            } catch (e) {
                rej(e);
            }

        });

        sock.ev.on("messaging-history.set", async ({ chats, contacts, messages, isLatest, progress, syncType }) => {

            allChats = allChats.concat(chats);
            allChats = allChats.filter((chat, index) => {
                return index === allChats.findIndex(c => c.id === chat.id);
            });
    
            contacts.forEach(contact => {
                allContacts[contact.id] = contact;
            });
            
            onSyncProgress(progress ?? 0);
    
            if (progress === 100) {
                allChats.forEach(chat => {
                    if (chat.name === targetedGroupchatName && chat.id) {
                        targetedGroupChatJid = chat.id;
                    }
                });
                if (!targetedGroupChatJid) {
                    onFail("Targeted group could not be found");
                }
                if (!existsSync("chats_state")) {
                    mkdirSync("chats_state");
                }
                writeFileSync("chats_state/chats.json", JSON.stringify(allChats));
                writeFileSync("chats_state/contacts.json", JSON.stringify(allContacts));
                done();
            }
    
        });

        function stringifyWAMessageForMC(msg, isReplyOriginal) {

            if (isReplyOriginal) {
                return "\"" + (msg.message?.conversation ?? "(unknown)") + "\"";
            }

            let text = msg.message?.conversation || msg.message?.extendedTextMessage?.text;
    
            if (text) {
                text = text.replace(/(@[0-9]+)/gm, (match, id) => "§7@<unknown>§r"); 
            }

            let author = msg.pushName ?? "<unknown>";

            let finalText = "§7" + author + "§r: ";
            
            if (msg.message?.extendedTextMessage?.contextInfo?.quotedMessage) { finalText += "§7(in reply to " + stringifyWAMessageForMC(msg.message?.extendedTextMessage?.contextInfo?.quotedMessage, true) + ")§r "; } 
            if (msg.message?.imageMessage) { finalText += "<image> "; } 
            else if (msg.message?.documentMessage) { finalText += "<file> "; } 
            else if (msg.message?.audioMessage) { finalText += "<voice message> "; }
            else if (msg.message?.stickerMessage) { finalText += "<sticker> "; }
            else if (msg.message?.viewOnceMessage) { finalText += "<view-once message> "; }
            else if (msg.message?.viewOnceMessageV2) { finalText += "<view-once message> "; }
            finalText += text ?? "";
            
            return finalText;

        }

        sock.ev.on("messages.upsert", async ({type, messages}) => {
            try {
                if (type === "notify") {
                    for (const msg of messages) {
                        if (msg.message?.conversation || msg.message?.extendedTextMessage?.text || msg.message?.imageMessage || msg.message?.documentMessage || msg.message?.audioMessage || msg.message?.stickerMessage || msg.message?.viewOnceMessage || msg.message?.viewOnceMessageV2) {
                           
                            if (msg.key?.remoteJid !== targetedGroupChatJid) { return; }
                    
                            await messageReceivedWA(stringifyWAMessageForMC(msg, false), msg);
                            
                        }
                    }
                }
            } catch (e) {
                logger.error(e);
            }
        });

    }

    await new Promise((res, rej) => {
        try {
            makeFullSock(res, rej);
        } catch (e) {
            onFail(e);
        }
    });

    anySockExists = false;

}

function startReadingIPCMessages(stdin, handler) {
    let buffer = Buffer.alloc(0);
    stdin.on("readable", async () => {
        let chunk;
        while ((chunk = stdin.read()) !== null) {
            buffer = Buffer.concat([buffer, chunk]);

            while (buffer.length >= 4) {
                const msgLength = buffer.readUInt32BE(0);

                if (buffer.length < 4 + msgLength) {
                    break;
                }

                const dataBuf = buffer.subarray(4, 4 + msgLength);
                const dataUtf8 = dataBuf.toString("utf8");

                const msg = {
                    type: dataUtf8.substring(0, 4),
                    data: dataUtf8.substring(4)
                };

                await handler(msg);

                buffer = buffer.subarray(4 + msgLength);
            }
        }
    });
    stdin.resume();
}

function sendIPCMessage(type, content) {
    if (type.length !== 4) {
        logger.error("IPC message type must be 4 characters");
        return;
    }
    const data = type + content;
    if (DEBUG) {
        console.log(data);
        return;
    }
    const buf = Buffer.alloc(4);
    buf.writeUInt32BE(Buffer.byteLength(data, "utf8"));
    writeSync(1, Buffer.concat([ buf, Buffer.from(data, "utf8") ]));
}

let onPlayerListReceived;

async function messageReceivedWA(message, originalMsgObj) {
    if (message.includes("*[Minecraft]* ")) {
        return;
    }
    if (message.split("§r: ")[1].startsWith(".mc")) {
        let parts = message.split("§r: ")[1].split(" ");
        if (parts.length <= 1) {
            return;
        }
        if (parts[1] === "help") {
            await globalSock.sendMessage(targetedGroupChatJid, { text: `
*WALink command overview*
- .mc help: Shows this.
- .mc players: Shows a list of currently online players.
            `.trim() }, { quoted: originalMsgObj });
        } else if (parts[1] === "players") {
            sendIPCMessage("lply", ""); // list players
            onPlayerListReceived = async msg => await globalSock.sendMessage(targetedGroupChatJid, { text: msg }, { quoted: originalMsgObj });
        } else {
            await globalSock.sendMessage(targetedGroupChatJid, { text: "Invalid command " + parts[1] }, { quoted: originalMsgObj });
        }
        return;
    }
    sendIPCMessage("nmsg", "§2[WhatsApp]§r " + message); // new message
}

async function messageReceivedMC(message) {
    if (!globalSock) {
        return;
    }
    if (message.includes("§2[WhatsApp]§r ")) {
        return;
    }
    await globalSock.sendMessage(targetedGroupChatJid, { text: "*[Minecraft]* " + message });
}

async function ipcMessageReceived(type, content) {
    logger.info("received " + type + " message");
    try {
        switch (type) {
            case "auth": { // auth
                await authenticate(qrUrl => {
                    logger.info("QR code received at url " + qrUrl);
                    sendIPCMessage("qrcd", qrUrl); // qr code
                }, reason => {
                    sendIPCMessage("auer", "" + reason); // auth error
                    logger.error("Issue occured trying to authenticate: " + reason);
                });
                sendIPCMessage("auok", ""); // auth ok
                break;
            }
            case "deau": { // deauth
                if (globalSock != null) {
                    await globalSock.logout();
                }
                if (existsSync("auth_state")) { 
                    rmdirSync("auth_state", { recursive: true });
                }
                if (existsSync("chats_state")) { 
                    rmdirSync("chats_state", { recursive: true });
                }
                sendIPCMessage("daok", ""); // deauth ok
                break;
            }
            case "gcnm": { // groupchat name
                logger.info("Setting group chat name to " + content);
                targetedGroupchatName = content;
                break;
            }
            case "stop": { // stop
                if (globalSock != null) {
                    await globalSock.end();
                }
                sendIPCMessage("scls", ""); // socket closed
            }
            case "init": { // init
                await startFull(reason => {
                    sendIPCMessage("ster", "" + reason); // start error
                    logger.error("Issue occured trying to start: " + reason);
                }, progress => {
                    sendIPCMessage("sync", "" + progress); // sync progress
                    logger.info("Sync progress at " + progress);
                });
                sendIPCMessage("wrdy", ""); // ready
                break;
            }
            case "nmsg": { // new message
                await messageReceivedMC(content);
                break;
            }
            case "cllo": { // clear logs
                readdirSync("logs").forEach(file => {
                    if (!file.endsWith(logFile)) {
                        unlinkSync("logs/" + file);
                    }
                });
                sendIPCMessage("clok", ""); // clear logs ok
                break;
            }
            case "plyl": { // player list
                if (onPlayerListReceived != null) {
                    onPlayerListReceived(content);
                }
                break;
            }
            default: {
                logger.warn("Received message of unknown type: " + type);
                break;
            }
        }
    } catch (e) {
        logger.error("Error occured while trying to handle IPC message: " + e);
    }
}

if (DEBUG) {
    const rl = createInterface({
        input: process.stdin,
        output: process.stdout
    });
    const inp = () => {
        rl.question("type+content:", async msg => {
            const type = msg.substring(0, 4);
            const content = msg.substring(4);
            await ipcMessageReceived(type, content);
            inp();
        });
    };
    inp();
} else {
    startReadingIPCMessages(process.stdin, async msg => {
        const { type, data } = msg;
        await ipcMessageReceived(type, data);
    });
}

