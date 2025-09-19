package com.g2806.undertaleextinct;

import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.PacketByteBuf;
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
    public static final Identifier GUN_ATTACK_VALUE_PACKET = new Identifier(MOD_ID, "gun_attack_value");
    public static final Identifier NUMBERED_ATTACK_VALUE_PACKET = new Identifier(MOD_ID, "numbered_attack_value");
    public static final Identifier NUMBERED_GUN_ATTACK_VALUE_PACKET = new Identifier(MOD_ID, "numbered_gun_attack_value");
    public static final Identifier START_ANIMATION_PACKET = new Identifier(MOD_ID, "start_animation");
    public static final Identifier START_ATTACK_PACKET = new Identifier(MOD_ID, "start_attack");
    public static final Identifier START_GUN_ATTACK_PACKET = new Identifier(MOD_ID, "start_gun_attack");
    public static final Identifier START_NUMBERED_ATTACK_PACKET = new Identifier(MOD_ID, "start_numbered_attack");
    public static final Identifier START_NUMBERED_GUN_ATTACK_PACKET = new Identifier(MOD_ID, "start_numbered_gun_attack");
    
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
        
        ServerPlayNetworking.registerGlobalReceiver(GUN_ATTACK_VALUE_PACKET, (server, player, handler, buf, responseSender) -> {
            // Read gun attack value from packet
            int gunAttackValue = buf.readInt();
            
            // Execute on server thread
            server.execute(() -> {
                handleGunAttackValueReceived(player, gunAttackValue);
            });
        });
        
        // Handle numbered attack values
        ServerPlayNetworking.registerGlobalReceiver(NUMBERED_ATTACK_VALUE_PACKET, (server, player, handler, buf, responseSender) -> {
            int attackValue = buf.readInt();
            int attackNumber = buf.readInt();
            
            server.execute(() -> {
                handleNumberedAttackValueReceived(player, attackValue, attackNumber);
            });
        });
        
        ServerPlayNetworking.registerGlobalReceiver(NUMBERED_GUN_ATTACK_VALUE_PACKET, (server, player, handler, buf, responseSender) -> {
            int gunAttackValue = buf.readInt();
            int attackNumber = buf.readInt();
            
            server.execute(() -> {
                handleNumberedGunAttackValueReceived(player, gunAttackValue, attackNumber);
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
    
    /**
     * Send gun attack start packet to specific player
     */
    public static void sendStartGunAttackToPlayer(ServerPlayerEntity targetPlayer) {
        try {
            ServerPlayNetworking.send(targetPlayer, START_GUN_ATTACK_PACKET, PacketByteBufs.empty());
            LOGGER.info("Sent start gun attack packet to player: {}", targetPlayer.getName().getString());
        } catch (Exception e) {
            LOGGER.error("Failed to send start gun attack packet to player: {}", targetPlayer.getName().getString(), e);
        }
    }
    
    /**
     * Send numbered attack start packet to specific player
     */
    public static void sendStartNumberedAttackToPlayer(ServerPlayerEntity targetPlayer, int attackNumber) {
        try {
            PacketByteBuf buf = PacketByteBufs.create();
            buf.writeInt(attackNumber);
            ServerPlayNetworking.send(targetPlayer, START_NUMBERED_ATTACK_PACKET, buf);
            LOGGER.info("Sent start numbered attack {} packet to player: {}", attackNumber, targetPlayer.getName().getString());
        } catch (Exception e) {
            LOGGER.error("Failed to send start numbered attack packet to player: {}", targetPlayer.getName().getString(), e);
        }
    }
    
    /**
     * Send numbered gun attack start packet to specific player
     */
    public static void sendStartNumberedGunAttackToPlayer(ServerPlayerEntity targetPlayer, int attackNumber) {
        try {
            PacketByteBuf buf = PacketByteBufs.create();
            buf.writeInt(attackNumber);
            ServerPlayNetworking.send(targetPlayer, START_NUMBERED_GUN_ATTACK_PACKET, buf);
            LOGGER.info("Sent start numbered gun attack {} packet to player: {}", attackNumber, targetPlayer.getName().getString());
        } catch (Exception e) {
            LOGGER.error("Failed to send start numbered gun attack packet to player: {}", targetPlayer.getName().getString(), e);
        }
    }
    
    private static void handleAttackValueReceived(ServerPlayerEntity player, int attackValue) {
        // Save to vanilla scoreboard
        saveToVanillaScoreboard(player, attackValue);
        
        // Send feedback to player about their score (only if chat messages are enabled)
        if (ModConfig.getInstance().isChatMessagesEnabled()) {
            String message = getAttackMessage(attackValue);
            player.sendMessage(Text.literal(message), false);
        }
        
        LOGGER.info("Player {} achieved attack value: {} - Saved to vanilla scoreboard", player.getName().getString(), attackValue);
    }
    
    private static void handleGunAttackValueReceived(ServerPlayerEntity player, int gunAttackValue) {
        // Save to vanilla scoreboard
        saveGunAttackToVanillaScoreboard(player, gunAttackValue);
        
        // Send feedback to player about their score (only if chat messages are enabled)
        if (ModConfig.getInstance().isChatMessagesEnabled()) {
            String message = getGunAttackMessage(gunAttackValue);
            player.sendMessage(Text.literal(message), false);
        }
        
        LOGGER.info("Player {} achieved gun attack value: {} - Saved to vanilla scoreboard", player.getName().getString(), gunAttackValue);
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
            
            // Set the player's score - only save the value, no additional tracking
            scoreboard.getPlayerScore(player.getName().getString(), objective).setScore(attackValue);
            LOGGER.info("Set vanilla scoreboard score for {}: attack_value = {}", player.getName().getString(), attackValue);
            
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
    
    private static void saveGunAttackToVanillaScoreboard(ServerPlayerEntity player, int gunAttackValue) {
        try {
            Scoreboard scoreboard = player.getServer().getScoreboard();
            
            // Create or get the gun_attack_value objective
            ScoreboardObjective objective = scoreboard.getNullableObjective("gun_attack_value");
            if (objective == null) {
                objective = scoreboard.addObjective(
                    "gun_attack_value", 
                    ScoreboardCriterion.DUMMY, 
                    Text.literal("Gun Attack Value"), 
                    ScoreboardCriterion.RenderType.INTEGER
                );
                LOGGER.info("Created vanilla scoreboard objective: gun_attack_value");
            }
            
            // Set the player's score - only save the value, no additional tracking
            scoreboard.getPlayerScore(player.getName().getString(), objective).setScore(gunAttackValue);
            LOGGER.info("Set vanilla scoreboard score for {}: gun_attack_value = {}", player.getName().getString(), gunAttackValue);
            
        } catch (Exception e) {
            LOGGER.error("Failed to save gun attack value to vanilla scoreboard for player {}: {}", player.getName().getString(), e.getMessage());
        }
    }
    
    private static void createGunAdditionalObjectives(ServerPlayerEntity player, int gunAttackValue, Scoreboard scoreboard) {
        String playerName = player.getName().getString();
        
        // Best gun attack objective (highest score)
        ScoreboardObjective bestObj = scoreboard.getNullableObjective("gun_attack_best");
        if (bestObj == null) {
            bestObj = scoreboard.addObjective(
                "gun_attack_best", 
                ScoreboardCriterion.DUMMY, 
                Text.literal("Best Gun Attack"), 
                ScoreboardCriterion.RenderType.INTEGER
            );
        }
        
        // Update best score if this is better
        int currentBest = scoreboard.getPlayerScore(playerName, bestObj).getScore();
        if (gunAttackValue > currentBest) {
            scoreboard.getPlayerScore(playerName, bestObj).setScore(gunAttackValue);
        }
        
        // Total gun attacks counter
        ScoreboardObjective totalObj = scoreboard.getNullableObjective("gun_attack_total");
        if (totalObj == null) {
            totalObj = scoreboard.addObjective(
                "gun_attack_total", 
                ScoreboardCriterion.DUMMY, 
                Text.literal("Total Gun Attacks"), 
                ScoreboardCriterion.RenderType.INTEGER
            );
        }
        
        // Increment total gun attacks
        int currentTotal = scoreboard.getPlayerScore(playerName, totalObj).getScore();
        scoreboard.getPlayerScore(playerName, totalObj).setScore(currentTotal + 1);
        
        // Perfect gun attacks counter (360+ score range - 90% of max 400)
        if (gunAttackValue >= 360) {
            ScoreboardObjective perfectObj = scoreboard.getNullableObjective("gun_attack_perfect");
            if (perfectObj == null) {
                perfectObj = scoreboard.addObjective(
                    "gun_attack_perfect", 
                    ScoreboardCriterion.DUMMY, 
                    Text.literal("Perfect Gun Attacks"), 
                    ScoreboardCriterion.RenderType.INTEGER
                );
            }
            
            // Increment perfect gun attacks
            int currentPerfect = scoreboard.getPlayerScore(playerName, perfectObj).getScore();
            scoreboard.getPlayerScore(playerName, perfectObj).setScore(currentPerfect + 1);
        }
    }
    
    private static void handleNumberedAttackValueReceived(ServerPlayerEntity player, int attackValue, int attackNumber) {
        // Save to numbered vanilla scoreboard
        saveNumberedAttackToVanillaScoreboard(player, attackValue, attackNumber);
        
        // Send feedback to player about their score (only if chat messages are enabled)
        if (ModConfig.getInstance().isChatMessagesEnabled()) {
            String message = getAttackMessage(attackValue) + " [Attack " + attackNumber + "]";
            player.sendMessage(Text.literal(message), false);
        }
        
        LOGGER.info("Player {} achieved numbered attack {} value: {} - Saved to vanilla scoreboard", 
            player.getName().getString(), attackNumber, attackValue);
    }
    
    private static void handleNumberedGunAttackValueReceived(ServerPlayerEntity player, int gunAttackValue, int attackNumber) {
        // Save to numbered vanilla scoreboard
        saveNumberedGunAttackToVanillaScoreboard(player, gunAttackValue, attackNumber);
        
        // Send feedback to player about their score (only if chat messages are enabled)
        if (ModConfig.getInstance().isChatMessagesEnabled()) {
            String message = getGunAttackMessage(gunAttackValue) + " [Gun Attack " + attackNumber + "]";
            player.sendMessage(Text.literal(message), false);
        }
        
        LOGGER.info("Player {} achieved numbered gun attack {} value: {} - Saved to vanilla scoreboard", 
            player.getName().getString(), attackNumber, gunAttackValue);
    }
    
    private static void saveNumberedAttackToVanillaScoreboard(ServerPlayerEntity player, int attackValue, int attackNumber) {
        try {
            Scoreboard scoreboard = player.getServer().getScoreboard();
            
            // Create or get the numbered attack objective (attack1, attack2, etc.)
            String objectiveName = "attack" + attackNumber;
            ScoreboardObjective objective = scoreboard.getNullableObjective(objectiveName);
            if (objective == null) {
                objective = scoreboard.addObjective(
                    objectiveName, 
                    ScoreboardCriterion.DUMMY, 
                    Text.literal("Attack " + attackNumber), 
                    ScoreboardCriterion.RenderType.INTEGER
                );
                LOGGER.info("Created vanilla scoreboard objective: {}", objectiveName);
            }
            
            // Set the player's score - only save the value
            scoreboard.getPlayerScore(player.getName().getString(), objective).setScore(attackValue);
            LOGGER.info("Set vanilla scoreboard score for {}: {} = {}", player.getName().getString(), objectiveName, attackValue);
            
        } catch (Exception e) {
            LOGGER.error("Failed to save numbered attack value to vanilla scoreboard", e);
        }
    }
    
    private static void saveNumberedGunAttackToVanillaScoreboard(ServerPlayerEntity player, int gunAttackValue, int attackNumber) {
        try {
            Scoreboard scoreboard = player.getServer().getScoreboard();
            
            // Create or get the numbered gun attack objective (gunattack1, gunattack2, etc.)
            String objectiveName = "gunattack" + attackNumber;
            ScoreboardObjective objective = scoreboard.getNullableObjective(objectiveName);
            if (objective == null) {
                objective = scoreboard.addObjective(
                    objectiveName, 
                    ScoreboardCriterion.DUMMY, 
                    Text.literal("Gun Attack " + attackNumber), 
                    ScoreboardCriterion.RenderType.INTEGER
                );
                LOGGER.info("Created vanilla scoreboard objective: {}", objectiveName);
            }
            
            // Set the player's score - only save the value
            scoreboard.getPlayerScore(player.getName().getString(), objective).setScore(gunAttackValue);
            LOGGER.info("Set vanilla scoreboard score for {}: {} = {}", player.getName().getString(), objectiveName, gunAttackValue);
            
        } catch (Exception e) {
            LOGGER.error("Failed to save numbered gun attack value to vanilla scoreboard", e);
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
    
    private static String getGunAttackMessage(int gunAttackValue) {
        if (gunAttackValue >= 360) {
            return "§a✦ PERFECT GUN ATTACK! §f(" + gunAttackValue + "/400) §7- Saved to scoreboard!";
        } else if (gunAttackValue >= 280) {
            return "§b◆ Great Gun Attack! §f(" + gunAttackValue + "/400) §7- Saved to scoreboard!";
        } else if (gunAttackValue >= 200) {
            return "§e⚔ Good Gun Attack! §f(" + gunAttackValue + "/400) §7- Saved to scoreboard!";
        } else if (gunAttackValue >= 100) {
            return "§6◊ Okay Gun Attack §f(" + gunAttackValue + "/400) §7- Saved to scoreboard!";
        } else {
            return "§c✗ Gun Attack Missed... §f(" + gunAttackValue + "/400) §7- Saved to scoreboard!";
        }
    }
}