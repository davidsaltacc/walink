package net.justacoder.walink;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.annotations.SerializedName;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.file.Path;
import java.util.List;

public abstract class WALConfig {

    public static Path CONFIG_FILE = FabricLoader.getInstance().getConfigDir().resolve("walink.json");

    private static Config config = null;

    public static void loadConfig() {
        WALMain.LOGGER.info("Loading config");
        String json = "{}";
        GsonBuilder builder = new GsonBuilder();
        Gson gson = builder.create();
        config = gson.fromJson(json, Config.class);
    }

    public static void saveConfig() {
        WALMain.LOGGER.info("Saving config");
        if (config == null) {
            WALMain.LOGGER.warn("Tried to save config before it was loaded");
            return;
        }
        GsonBuilder builder = new GsonBuilder();
        Gson gson = builder.create();
        String json = gson.toJson(config);
    }

    public static class Config {
        @SerializedName("prefix_in_minecraft")
        public String prefixInMinecraft;
        @SerializedName("prefix_in_whatsapp")
        public String prefixInWhatsapp;
        @SerializedName("group_chat_name")
        public String groupChatName;
        @SerializedName("vanished_users_minecraft")
        public List<String> vanishedPlayersMc;
        @SerializedName("vanished_users_whatsapp")
        public List<String> vanishedPlayersWa;
    }

}
