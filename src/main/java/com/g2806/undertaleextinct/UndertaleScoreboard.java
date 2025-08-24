package com.g2806.undertaleextinct;

import net.minecraft.nbt.NbtCompound;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.world.PersistentState;
import net.minecraft.world.PersistentStateManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages attack values and other scores for the Undertale mod
 */
public class UndertaleScoreboard {
    private static final Logger LOGGER = LoggerFactory.getLogger("UndertaleScoreboard");
    private static final String MOD_ID = "undertaleextinct";
    
    // In-memory storage for scores
    private static final Map<UUID, PlayerScores> playerScores = new ConcurrentHashMap<>();
    
    /**
     * Player score data container
     */
    public static class PlayerScores {
        public int lastAttackValue = 0;
        public int bestAttackValue = 0;
        public int totalAttacks = 0;
        public float averageAttackValue = 0.0f;
        public long lastAttackTime = 0;
        public int perfectAttacks = 0; // Attacks with score 45-55
        
        public PlayerScores() {}
        
        public PlayerScores(NbtCompound nbt) {
            lastAttackValue = nbt.getInt("lastAttackValue");
            bestAttackValue = nbt.getInt("bestAttackValue");
            totalAttacks = nbt.getInt("totalAttacks");
            averageAttackValue = nbt.getFloat("averageAttackValue");
            lastAttackTime = nbt.getLong("lastAttackTime");
            perfectAttacks = nbt.getInt("perfectAttacks");
        }
        
        public NbtCompound toNbt() {
            NbtCompound nbt = new NbtCompound();
            nbt.putInt("lastAttackValue", lastAttackValue);
            nbt.putInt("bestAttackValue", bestAttackValue);
            nbt.putInt("totalAttacks", totalAttacks);
            nbt.putFloat("averageAttackValue", averageAttackValue);
            nbt.putLong("lastAttackTime", lastAttackTime);
            nbt.putInt("perfectAttacks", perfectAttacks);
            return nbt;
        }
    }
    
    /**
     * Record a new attack value for a player
     */
    public static void recordAttackValue(UUID playerId, int attackValue) {
        PlayerScores scores = playerScores.computeIfAbsent(playerId, k -> new PlayerScores());
        
        // Update scores
        scores.lastAttackValue = attackValue;
        scores.totalAttacks++;
        scores.lastAttackTime = System.currentTimeMillis();
        
        // Update best score (closest to 50 is best)
        int currentBest = Math.abs(scores.bestAttackValue - 50);
        int newScore = Math.abs(attackValue - 50);
        if (scores.totalAttacks == 1 || newScore < currentBest) {
            scores.bestAttackValue = attackValue;
        }
        
        // Count perfect attacks (45-55 range)
        if (attackValue >= 45 && attackValue <= 55) {
            scores.perfectAttacks++;
        }
        
        // Calculate average
        scores.averageAttackValue = calculateAverage(playerId, attackValue);
        
        LOGGER.info("Recorded attack value {} for player {}. Total attacks: {}, Best: {}, Average: {}", 
                   attackValue, playerId, scores.totalAttacks, scores.bestAttackValue, scores.averageAttackValue);
    }
    
    private static float calculateAverage(UUID playerId, int newValue) {
        PlayerScores scores = playerScores.get(playerId);
        if (scores == null || scores.totalAttacks <= 1) {
            return newValue;
        }
        
        // Calculate running average
        float oldAverage = scores.averageAttackValue;
        int oldCount = scores.totalAttacks - 1; // -1 because we already incremented totalAttacks
        return ((oldAverage * oldCount) + newValue) / scores.totalAttacks;
    }
    
    /**
     * Get player's attack scores
     */
    public static PlayerScores getPlayerScores(UUID playerId) {
        return playerScores.getOrDefault(playerId, new PlayerScores());
    }
    
    /**
     * Get player's last attack value
     */
    public static int getLastAttackValue(UUID playerId) {
        PlayerScores scores = playerScores.get(playerId);
        return scores != null ? scores.lastAttackValue : 0;
    }
    
    /**
     * Get player's best attack value (closest to 50)
     */
    public static int getBestAttackValue(UUID playerId) {
        PlayerScores scores = playerScores.get(playerId);
        return scores != null ? scores.bestAttackValue : 0;
    }
    
    /**
     * Get player's average attack value
     */
    public static float getAverageAttackValue(UUID playerId) {
        PlayerScores scores = playerScores.get(playerId);
        return scores != null ? scores.averageAttackValue : 0.0f;
    }
    
