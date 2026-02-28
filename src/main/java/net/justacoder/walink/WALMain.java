package net.justacoder.walink;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.PlayerManager;
import net.minecraft.server.command.CommandManager;
import net.minecraft.text.Text;
import org.jetbrains.annotations.Nullable;
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
import java.util.concurrent.CountDownLatch;
import java.util.stream.Stream;

public class WALMain implements ModInitializer {

    public static final String MOD_ID = "walink";
    public static final Logger LOGGER = LoggerFactory.getLogger("WALink");
    public static final String VERSION = /*$ mod_version*/ "0.1";
    public static final String MINECRAFT = /*$ minecraft*/ "1.21.10";

    public static final Path WALINK_DATA = FabricLoader.getInstance().getGameDir().resolve("walink-data");

    private static InputStream nodeStdout;
    private static OutputStream nodeStdin;
    private static Thread ipcReadThread;
    private static Process process;
    private static CountDownLatch shutdownPoint;

    private static MinecraftServer mcServer;

    static void initWALink() {

        LOGGER.info("Initializing WALink");

        Optional<ModContainer> container = FabricLoader.getInstance().getModContainer(MOD_ID);
        if (container.isEmpty()) {
            LOGGER.error("This should not happen. Please reinstall WALink");
            return;
        }

        Optional<Path> bundledOpt = container.get().findPath("bundled");
        if (bundledOpt.isEmpty()) {
            LOGGER.error("This should not happen, WALink data was not found bundled with the mod. WALink will not work. Please reinstall WALink");
            return;
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

        ProcessBuilder processBuilder = new ProcessBuilder();
        processBuilder.directory(WALINK_DATA.toFile());
        processBuilder.command("node", "main.js");

        try {

            shutdownPoint = new CountDownLatch(1);

            process = processBuilder.start();
            Runtime.getRuntime().addShutdownHook(new Thread(WALMain::shutdownNode, "WALink IPC Shutdown"));

            nodeStdout = process.getInputStream(); // okay, who the fuck decided to name these this way
            nodeStdin = process.getOutputStream();

            sendIPCMessage(new IPCMessage("gcnm", "test"));
            sendIPCMessage(new IPCMessage("init", ""));

            CountDownLatch readyPoint = new CountDownLatch(1);
            ipcReadThread = new Thread(() -> {

                while (shutdownPoint.getCount() > 0) {

                    if (!process.isAlive()) {
                        initWALink();
                        throw new RuntimeException("NodeJS WALink backend exited unexpectedly.");
                    }

                    IPCMessage message = readIPCMessage();
                    try {
                        if (message.type.equals("wrdy")) {
                            readyPoint.countDown();
                        }
                        if (message.type.equals("qrcd")) {
                            LOGGER.info("Authentication QR code available at {} - please scan this to link WALink with your WhatsApp account.", message.data);
                        }
                        if (message.type.equals("sync")) {
                            LOGGER.info("WhatsApp sync progress at {}%", message.data);
                        }
                        if (message.type.equals("nmsg")) {
                            messageReceivedWA(mcServer, message.data);
                        }
                    } catch (Exception e) {
                        LOGGER.error("Error handling IPC message", e);
                    }
                }

            });

            ipcReadThread.setName("WALink IPC Read");
            ipcReadThread.start();
            readyPoint.await();

        } catch (Exception e) {
            throw new RuntimeException("Error occurred while initializing WALink ", e);
        }

    }

    private static void shutdownNode() {
        shutdownPoint.countDown();
        try {
            process.destroy();
        } catch (Exception e) {
            LOGGER.error("Error while shutting down NodeJS process", e);
        }
    }

    public static void onEarlyInit() {

        LOGGER.info("Checking for NodeJS");

        ProcessBuilder processBuilder = new ProcessBuilder();
        processBuilder.command("node", "-v");

        try {
            processBuilder.start();
        } catch (IOException e) {
            throw new RuntimeException("NodeJS could not be found or run. It is required for running WALink. Please install and put it in your path.", e);
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

        initWALink();

        if (FabricLoader.getInstance().getEnvironmentType() == EnvType.SERVER) { // we can safely notify users even earlier than usual if running a dedicated server
            sendIPCMessage(new IPCMessage("nmsg", "*[Minecraft]* _Server starting..._"));
        }

    }

    @Override
    public void onInitialize() {

        ServerLifecycleEvents.SERVER_STARTING.register(server -> {
            mcServer = server;
            if (FabricLoader.getInstance().getEnvironmentType() == EnvType.CLIENT) { // if on a client, we need to notify about server startup only when the actual integrated server starts, not anything before
                sendIPCMessage(new IPCMessage("nmsg", "*[Minecraft]* _Server starting..._"));
            }
        });
        ServerLifecycleEvents.SERVER_STARTED.register(server -> sendIPCMessage(new IPCMessage("nmsg", "*[Minecraft]* _Server started._")));
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> sendIPCMessage(new IPCMessage("nmsg", "*[Minecraft]* _Server stopping._")));

        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            dispatcher.register(
                    CommandManager.literal(MOD_ID)
                            .then(
                                CommandManager.literal("vanish")
                                    .then(CommandManager.literal("off"))
                                    .then(CommandManager.literal("on"))
                            ).then(
                                CommandManager.literal("help")
                            ).then(
                                CommandManager.literal("restart").requires(CommandManager.requirePermissionLevel(CommandManager.ADMINS_CHECK)).executes(context -> {
                                    context.getSource().sendMessage(Text.of("Restarting WALink..."));
                                    shutdownNode();
                                    initWALink();
                                    return 1;
                                })
                            ).then(
                                    CommandManager.literal("auth").requires(CommandManager.requirePermissionLevel(CommandManager.OWNERS_CHECK))
                            )
            );
        });

    }

    private static String escapeStringForWA(String in) {
        return in.replace("*", "\u200c*\u200c").replace("_", "\u200c_\u200c");
    }

    public static void messageReceivedWA(@Nullable MinecraftServer server, String message) {

        if (server == null) {
            return;
        }

        String findStr = "§r: ";
        int index = message.indexOf(findStr);
        String msgSliced = "";
        if (index > 0) {
            msgSliced = message.substring(index + findStr.length());
        }
        if (msgSliced.startsWith(".mc")) {
            String[] parts = msgSliced.split(" ");
            if (parts.length <= 1) {
                return;
            }
            if (parts[1].equals("players")) {
                sendIPCMessage(new IPCMessage("nmsg", "*[Minecraft]* Online: " + String.join("", server.getPlayerManager().getPlayerList().stream().map(player -> "\n- " + escapeStringForWA(player.getName().getString())).toList())));
            }
            if (parts[1].equals("help")) {

            }
            if (parts[1].equals("vanish")) {

            }
            return;
        }

        PlayerManager manager = server.getPlayerManager();
        manager.broadcast(Text.of(message), false);
    }

    public static void messageReceivedMC(@Nullable String author, String message) {
        if (author == null) {
            sendIPCMessage(new IPCMessage("nmsg", "*[Minecraft]* _" + escapeStringForWA(message) + "_"));
        } else {
            sendIPCMessage(new IPCMessage("nmsg", "*[Minecraft]* _" + escapeStringForWA(author) + "_: " + escapeStringForWA(message)));
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
            throw new Error("Trying to send IPC message with type length that isn't 4");
        }
        if (nodeStdin == null) {
            throw new Error("Trying to send IPC message before initializing");
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