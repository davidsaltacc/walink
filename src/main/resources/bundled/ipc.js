
export function startReadingIPCMessages(stdin, handler) {
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

export function sendIPCMessage(stdout, type, content) {
    if (type.length !== 4) {
        throw new Error("IPC message type must be 4 characters");
    }
    const data = type + content;
    const buf = Buffer.alloc(4);
    buf.writeUInt32BE(Buffer.byteLength(data, "utf8"));
    stdout.write(new Uint8Array(buf));
    stdout.write(data);
}