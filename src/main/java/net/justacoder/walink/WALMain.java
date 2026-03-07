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
import net.minecraft.text.Text;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
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
                    case "nmsg" -> messageReceivedWA(message.data);
                    default -> LOGGER.warn("Received IPC Message with unknown type: {}", message.type);
                }

            }

        }, "WALink IPC").start();

        sendIPCMessage(new IPCMessage("init", ""));

        onStartError = msg -> LOGGER.error("Error occurred while trying to start WALink: {}", msg);
        onSyncProgress = msg -> LOGGER.info("Syncing chats, progress at {} percent", msg);
        onBackendReady = ignored -> {
            if (FabricLoader.getInstance().getEnvironmentType() == EnvType.SERVER) { // we can safely notify users even earlier than usual if running a dedicated server
                sendIPCMessage(new IPCMessage("nmsg", "_Server starting..._"));
            }
        };

    }

    public static void messageReceivedMC(String author, String content) {
        sendIPCMessage(new IPCMessage("nmsg", author == null ? ("*" + escapeStringForWA(content) + "*") : ("*" + escapeStringForWA(author) + "*: " + escapeStringForWA(content))));
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
    private static Consumer<String> onStartError;
    private static Consumer<String> onBackendReady;
    private static Consumer<String> onSyncProgress;

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
                                CommandManager.literal("help")
                        ).then(
                                CommandManager.literal("restart").requires(CommandManager.requirePermissionLevel(CommandManager.ADMINS_CHECK)).executes(context -> {
                                    // TODO stop backend, restart anew
                                    return 1;
                                })
                        ).then(
                                CommandManager.literal("auth").requires(CommandManager.requirePermissionLevel(CommandManager.OWNERS_CHECK)).executes(context -> {

                                    onQrCode = qr -> context.getSource().sendMessage(Text.of("Authentication QR code available at " + qr + " - please scan this to link WALink with your WhatsApp account."));
                                    onAuthError = msg -> context.getSource().sendMessage(Text.of("Error occurred while authenticating WALink: " + msg));
                                    onAuthOk = empty -> context.getSource().sendMessage(Text.of("Successfully authenticated WALink with your WhatsApp account."));

                                    sendIPCMessage(new IPCMessage("auth", ""));

                                    return 1;
                                })
                        ).then(
                                CommandManager.literal("config").requires(CommandManager.requirePermissionLevel(CommandManager.OWNERS_CHECK))
                                        .then(
                                                CommandManager.literal("chat_name").then(
                                                        CommandManager.argument("name", StringArgumentType.string()).executes(context -> {
                                                            sendIPCMessage(new IPCMessage("gcnm", StringArgumentType.getString(context, "name")));
                                                            return 1;
                                                        })
                                                )
                                        )
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