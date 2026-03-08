package net.justacoder.walink;

import com.google.gson.*;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public abstract class WALConfig {

    public static Path CONFIG_FILE = FabricLoader.getInstance().getConfigDir().resolve("walink.json");

    public static void loadConfig() {
        if (!CONFIG_FILE.toFile().exists()) {
            State.loaded = true;
            return;
        }
        WALMain.LOGGER.info("Loading config");
        try {
            String json = Files.readString(CONFIG_FILE);
            GsonBuilder builder = new GsonBuilder();
            Gson gson = builder.create();
            JsonObject obj = gson.fromJson(json, JsonObject.class);

            State.groupChatName = obj.has("group_chat_name") ? obj.get("group_chat_name").getAsString() : State.groupChatName;
            State.prefixInMinecraft = obj.has("prefix_minecraft") ? obj.get("prefix_minecraft").getAsString() : State.prefixInMinecraft;
            State.prefixInWhatsapp = obj.has("prefix_whatsapp") ? obj.get("prefix_whatsapp").getAsString() : State.prefixInWhatsapp;
            State.vanishedPlayersMc = obj.has("vanished_players_minecraft") ? obj.get("vanished_players_minecraft").getAsJsonArray().asList().stream().map(JsonElement::getAsString).toList() : State.vanishedPlayersMc;
            State.vanishedUsersWa = obj.has("vanished_users_whatsapp") ? obj.get("vanished_users_whatsapp").getAsJsonArray().asList().stream().map(JsonElement::getAsString).toList() : State.vanishedUsersWa;

            State.loaded = true;
        } catch (IOException e) {
            throw new RuntimeException("Failed to read config", e);
        }
    }

    public static void saveConfig() {
        try {
            if (!State.loaded) {
                WALMain.LOGGER.warn("Tried to save config before it was loaded");
                return;
            }
            WALMain.LOGGER.info("Saving config");
            GsonBuilder builder = new GsonBuilder();
            builder.setFormattingStyle(FormattingStyle.PRETTY.withIndent(" ".repeat(4)));
            Gson gson = builder.create();
            JsonObject obj = new JsonObject();

            JsonArray vanishedMc = new JsonArray();
            JsonArray vanishedWa = new JsonArray();
            State.vanishedPlayersMc.forEach(vanishedMc::add);
            State.vanishedUsersWa.forEach(vanishedWa::add);

            obj.add("group_chat_name", new JsonPrimitive(State.groupChatName));
            obj.add("prefix_minecraft", new JsonPrimitive(State.prefixInMinecraft));
            obj.add("prefix_whatsapp", new JsonPrimitive(State.prefixInWhatsapp));
            obj.add("vanished_players_minecraft", vanishedMc);
            obj.add("vanished_users_whatsapp", vanishedWa);

            String json = gson.toJson(obj);
            Files.writeString(CONFIG_FILE, json);
        } catch (IOException e) {
            WALMain.LOGGER.error("Failed to save config", e);
        }
    }

    public abstract static class State {
        public static boolean loaded = false;
        public static String groupChatName;
        public static String prefixInMinecraft = "§2[WhatsApp]§r ";
        public static String prefixInWhatsapp = "*[Minecraft]* ";
        public static List<String> vanishedPlayersMc = new ArrayList<>();
        public static List<String> vanishedUsersWa = new ArrayList<>();
    }

}
