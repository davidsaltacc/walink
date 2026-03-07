# WALink

TODO UPDATE THIS README AFTER REWRITE, LOTS OF THINGS MAY HAVE CHANGED

Experimental Minecraft mod that links the in-game text chat to a WhatsApp group chat without eating up the ram of your average Chrome tab. 
(The reason I mention the RAM usage is, some other implementations of WhatsApp automation literally open up a Chrome tab!)

Bugs, crashes and freezes may occur, it's still experimental. (Most of this is just me not implementing enough error handling, so 99% of the time if something goes wrong, anything may happen instead of it just restarting or informing you.)

# Disclaimer

I do not encourage the use of software to tamper with one's own WhatsApp account. This is merely for purposes of experimentation and a proof-of-concept.

### Risks of using this software

Realistically? Quite low. While I do not endorse using this mod, as it is against WhatsApp's TOS to use automation software on your account, this policy is most likely only in place to stop big scam operations. As long as you only use it on a small server with your friends, the real risk of getting your account terminated is extremely low. Besides, I cannot say this with 100% confidence, but I believe the baileys library impersonates the WhatsApp web protocol pretty well, and will not automatically light any major red flags without human review. 

Also to be noted is that this of course lowers the security of your WhatsApp group chat. While usually it is end-to-end-encrypted, this links it to the minecraft server which is not end-to-end-encrypted, and therefore *technically* more prone to hacks. Also, messages get stored in the server logs, you may or may not want to delete old logs. Also, the mod creates its own logs which may contain sensitive information. You have the ability to delete these logs via a command, more below.

# Installing

Put it in your fabric mod folder. Launch the game/server. Once launched, run /walink config chat_name NAME to configure the name of the group chat you want to link (MUST BE A GROUP CHAT). Run /walink auth, and wait for a QR code. Scan that with your phone to link WALink with your WhatsApp account. Run /walink restart to fully start the backend and make WALink work.

# Commands

In Minecraft:
- /walink help: [NOT IMPLEMENTED] Get a small help message about these commands.
- /walink auth: [NOT IMPLEMENTED] Authenticates WALink with your WhatsApp account. Usually required to run only the first time, unless re-authentication is neccessary. In your WhatsApp app, WALink will appear listed as "Google Chrome (Windows)", as the underlying library imitates a WhatsApp Web instance.
- /walink vanish on/off: [NOT IMPLEMENTED] Allows your messages to not appear in the WhatsApp group chat.
- /walink restart: Restarts the WALink Node.js backend to resolve possible issues and freezes.
- /walink config chat_name: [NOT IMPLEMENTED] Set the target group chat name. Warning: It has to be a unique group chat name, and needs to exactly match the name. If two group chats with the same name exist, it is essentially a gamble where the messages will end up in. 

In WhatsApp:
- .mc help: [NOT IMPLEMENTED] Get a small help message about these commands.
- .mc vanish on/off: [NOT IMPLEMENTED] Allows your messages to not appear in the Minecraft text chat
- .mc players: Shows a list of the players currently on the server. /walink vanish does not protect from appearing in this list.

# Technical Details

It requires Node.js to be installed. It bundles a node program along with baileys to allow lightweight communication with WhatsApp without heavy resource usage.

# Supported Versions

Anything from 1.21.10 onwards, I try to update for the latest version as soon as one releases.

Older versions may be supported, you'd probably have to edit the fabric.mod.json inside the mod archive. I do not guarantee for anything though.

Backports to major old versions may come (it's really just about rebuilding the mod with like 1 value changed and seeing if everything works as it should, which 99% of the time it should anyway). 

If you want the mod for a specific version, open an issue on the GitHub. ill try to recompile and test for that specific version of the game.

# TODOs/Future Plans

TODO: configurable group chat name (right now its hardcoded)
TODO: implement commands ( /vanish - notify when person joins on the server and they have vanish on incase they forgot, /help, /auth - replace authentication on start with command, .help )
TODO: properly escape stuff when sending to WA. expected behavior: escape stuff properly, for example: "*[Minecraft]* _justacoder\_ has left the server._" should appear with proper formatting, yet the _ in the username doesn't get escaped properly, leading to there being a trailing _ and incorrect formatting.
TODO: resolve issue in scenario: auth state exists, though link removed in WA. expected behavior: reset auth state, re-request auth. current behavior: infinite blocking on startup
TODO: command to clear walink logs