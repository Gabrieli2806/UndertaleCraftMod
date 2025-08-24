package com.g2806.undertaleextinct;

import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.scoreboard.Scoreboard;
import net.minecraft.scoreboard.ScoreboardObjective;
import net.minecraft.scoreboard.ScoreboardCriterion;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Handles networking between client and server for attack values
 */
public class UndertaleNetworking {
    private static final Logger LOGGER = LoggerFactory.getLogger("UndertaleNetworking");
    private static final String MOD_ID = "undertaleextinct";
    
    // Packet identifiers
    public static final Identifier ATTACK_VALUE_PACKET = new Identifier(MOD_ID, "attack_value");
    public static final Identifier START_ANIMATION_PACKET = new Identifier(MOD_ID, "start_animation");
    public static final Identifier START_ATTACK_PACKET = new Identifier(MOD_ID, "start_attack");
    
    /**
     * Register server-side packet handlers
     */
    public static void registerServerPackets() {
        ServerPlayNetworking.registerGlobalReceiver(ATTACK_VALUE_PACKET, (server, player, handler, buf, responseSender) -> {
            // Read attack value from packet
            int attackValue = buf.readInt();
            
            // Execute on server thread
            server.execute(() -> {
                handleAttackValueReceived(player, attackValue);
            });
        });
        
        LOGGER.info("Registered server-side networking packets");
    }
    
    /**
     * Send animation start packet to specific player
     */
    public static void sendStartAnimationToPlayer(ServerPlayerEntity targetPlayer) {
        try {
            ServerPlayNetworking.send(targetPlayer, START_ANIMATION_PACKET, PacketByteBufs.empty());
            LOGGER.info("Sent start animation packet to player: {}", targetPlayer.getName().getString());
        } catch (Exception e) {
            LOGGER.error("Failed to send start animation packet to player: {}", targetPlayer.getName().getString(), e);
        }
    }
    
    /**
     * Send attack start packet to specific player
     */
    public static void sendStartAttackToPlayer(ServerPlayerEntity targetPlayer) {
        try {
            ServerPlayNetworking.send(targetPlayer, START_ATTACK_PACKET, PacketByteBufs.empty());
            LOGGER.info("Sent start attack packet to player: {}", targetPlayer.getName().getString());
        } catch (Exception e) {
            LOGGER.error("Failed to send start attack packet to player: {}", targetPlayer.getName().getString(), e);
        }
    }
    
    private static void handleAttackValueReceived(ServerPlayerEntity player, int attackValue) {
        // Save to vanilla scoreboard
        saveToVanillaScoreboard(player, attackValue);
        
        // Send feedback to player about their score
        String message = getAttackMessage(attackValue);
        player.sendMessage(Text.literal(message), false);
        
        LOGGER.info("Player {} achieved attack value: {} - Saved to vanilla scoreboard", player.getName().getString(), attackValue);
    }
    
    private static void saveToVanillaScoreboard(ServerPlayerEntity player, int attackValue) {
        try {
            Scoreboard scoreboard = player.getServer().getScoreboard();
            
            // Create or get the attack_value objective
            ScoreboardObjective objective = scoreboard.getNullableObjective("attack_value");
            if (objective == null) {
                objective = scoreboard.addObjective(
                    "attack_value", 
                    ScoreboardCriterion.DUMMY, 
                    Text.literal("Attack Value"), 
                    ScoreboardCriterion.RenderType.INTEGER
                );
                LOGGER.info("Created vanilla scoreboard objective: attack_value");
            }
            
            // Set the player's score
            scoreboard.getPlayerScore(player.getName().getString(), objective).setScore(attackValue);
            LOGGER.info("Set vanilla scoreboard score for {}: attack_value = {}", player.getName().getString(), attackValue);
            
            // Also create additional objectives for tracking
            createAdditionalObjectives(player, attackValue, scoreboard);
            
        } catch (Exception e) {
            LOGGER.error("Failed to save attack value to vanilla scoreboard", e);
        }
    }
    
    private static void createAdditionalObjectives(ServerPlayerEntity player, int attackValue, Scoreboard scoreboard) {
        String playerName = player.getName().getString();
        
        // Best attack value (closest to 50)
        ScoreboardObjective bestObj = scoreboard.getNullableObjective("attack_best");
        if (bestObj == null) {
            bestObj = scoreboard.addObjective(
                "attack_best", 
                ScoreboardCriterion.DUMMY, 
                Text.literal("Best Attack"), 
                ScoreboardCriterion.RenderType.INTEGER
            );
        }
        
        // Update best score if this is higher (higher score is better now)
        int currentBest = scoreboard.getPlayerScore(playerName, bestObj).getScore();
        
        if (attackValue > currentBest) {
            scoreboard.getPlayerScore(playerName, bestObj).setScore(attackValue);
        }
        
        // Total attacks counter
        ScoreboardObjective totalObj = scoreboard.getNullableObjective("attack_total");
        if (totalObj == null) {
            totalObj = scoreboard.addObjective(
                "attack_total", 
                ScoreboardCriterion.DUMMY, 
                Text.literal("Total Attacks"), 
                ScoreboardCriterion.RenderType.INTEGER
            );
        }
        
        // Increment total attacks
        int currentTotal = scoreboard.getPlayerScore(playerName, totalObj).getScore();
        scoreboard.getPlayerScore(playerName, totalObj).setScore(currentTotal + 1);
        
        // Perfect attacks counter (90+ score range)
        if (attackValue >= 90) {
            ScoreboardObjective perfectObj = scoreboard.getNullableObjective("attack_perfect");
            if (perfectObj == null) {
                perfectObj = scoreboard.addObjective(
                    "attack_perfect", 
                    ScoreboardCriterion.DUMMY, 
                    Text.literal("Perfect Attacks"), 
                    ScoreboardCriterion.RenderType.INTEGER
                );
            }
            
            // Increment perfect attacks
            int currentPerfect = scoreboard.getPlayerScore(playerName, perfectObj).getScore();
            scoreboard.getPlayerScore(playerName, perfectObj).setScore(currentPerfect + 1);
        }
    }
    
    private static String getAttackMessage(int attackValue) {
        if (attackValue >= 90) {
            return "§a✦ PERFECT ATTACK! §f(" + attackValue + "/100) §7- Saved to scoreboard!";
        } else if (attackValue >= 70) {
            return "§b◆ Great Attack! §f(" + attackValue + "/100) §7- Saved to scoreboard!";
        } else if (attackValue >= 50) {
            return "§e⚔ Good Attack! §f(" + attackValue + "/100) §7- Saved to scoreboard!";
        } else if (attackValue >= 25) {
            return "§6◊ Okay Attack §f(" + attackValue + "/100) §7- Saved to scoreboard!";
        } else {
            return "§c✗ Missed... §f(" + attackValue + "/100) §7- Saved to scoreboard!";
        }
    }
}