package com.g2806.undertaleextinct;

import net.fabricmc.api.ClientModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Client-side mod initializer for client-specific features
 */
public class UndertaleExtinctClient implements ClientModInitializer {
    public static final Logger LOGGER = LoggerFactory.getLogger("UndertaleExtinctClient");
    private static AnimationPlayer animationPlayer;

    @Override
    public void onInitializeClient() {
        LOGGER.info("Initializing Undertale Extinct Client...");
        
        // Initialize animation player
        animationPlayer = new AnimationPlayer();
        
        LOGGER.info("Undertale Extinct Client initialized successfully!");
    }
    
    /**
     * Get the animation player instance
     */
    public static AnimationPlayer getAnimationPlayer() {
        return animationPlayer;
    }
}