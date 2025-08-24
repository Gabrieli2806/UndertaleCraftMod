package com.g2806.undertaleextinct;

import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Client-side handler for Undertale attack GUI
 */
public class UndertaleAttackHandler {
    private static final Logger LOGGER = LoggerFactory.getLogger("UndertaleAttackHandler");
    
    public static void initialize() {
        // Note: Client-side command removed to avoid conflicts with server-side command
        // The server-side /undertaleattack command now handles all functionality
        // Click detection is handled individually by each overlay's tick events
        LOGGER.info("Attack handler initialized - using server-side commands only");
    }
    
    /**
     * Toggle the attack overlay directly
     */
    public static void toggleAttackOverlay() {
        UndertaleAttackOverlay overlay = UndertaleExtinctClient.getAttackOverlay();
        if (overlay != null) {
            overlay.toggle();
            LOGGER.info("Toggled Undertale attack overlay");
        } else {
            LOGGER.warn("Attack overlay not available");
        }
    }
    
    /**
     * Toggle the gun attack overlay directly
     */
    public static void toggleGunAttackOverlay() {
        UndertaleAttackGunOverlay overlay = UndertaleExtinctClient.getGunAttackOverlay();
        if (overlay != null) {
            if (overlay.isActive()) {
                overlay.stopGunAttack();
            } else {
                overlay.startGunAttack();
            }
            LOGGER.info("Toggled Undertale gun attack overlay");
        } else {
            LOGGER.warn("Gun attack overlay not available");
        }
    }
}