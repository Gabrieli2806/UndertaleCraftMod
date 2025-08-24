package com.g2806.undertaleextinct;

import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Main mod initializer that coordinates all mod components
 */
public class UndertaleExtinctMod implements ModInitializer {
    public static final String MOD_ID = "undertaleextinct";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitialize() {
        LOGGER.info("Initializing Undertale Extinct Mod...");

        // Initialize the extinction system
        UndertaleExtinct extinctionSystem = new UndertaleExtinct();
        extinctionSystem.onInitialize();

        LOGGER.info("Undertale Extinct Mod initialized successfully!");
    }
}