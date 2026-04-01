package net.justacoder.walink;

import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.CommandManager;
import net.minecraft.text.ClickEvent;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.stream.Stream;

public class WALMain implements ModInitializer {

    public static final String MOD_ID = "walink";
    public static final Logger LOGGER = LoggerFactory.getLogger("WALink");
    public static final String VERSION = /*$ mod_version*/ "0.1";
    public static final String MINECRAFT = /*$ minecraft*/ "1.21.11";
    public static final Path WALINK_DATA = FabricLoader.getInstance().getGameDir().resolve("walink-data");
    public static final String HELP_TEXT = """
            §a§lWALink command overview§r
            - /walink help: Show this.
            - /walink auth: Authenticates WALink with your WhatsApp account. Usually required to run only the first time, unless re-authentication is neccessary. In your WhatsApp app, WALink will appear listed as "Google Chrome (Windows)", as the underlying library imitates a WhatsApp Web instance.
            - /walink deauth: Deauthenticate WALink from your WhatsApp account. WALink needs to be running for it to be unlinked in the WhatsApp app, otherwise it will remain in the app (a zombie, really. WALink will ask for new authentication next time). Useful when issues concerning authentication/login occur.
            - /walink vanish on/off: [NOT IMPLEMENTED] Allows your messages to not appear in the WhatsApp group chat.
            - /walink stop: Stops WALink. Can be restarted with /restart.
            - /walink restart: Restarts the WALink Node.js backend to resolve possible issues and freezes, or just starts it if it wasn't started before.
            - /walink chat_name: Set the target group chat name. Warning: It has to be a unique group chat name, and needs to exactly match the name. If two group chats with the same name exist, it is essentially a gamble where the messages will end up in.
            - /walink clear_logs: Clears old logs (except the one for the current run).""";

    // TODO
    // clickable links in sent messages
    // config for start/shutdown messages
    // vanishing

    private static MinecraftServer mcServer;
    private static InputStream nodeStdout;
    private static OutputStream nodeStdin;
    private static Process process;

