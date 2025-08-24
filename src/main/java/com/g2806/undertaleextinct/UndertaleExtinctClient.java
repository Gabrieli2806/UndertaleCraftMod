package com.g2806.undertaleextinct;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Client-side mod initializer for client-specific features
 */
public class UndertaleExtinctClient implements ClientModInitializer {
    public static final Logger LOGGER = LoggerFactory.getLogger("UndertaleExtinctClient");
    private static AnimationPlayer animationPlayer;
    private static UndertaleAttackOverlay attackOverlay;

    @Override
    public void onInitializeClient() {
        LOGGER.info("Initializing Undertale Extinct Client...");
        
        // Initialize animation player
        animationPlayer = new AnimationPlayer();
        
        // Initialize attack overlay
        attackOverlay = new UndertaleAttackOverlay();
        
        // Initialize attack handler
        UndertaleAttackHandler.initialize();
        
        // Register client-side packet handlers
        registerClientPackets();
        
        LOGGER.info("Undertale Extinct Client initialized successfully!");
    }
    
    private void registerClientPackets() {
        // Handle animation start packet
        ClientPlayNetworking.registerGlobalReceiver(UndertaleNetworking.START_ANIMATION_PACKET, (client, handler, buf, responseSender) -> {
            client.execute(() -> {
                if (animationPlayer != null) {
                    animationPlayer.startAnimation();
                    LOGGER.info("Started animation from server command");
                }
            });
        });
        
        // Handle attack start packet
        ClientPlayNetworking.registerGlobalReceiver(UndertaleNetworking.START_ATTACK_PACKET, (client, handler, buf, responseSender) -> {
            client.execute(() -> {
                if (attackOverlay != null) {
                    attackOverlay.startAttack();
                    LOGGER.info("Started attack overlay from server command");
                }
            });
        });
        
        LOGGER.info("Registered client-side packet handlers for targeted commands");
    }
    
    /**
     * Get the animation player instance
     */
    public static AnimationPlayer getAnimationPlayer() {
        return animationPlayer;
    }
    
    /**
     * Get the attack overlay instance
     */
    public static UndertaleAttackOverlay getAttackOverlay() {
        return attackOverlay;
    }
}