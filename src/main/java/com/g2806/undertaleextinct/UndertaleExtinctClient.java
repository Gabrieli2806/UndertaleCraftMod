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
    private static UndertaleAttackGunOverlay gunAttackOverlay;

    @Override
    public void onInitializeClient() {
        LOGGER.info("Initializing Undertale Extinct Client...");
        
        // Initialize animation player
        animationPlayer = new AnimationPlayer();
        
        // Initialize attack overlay
        attackOverlay = new UndertaleAttackOverlay();
        
        // Initialize gun attack overlay
        gunAttackOverlay = new UndertaleAttackGunOverlay();
        
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
        
        // Handle gun attack start packet
        ClientPlayNetworking.registerGlobalReceiver(UndertaleNetworking.START_GUN_ATTACK_PACKET, (client, handler, buf, responseSender) -> {
            client.execute(() -> {
                if (gunAttackOverlay != null) {
                    gunAttackOverlay.startGunAttack();
                    LOGGER.info("Started gun attack overlay from server command");
                }
            });
        });
        
        // Handle numbered attack start packets
        ClientPlayNetworking.registerGlobalReceiver(UndertaleNetworking.START_NUMBERED_ATTACK_PACKET, (client, handler, buf, responseSender) -> {
            int attackNumber = buf.readInt();
            client.execute(() -> {
                if (attackOverlay != null) {
                    attackOverlay.startNumberedAttack(attackNumber);
                    LOGGER.info("Started numbered attack overlay {} from server command", attackNumber);
                }
            });
        });
        
        ClientPlayNetworking.registerGlobalReceiver(UndertaleNetworking.START_NUMBERED_GUN_ATTACK_PACKET, (client, handler, buf, responseSender) -> {
            int attackNumber = buf.readInt();
            client.execute(() -> {
                if (gunAttackOverlay != null) {
                    gunAttackOverlay.startNumberedGunAttack(attackNumber);
                    LOGGER.info("Started numbered gun attack overlay {} from server command", attackNumber);
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
    
    /**
     * Get the gun attack overlay instance
     */
    public static UndertaleAttackGunOverlay getGunAttackOverlay() {
        return gunAttackOverlay;
    }
}