    public static void onEarlyInit() {

        LOGGER.info("Checking for NodeJS");

        ProcessBuilder nodeProcessBuilder = new ProcessBuilder();
        nodeProcessBuilder.command("node", "-v");

        try {
            nodeProcessBuilder.start();
        } catch (IOException e) {
            throw new RuntimeException("NodeJS could not be found or run. It is required for running WALink. Please install and put it in your path.", e);
        }

        WALConfig.loadConfig();
        Runtime.getRuntime().addShutdownHook(new Thread(WALConfig::saveConfig, "WALink Shutdown Config Save"));

        LOGGER.info("Initializing WALink");

        Optional<ModContainer> container = FabricLoader.getInstance().getModContainer(MOD_ID);
        if (container.isEmpty()) {
            throw new RuntimeException("This should not happen. What the hell did you do to end up here?");
        }

        Optional<Path> bundledOpt = container.get().findPath("bundled");
        if (bundledOpt.isEmpty()) {
            throw new RuntimeException("This should not happen, WALink data was not found bundled with the mod. WALink will not work. Please reinstall WALink");
        }
        Path bundledRoot = bundledOpt.get();

        try (Stream<Path> paths = Files.walk(bundledRoot)) {
            paths.forEach(src -> {
                try {
                    Path rel = bundledRoot.relativize(src);
                    Path dest = WALINK_DATA.resolve(rel.toString());
                    if (Files.isDirectory(src)) {
                        Files.createDirectories(dest);
                    } else {
                        if (dest.getParent() != null) {
                            Files.createDirectories(dest.getParent());
                        }
                        Files.copy(src, dest, StandardCopyOption.REPLACE_EXISTING);
                    }
                } catch (IOException e) {
                    throw new RuntimeException("Failed copying bundled resource: " + src, e);
                }
            });
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        LOGGER.info("Downloading dependencies");

        ProcessBuilder npmProcessBuilder = new ProcessBuilder();
        npmProcessBuilder.directory(WALINK_DATA.toFile());
        npmProcessBuilder.command(System.getProperty("os.name").startsWith("Windows") ? "npm.cmd" : "npm", "install");
        try {
            Process process = npmProcessBuilder.start();
            process.waitFor();
        } catch (Exception e) {
            throw new RuntimeException("Failed to download dependencies via npm install. Please check if you have a working internet connection and NodeJS got installed properly.", e);
        }

        LOGGER.info("Launching Node.js backend");

        ProcessBuilder processBuilder = new ProcessBuilder();
        processBuilder.directory(WALINK_DATA.toFile());
        processBuilder.command("node", "main.js");

        try {
            process = processBuilder.start();
        } catch (IOException e) {
            throw new RuntimeException("Failed to start Node.js backend", e);
        }
        Runtime.getRuntime().addShutdownHook(new Thread(WALMain::shutdownNode, "WALink IPC Shutdown"));

        nodeStdout = process.getInputStream(); // okay, who the fuck decided to name these this way
        nodeStdin = process.getOutputStream();

        new Thread(() -> {

            while (process.isAlive()) {

                IPCMessage message = readIPCMessage();

                switch (message.type) {
                    case "qrcd" -> {
                        if (onQrCode != null) {
                            onQrCode.accept(message.data);
                        }
                    }
                    case "auer" -> {
                        if (onAuthError != null) {
                            onAuthError.accept(message.data);
                        }
                    }
                    case "auok" -> {
                        if (onAuthOk != null) {
                            onAuthOk.accept(message.data);
                        }
                    }
                    case "daok" -> {
                        if (onDeAuthOk != null) {
                            onDeAuthOk.accept(message.data);
                        }
                    }
                    case "ster" -> {
                        if (onStartError != null) {
                            onStartError.accept(message.data);
                        }
                    }
                    case "wrdy" -> {
                        if (onBackendReady != null) {
                            onBackendReady.accept(message.data);
                        }
                    }
                    case "sync" -> {
                        if (onSyncProgress != null) {
                            onSyncProgress.accept(message.data);
                        }
                    }
                    case "scls" -> {
                        if (onClosedSock != null) {
                            onClosedSock.accept(message.data);
                        }
                    }
                    case "clok" -> {
                        if (onLogsCleared != null) {
                            onLogsCleared.accept(message.data);
                        }
                    }
                    case "cnin" -> {
                        if (onConnectionInfo != null) {
                            onConnectionInfo.accept(message.data);
                        }
                    }
                    case "lply" -> {
                        if (mcServer == null) {
                            sendIPCMessage(new IPCMessage("plyl", "Server not started."));
                        }
                        String text = String.join("\n", mcServer.getPlayerManager().getPlayerList().stream().map(p -> "- " + escapeStringForWA(p.getName().getString())).toList());
                        if (text.isEmpty()) {
                            text = "No players are currently online.";
                        } else {
                            text = "Currently online:\n" + text;
                        }
                        sendIPCMessage(new IPCMessage("plyl", text));
                    }
                    case "nmsg" -> messageReceivedWA(message.data);
                    default -> LOGGER.warn("Received IPC Message with unknown type: {}", message.type);
                }

            }

        }, "WALink IPC").start();

        onStartError = msg -> LOGGER.error("Error occurred while trying to start WALink: {}", msg);
        onSyncProgress = msg -> LOGGER.info("Syncing chats, progress at {} percent", msg);
        onBackendReady = ignored -> {
            if (FabricLoader.getInstance().getEnvironmentType() == EnvType.SERVER) { // we can safely notify users even earlier than usual if running a dedicated server
                sendIPCMessage(new IPCMessage("nmsg", "_Server starting..._"));
            }
        };
        onConnectionInfo = msg -> {
            LOGGER.warn("Important connection info received: {}", msg);
            if (mcServer != null) {
                mcServer.getPlayerManager().broadcast(Text.of(WALConfig.State.prefixInMinecraft + "§6" + msg + "§r"), false);
            }
        };

        if (WALConfig.State.groupChatName != null) {
            sendIPCMessage(new IPCMessage("gcnm", WALConfig.State.groupChatName));
        }
        sendIPCMessage(new IPCMessage("pfmc", WALConfig.State.prefixInMinecraft));
        sendIPCMessage(new IPCMessage("pfwa", WALConfig.State.prefixInWhatsapp));
        sendIPCMessage(new IPCMessage("init", ""));

    }

    public static void messageReceivedMC(String author, String content) {
        sendIPCMessage(new IPCMessage("nmsg", author == null ? ("_" + escapeStringForWA(content) + "_") : ("_" + escapeStringForWA(author) + "_: " + escapeStringForWA(content))));
    }

    public static void messageReceivedWA(String content) {
        mcServer.getPlayerManager().broadcast(Text.of(content), false);
    }


    public static String escapeStringForWA(String in) {
        return in.replace("_", "ˍ").replace("*", "∗"); // this is the best solution. may look odd in some cases, but better than fucked up formatting
    }

    private static Consumer<String> onQrCode;
    private static Consumer<String> onAuthError;
    private static Consumer<String> onAuthOk;
    private static Consumer<String> onDeAuthOk;
    private static Consumer<String> onStartError;
    private static Consumer<String> onBackendReady;
    private static Consumer<String> onSyncProgress;
    private static Consumer<String> onClosedSock;
    private static Consumer<String> onLogsCleared;
    private static Consumer<String> onConnectionInfo;

    @Override
    public void onInitialize() {

        ServerLifecycleEvents.SERVER_STARTING.register(server -> {
            mcServer = server;
            if (FabricLoader.getInstance().getEnvironmentType() == EnvType.CLIENT) { // if on a client, we need to notify about server startup only when the actual integrated server starts, not anything before
                sendIPCMessage(new IPCMessage("nmsg", "_Server starting..._"));
            }
        });
        ServerLifecycleEvents.SERVER_STARTED.register(server -> sendIPCMessage(new IPCMessage("nmsg", "_Server started._")));
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> sendIPCMessage(new IPCMessage("nmsg", "_Server stopping._")));

        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> dispatcher.register(
                CommandManager.literal(MOD_ID)
                        .then(
                                CommandManager.literal("vanish")
                                        .then(CommandManager.literal("off"))
                                        .then(CommandManager.literal("on"))
                        ).then(
                                CommandManager.literal("help").executes(context -> {
                                    context.getSource().sendMessage(Text.of(HELP_TEXT));
                                    return 1;
                                })
                        ).then(
                                CommandManager.literal("stop").requires(CommandManager.requirePermissionLevel(CommandManager.ADMINS_CHECK)).executes(context -> {

                                    onClosedSock = ignored -> context.getSource().sendMessage(Text.of("Successfully stopped WALink"));

                                    sendIPCMessage(new IPCMessage("stop", ""));

                                    return 1;
                                })
                        ).then(
                                CommandManager.literal("restart").requires(CommandManager.requirePermissionLevel(CommandManager.ADMINS_CHECK)).executes(context -> {

                                    onClosedSock = ignored -> {
                                        onStartError = msg -> context.getSource().sendMessage(Text.of("Error occurred while trying to start WALink: " + msg));
                                        onSyncProgress = msg -> context.getSource().sendMessage(Text.of("Syncing chats, progress at " + msg + " percent"));
                                        onBackendReady = ignored2 -> context.getSource().sendMessage(Text.of("Successfully restarted WALink"));
                                        sendIPCMessage(new IPCMessage("init", ""));
                                    };

                                    sendIPCMessage(new IPCMessage("stop", ""));

                                    return 1;
                                })
                        ).then(
                                CommandManager.literal("auth").requires(CommandManager.requirePermissionLevel(CommandManager.OWNERS_CHECK)).executes(context -> {
                                    onQrCode = qr -> {
                                        MutableText message = Text.literal("Authentication QR code available at ");
                                        message.append(Text.literal(qr.strip()).styled(style -> {
                                            try {
                                                return style.withUnderline(true).withColor(Formatting.DARK_BLUE).withClickEvent(new ClickEvent.OpenUrl(new URI(qr.strip())));
                                            } catch (URISyntaxException e) {
                                                throw new RuntimeException("This should not happen. Invalid QR code image URL received", e);
                                            }
                                        }));
                                        message.append(" - please scan this to link WALink with your WhatsApp account.");
                                        context.getSource().sendMessage(message);
                                    };
                                    onAuthError = msg -> context.getSource().sendMessage(Text.of("Error occurred while authenticating WALink: " + msg));
                                    onAuthOk = empty -> context.getSource().sendMessage(Text.of("Successfully authenticated WALink with your WhatsApp account."));

                                    sendIPCMessage(new IPCMessage("auth", ""));

                                    return 1;
                                })
                        ).then(
                                CommandManager.literal("chat_name").requires(CommandManager.requirePermissionLevel(CommandManager.OWNERS_CHECK)).then(
                                        CommandManager.argument("name", StringArgumentType.string()).executes(context -> {

                                            WALConfig.State.groupChatName = StringArgumentType.getString(context, "name");
                                            sendIPCMessage(new IPCMessage("gcnm", WALConfig.State.groupChatName));
                                            context.getSource().sendMessage(Text.of("Successfully set group chat name"));

                                            return 1;
                                        })
                                )
                        ).then(
                                CommandManager.literal("deauth").requires(CommandManager.requirePermissionLevel(CommandManager.OWNERS_CHECK)).executes(context -> {

                                    onDeAuthOk = ignored -> context.getSource().sendMessage(Text.of("Successfully deauthenticated WALink"));

                                    sendIPCMessage(new IPCMessage("deau", ""));

                                    return 1;
                                })
                        ).then(
                                CommandManager.literal("clear_logs").requires(CommandManager.requirePermissionLevel(CommandManager.ADMINS_CHECK)).executes(context -> {

                                    onLogsCleared = ignored -> context.getSource().sendMessage(Text.of("Successfully cleared logs"));

                                    sendIPCMessage(new IPCMessage("cllo", ""));

                                    return 1;
                                })
                        )
        ));

    }

    private static void shutdownNode() {
        try {
            process.destroy();
        } catch (Exception e) {
            LOGGER.error("Error while shutting down NodeJS process", e);
        }
    }

    private record IPCMessage(String type, String data) {}

    private static IPCMessage readIPCMessage() {
        try {
            byte[] length = nodeStdout.readNBytes(4);
            if (length.length == 0) {
                return new IPCMessage("", "");
            }
            int len = ByteBuffer.wrap(length).order(ByteOrder.BIG_ENDIAN).getInt();
            LOGGER.info("Reading IPC message of {} bytes, length bytes received: {}", len, new String(length, StandardCharsets.UTF_8));
            byte[] data = nodeStdout.readNBytes(len);
            if (data.length == 0) {
                return new IPCMessage("", "");
            }
            String content = new String(data, StandardCharsets.UTF_8);
            LOGGER.info("Read IPC message of type {}", content.substring(0, 4));
            return new IPCMessage(
                    content.substring(0, 4),
                    content.substring(4)
            );
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private static void sendIPCMessage(IPCMessage message) {
        if (message.type.length() != 4) {
            throw new RuntimeException("Trying to send IPC message with type length that isn't 4");
        }
        if (nodeStdin == null) {
            throw new RuntimeException("Trying to send IPC message before initializing");
        }
        try {
            String data = message.type + message.data;
            byte[] bytes = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(data.getBytes(StandardCharsets.UTF_8).length).array();
            nodeStdin.write(bytes);
            nodeStdin.write(data.getBytes(StandardCharsets.UTF_8));
            nodeStdin.flush();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

}