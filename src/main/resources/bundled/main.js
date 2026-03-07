import { existsSync, mkdirSync, openAsBlob, readFileSync, unlinkSync, writeFileSync, writeSync } from "node:fs";
import { createInterface } from "node:readline";
import makeWASocket, { Browsers, DisconnectReason, useMultiFileAuthState, makeCacheableSignalKeyStore } from "baileys";
import dateFormat from "dateformat";
import NodeCache from "node-cache";
import QRCode from "qrcode";
import P from "pino";

const DEBUG = true;

const { state, saveCreds } = await useMultiFileAuthState("auth_state");
const groupCache = new NodeCache();

if (!existsSync("logs")) {
    mkdirSync("logs");
}

export const logger = P({
    transport: {
        target: "pino-pretty",
        options: {
            colorize: false,
            destination: "logs/" + dateFormat("yyyy-mm-dd-HH-MM-ss") + ".log"
        }
    },
    level: "info"
});

let targetedGroupchatName;
let targetedGroupChatJid;

let allChats = [];
let allContacts = {};

let globalSock;

async function makeSock(forAuthOnly) {
    
    forAuthOnly = !!forAuthOnly;

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
        shouldSyncHistoryMessage: () => !forAuthOnly
    };

    if (!forAuthOnly) {
        conf.cachedGroupMetadata = async (jid) => groupCache.get(jid);
    }

    return makeWASocket(conf);

}

async function authenticate(onQrCodeUrl, onFail) {
       
    let sock = await makeSock(true);

    sock.ev.on("creds.update", saveCreds);

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

    sock.end();

}

async function startFull(onFail, onSyncProgress) {

    if (targetedGroupchatName == null) {
        onFail("Cannot start without having a valid group chat name.");
        return;
    }

    if (globalSock != null) {
        globalSock.end();
    }

    const makeFullSock = async (done, rej) => {

        let sock = globalSock = await makeSock(false);
        
        sock.ev.on("creds.update", saveCreds);

        sock.ev.on("connection.update", async update => {

            try {
            
                const { connection, lastDisconnect, qr } = update;

                if (qr) {
                    onFail("Authentication not set up. Please link WALink before attempting a full start.");
                    sock.end();
                    done();
                }

                if (connection === "close" && lastDisconnect?.error?.output?.statusCode === DisconnectReason.restartRequired) {
                    makeFullSock();
                } else if (connection === "close" && !!lastDisconnect?.error) {
                    onFail(lastDisconnect?.error?.output);
                    rej(lastDisconnect?.error?.output);
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
                    
                            await messageReceivedWA(stringifyWAMessageForMC(msg, false));
                            
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
                    data: dataUtf8.substring(4),
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

async function messageReceivedWA(message) {
    sendIPCMessage("nmsg", "§2[WhatsApp]§r " + message); // new message
}

async function messageReceivedMC(message) {
    if (!globalSock) {
        return;
    }
    await globalSock.sendMessage(targetedGroupChatJid, { text: "*[Minecraft]* " + message });
}

// TODO if any instance of socket exists and is alive, ignore all (init, auth, ...), otherwise calling /auth or /init twice will cause issues

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
            case "gcnm": { // groupchat name
                targetedGroupchatName = content;
                break;
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
            const comment = msg.substring(4);
            await ipcMessageReceived(type, comment);
            inp();
        });
    };
    inp();
} else {
    startReadingIPCMessages(process.stdin, async msg => {
        const { type, content } = msg;
        await ipcMessageReceived(type, content);
    });
}

