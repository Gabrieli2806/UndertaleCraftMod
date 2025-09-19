package com.g2806.undertaleextinct;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public class ModConfig {
    private static final Logger LOGGER = LoggerFactory.getLogger("ModConfig");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String CONFIG_FILE_NAME = "undertale_extinct_config.json";

    private static ModConfig instance;

    // Configuration options
    public boolean enableChatMessages = true;
    public double attackBarSpeed = 0.03; // Current default speed
    public boolean showExtinctionNotifications = true;
    public boolean showSaveWorldNotifications = true;
    public boolean showPurgeNotifications = true;

    private ModConfig() {}

    public static ModConfig getInstance() {
        if (instance == null) {
            instance = load();
        }
        return instance;
    }

    private static ModConfig load() {
        Path configPath = getConfigPath();

        if (Files.exists(configPath)) {
            try {
                String json = Files.readString(configPath);
                ModConfig config = GSON.fromJson(json, ModConfig.class);
                LOGGER.info("Loaded mod configuration from {}", configPath);
                return config;
            } catch (Exception e) {
                LOGGER.warn("Failed to load config file, using defaults: {}", e.getMessage());
            }
        }

        // Create default config
        ModConfig defaultConfig = new ModConfig();
        defaultConfig.save();
        return defaultConfig;
    }

    public void save() {
        try {
            Path configPath = getConfigPath();
            Files.createDirectories(configPath.getParent());
            String json = GSON.toJson(this);
            Files.writeString(configPath, json);
            LOGGER.info("Saved mod configuration to {}", configPath);
        } catch (IOException e) {
            LOGGER.error("Failed to save config file: {}", e.getMessage());
        }
    }

    private static Path getConfigPath() {
        return FabricLoader.getInstance().getConfigDir().resolve(CONFIG_FILE_NAME);
    }

    // Getters and setters with validation
    public boolean isChatMessagesEnabled() {
        return enableChatMessages;
    }

    public void setChatMessagesEnabled(boolean enabled) {
        this.enableChatMessages = enabled;
        save();
    }

    public double getAttackBarSpeed() {
        return attackBarSpeed;
    }

    public void setAttackBarSpeed(double speed) {
        // Clamp speed between 0.01 and 0.1 for reasonable gameplay
        this.attackBarSpeed = Math.max(0.01, Math.min(0.1, speed));
        save();
    }

    public boolean isExtinctionNotificationsEnabled() {
        return showExtinctionNotifications;
    }

    public void setExtinctionNotificationsEnabled(boolean enabled) {
        this.showExtinctionNotifications = enabled;
        save();
    }

    public boolean isSaveWorldNotificationsEnabled() {
        return showSaveWorldNotifications;
    }

    public void setSaveWorldNotificationsEnabled(boolean enabled) {
        this.showSaveWorldNotifications = enabled;
        save();
    }

    public boolean isPurgeNotificationsEnabled() {
        return showPurgeNotifications;
    }

    public void setPurgeNotificationsEnabled(boolean enabled) {
        this.showPurgeNotifications = enabled;
        save();
    }

    // Helper method to get attack bar speed as float for use in game code
    public float getAttackBarSpeedFloat() {
        return (float) attackBarSpeed;
    }
}