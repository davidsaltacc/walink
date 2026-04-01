# WALink

Experimental Minecraft mod that links the in-game text chat to a WhatsApp group chat without eating up the ram of your average Chrome tab. 
(The reason I mention the RAM usage is, some other implementations of WhatsApp automation literally open up a Chrome tab!)

Bugs, crashes and freezes may occur, it's still experimental. (Most of this is just me not implementing enough error handling, so 99% of the time if something goes wrong, anything may happen instead of it just restarting or informing you.)

[Most issues should be gone, but there is always a small chance something goes wrong anyway. The best first fix is probably to just restart walink.](https://en.wikipedia.org/wiki/Ostrich_algorithm)

Should reconnect after network disrupts for a short time, not guaranteed it works though. Sometimes a manual restart is neccessary.

# Disclaimer

I do not encourage the use of software to tamper with one's own WhatsApp account. This is merely for purposes of experimentation and a proof-of-concept.

### Risks of using this software

Realistically? Quite low. While I do not endorse using this mod, as it is against WhatsApp's TOS to use automation software on your account, this policy is most likely only in place to stop big scam operations. As long as you only use it on a small server with your friends, the real risk of getting your account terminated is extremely low. Besides, I cannot say this with 100% confidence, but I believe the baileys library impersonates the WhatsApp web protocol pretty well, and will not automatically light any major red flags without human review. 

Also to be noted is that this of course lowers the security of your WhatsApp group chat. While usually it is end-to-end-encrypted, this links it to the minecraft server which is not end-to-end-encrypted, and therefore *technically* more prone to hacks. Also, messages get stored in the server logs, you may or may not want to delete old logs. Also, the mod creates its own logs which may contain sensitive information. You have the ability to delete these logs via a command, more below.

# Installing / Setup

Install [Node.js](https://nodejs.org/en/download) along with npm (important!) and make sure it is on your path (on the download page, below you can just download pre-built installers/binaries for your system). Put the mod .jar in your fabric mod folder. Launch the game/server. Once launched, run /walink chat_name NAME to configure the name of the group chat you want to link (MUST BE A GROUP CHAT) (MUST MATCH THE GROUP CHAT NAME EXACTLY). Run /walink auth, and wait for a QR code. Scan that with your phone to link WALink with your WhatsApp account. Run /walink restart to fully start the backend and make WALink work.

### Known Issues

It rarely may happen that when starting WALink, it tries to sync old chats, says "0 percent" a few times, then gets stuck and never continues (wait for about a minute to be 100% sure it's stuck). This is most likely an issue with the underlying library WALink uses, and the only way to fix this is just deauthenticating WALink and re-authenticating.

It also may happen that it just gets stuck while authenticating or starting, in that case just stop and re-try the process.

One fix is always to simply stop minecraft, remove the walink-data folder all together, and try again. It sometimes runs into inexplicable issues. Debugging them is a nightmare as you never know if it's a library, my own code, or something else entirely, and then when I try to reproduce the issue it's just completely gone.

# Configuring

The targeted group chat can be set via commands (more above and below). The rest of the config can be edited via the config file (config/walink.json). To apply manual changes to the config, you will need to first shut down the server completely! Otherwise, it will just overwrite the config on exit.

# Uninstalling

Just remove the mod. Optionally you may want to delete the walink-data folder, as its just leftover files by WALink that may or may not also contain sensitive information. Before that, in the game you can also run /walink deauth before to save you some work.

# Commands

In Minecraft:
- /walink help: Get a small help message about these commands.
- /walink auth: Authenticates WALink with your WhatsApp account. Usually required to run only the first time, unless re-authentication is neccessary. In your WhatsApp app, WALink will appear listed as "Google Chrome (Windows)", as the underlying library imitates a WhatsApp Web instance.
- /walink deauth: Deauthenticate WALink from your WhatsApp account. WALink needs to be running for it to be unlinked in the WhatsApp app, otherwise it will remain in the app (a zombie, really. WALink will ask for new authentication next time). Useful when issues concerning authentication/login occur. 
- /walink vanish on/off: [NOT IMPLEMENTED] Allows your messages to not appear in the WhatsApp group chat.
- /walink stop: Stops WALink. Can be restarted with /restart.
- /walink restart: Restarts the WALink Node.js backend to resolve possible issues and freezes, or just starts it if it wasn't started before.
- /walink chat_name: Set the target group chat name. Warning: It has to be a unique group chat name, and needs to exactly match the name. If two group chats with the same name exist, it is essentially a gamble where the messages will end up in. 
- /walink clear_logs: Clears old logs (except the one for the current run).

In WhatsApp:
- .mc help: Get a small help message about these commands.
- .mc vanish on/off: [NOT IMPLEMENTED] Allows your messages to not appear in the Minecraft text chat
- .mc players: Shows a list of the players currently on the server. /walink vanish does not protect from appearing in this list.

# Technical Details

It requires Node.js and npm to be installed. It bundles a node program along with baileys to allow lightweight communication with WhatsApp without heavy resource usage.

# Supported Versions

Anything from 1.21.10 onwards, I try to update for the latest version as soon as one releases.

Older versions may be supported, you'd probably have to edit the fabric.mod.json inside the mod archive. I do not guarantee for anything though.

Backports to major old versions may come (it's really just about rebuilding the mod with like 1 value changed and seeing if everything works as it should, which 99% of the time it should anyway). 

If you want the mod for a specific version, open an issue on the GitHub. ill try to recompile and test for that specific version of the game.