    /**
     * Get total number of attacks by player
     */
    public static int getTotalAttacks(UUID playerId) {
        PlayerScores scores = playerScores.get(playerId);
        return scores != null ? scores.totalAttacks : 0;
    }
    
    /**
     * Get number of perfect attacks by player (45-55 range)
     */
    public static int getPerfectAttacks(UUID playerId) {
        PlayerScores scores = playerScores.get(playerId);
        return scores != null ? scores.perfectAttacks : 0;
    }
    
    /**
     * Reset a player's scores
     */
    public static void resetPlayerScores(UUID playerId) {
        playerScores.remove(playerId);
        LOGGER.info("Reset attack scores for player {}", playerId);
    }
    
    /**
     * Reset all scores
     */
    public static void resetAllScores() {
        playerScores.clear();
        LOGGER.info("Reset all attack scores");
    }
    
    /**
     * Get leaderboard (top players by best attack value)
     */
    public static List<Map.Entry<UUID, PlayerScores>> getLeaderboard(int limit) {
        return playerScores.entrySet().stream()
                .filter(entry -> entry.getValue().totalAttacks > 0)
                .sorted((a, b) -> {
                    // Sort by closest to 50 (best attack value)
                    int scoreA = Math.abs(a.getValue().bestAttackValue - 50);
                    int scoreB = Math.abs(b.getValue().bestAttackValue - 50);
                    return Integer.compare(scoreA, scoreB);
                })
                .limit(limit)
                .toList();
    }
    
    // Persistent data storage
    public static class ScoreboardData extends PersistentState {
        private final Map<UUID, PlayerScores> storedScores = new HashMap<>();
        
        public static ScoreboardData fromNbt(NbtCompound nbt) {
            ScoreboardData data = new ScoreboardData();
            
            if (nbt.contains("playerScores")) {
                NbtCompound scoresNbt = nbt.getCompound("playerScores");
                for (String uuidStr : scoresNbt.getKeys()) {
                    try {
                        UUID playerId = UUID.fromString(uuidStr);
                        PlayerScores scores = new PlayerScores(scoresNbt.getCompound(uuidStr));
                        data.storedScores.put(playerId, scores);
                    } catch (IllegalArgumentException e) {
                        LOGGER.warn("Invalid UUID in scoreboard data: {}", uuidStr);
                    }
                }
            }
            
            return data;
        }
        
        @Override
        public NbtCompound writeNbt(NbtCompound nbt) {
            NbtCompound scoresNbt = new NbtCompound();
            
            for (Map.Entry<UUID, PlayerScores> entry : storedScores.entrySet()) {
                scoresNbt.put(entry.getKey().toString(), entry.getValue().toNbt());
            }
            
            nbt.put("playerScores", scoresNbt);
            return nbt;
        }
        
        public void updateScores(Map<UUID, PlayerScores> currentScores) {
            storedScores.clear();
            storedScores.putAll(currentScores);
            markDirty();
        }
        
        public Map<UUID, PlayerScores> getStoredScores() {
            return new HashMap<>(storedScores);
        }
    }
    
    /**
     * Save scores to persistent storage
     */
    public static void saveScores(MinecraftServer server) {
        if (server == null) return;
        
        try {
            PersistentStateManager stateManager = server.getOverworld().getPersistentStateManager();
            ScoreboardData data = stateManager.getOrCreate(
                    ScoreboardData::fromNbt,
                    ScoreboardData::new,
                    MOD_ID + "_scoreboard"
            );
            
            data.updateScores(playerScores);
            LOGGER.info("Saved attack scoreboard with {} players", playerScores.size());
            
        } catch (Exception e) {
            LOGGER.error("Failed to save attack scoreboard", e);
        }
    }
    
    /**
     * Load scores from persistent storage
     */
    public static void loadScores(MinecraftServer server) {
        if (server == null) return;
        
        try {
            PersistentStateManager stateManager = server.getOverworld().getPersistentStateManager();
            ScoreboardData data = stateManager.getOrCreate(
                    ScoreboardData::fromNbt,
                    ScoreboardData::new,
                    MOD_ID + "_scoreboard"
            );
            
            playerScores.clear();
            playerScores.putAll(data.getStoredScores());
            
            LOGGER.info("Loaded attack scoreboard with {} players", playerScores.size());
            
        } catch (Exception e) {
            LOGGER.error("Failed to load attack scoreboard", e);
        }
    }
    
    /**
     * Get all player scores (for commands/admin use)
     */
    public static Map<UUID, PlayerScores> getAllScores() {
        return new HashMap<>(playerScores);
    }
}