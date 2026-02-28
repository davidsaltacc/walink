# WALink

Highly unstable Minecraft mod that links the in-game text chat to a WhatsApp group chat.

Bugs and crashes may occur all the time, it's still very experimental.

# Disclaimer

I do not encourage the use of software to tamper with one's own WhatsApp account. This is merely for purposes of experimentation and a proof-of-concept.

### Risks of using this software

Realistically? Quite low. While I do not endorse using this mod, as it is against WhatsApp's TOS to use automation software on your account, this policy is most likely only in place to stop big scam operations. As long as you only use it on a small server with your friends, the real risk of getting your account terminated is extremely low. Besides, I cannot say this with 100% confidence, but I believe the baileys library impersonates the WhatsApp web protocol pretty well, and will not automatically light any major red flags without human review. 

# Commands

In Minecraft:
- /walink help: [NOT IMPLEMENTED] Get a small help message about these commands.
- /walink auth: [NOT IMPLEMENTED] Authenticates WALink with your WhatsApp account. Usually required to run only the first time, unless re-authentication is neccessary.
- /walink vanish on/off: [NOT IMPLEMENTED] Allows your messages to not appear in the WhatsApp group chat.
- /walink restart: Restarts the WALink Node.js backend to resolve possible issues and freezes.

In WhatsApp:
- .mc help: [NOT IMPLEMENTED] Get a small help message about these commands.
- .mc vanish on/off: [NOT IMPLEMENTED] Allows your messages to not appear in the Minecraft text chat
- .mc players: Shows a list of the players currently on the server. /walink vanish does not protect from appearing in this list.

# Technical Details

It requires Node.js to be installed. It bundles a node program along with baileys to allow lightweight communication with WhatsApp that doesn't eat up the ram of your average Chrome tab.

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
