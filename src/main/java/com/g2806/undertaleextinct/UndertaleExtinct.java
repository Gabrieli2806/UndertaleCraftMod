package com.g2806.undertaleextinct;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.command.argument.RegistryEntryArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.registry.Registries;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Identifier;
import net.minecraft.world.PersistentState;
import net.minecraft.world.PersistentStateManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class UndertaleExtinct implements ModInitializer {
    public static final String MOD_ID = "undertaleextinct";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    // Configuration
    private static int EXTINCTION_THRESHOLD = 500;

    // State management
    private static boolean isPurgeActive = false;
    private static final Set<Identifier> purgedMobs = ConcurrentHashMap.newKeySet();
    private static final Set<Identifier> extinctMobs = ConcurrentHashMap.newKeySet();
    private static final Map<Identifier, Integer> killCounts = new ConcurrentHashMap<>();
    private static final Map<UUID, Identifier> nextKillTargets = new ConcurrentHashMap<>();
    
    // Animation player instance
    private static AnimationPlayer animationPlayer;

    @Override
    public void onInitialize() {
        LOGGER.info("Undertale Extinct mod initialized!");
        
        // Initialize animation player
        animationPlayer = new AnimationPlayer();

        registerCommands();
        registerEvents();
        registerServerEvents();
    }

    private void registerCommands() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            // Command to disable next killed mob
            dispatcher.register(CommandManager.literal("disablenext")
                    .requires(source -> source.hasPermissionLevel(2))
                    .executes(context -> {
                        ServerCommandSource source = context.getSource();
                        if (source.getEntity() instanceof ServerPlayerEntity player) {
                            nextKillTargets.put(player.getUuid(), null); // Mark for next kill
                            source.sendFeedback(() -> Text.literal("§6Next mob you kill will be disabled from spawning!"), false);
                            return 1;
                        }
                        return 0;
                    }));

            // Command to instantly eliminate all of a specific mob type (admin command)
            dispatcher.register(CommandManager.literal("exterminate")
                    .requires(source -> source.hasPermissionLevel(2))
                    .then(CommandManager.argument("mobtype", RegistryEntryArgumentType.registryEntry(registryAccess, RegistryKeys.ENTITY_TYPE))
                            .executes(context -> {
                                EntityType<?> entityType = RegistryEntryArgumentType.getRegistryEntry(context, "mobtype", RegistryKeys.ENTITY_TYPE).value();
                                Identifier mobId = Registries.ENTITY_TYPE.getId(entityType);

                                // Add to purged list
                                purgedMobs.add(mobId);
                                extinctMobs.add(mobId);

                                // Kill all existing mobs of this type
                                MinecraftServer server = context.getSource().getServer();
                                int killedCount = 0;
                                for (ServerWorld world : server.getWorlds()) {
                                    for (Object entityObj : world.getEntitiesByType(entityType, livingEntity -> livingEntity.isAlive())) {
                                        if (entityObj instanceof MobEntity mobEntity) {
                                            mobEntity.damage(world.getDamageSources().genericKill(), Float.MAX_VALUE);
                                            killedCount++;
                                        }
                                    }
                                }

                                final int finalKilledCount = killedCount;
                                final String mobName = entityType.getName().getString();
                                context.getSource().sendFeedback(() ->
                                        Text.literal("§4💀 EXTERMINATED " + finalKilledCount + " " + mobName +
                                                " and marked them as extinct!"), false);
                                return 1;
                            })));
            dispatcher.register(CommandManager.literal("removecurse")
                    .requires(source -> source.hasPermissionLevel(2))
                    .then(CommandManager.argument("mobtype", RegistryEntryArgumentType.registryEntry(registryAccess, RegistryKeys.ENTITY_TYPE))
                            .executes(context -> {
                                EntityType<?> entityType = RegistryEntryArgumentType.getRegistryEntry(context, "mobtype", RegistryKeys.ENTITY_TYPE).value();
                                Identifier mobId = Registries.ENTITY_TYPE.getId(entityType);

                                boolean wasRemoved = false;
                                if (purgedMobs.remove(mobId)) {
                                    wasRemoved = true;
                                }
                                if (extinctMobs.remove(mobId)) {
                                    wasRemoved = true;
                                }
                                killCounts.remove(mobId);

                                if (wasRemoved) {
                                    context.getSource().sendFeedback(() ->
                                            Text.literal("§aCurse removed from " + entityType.getName().getString() + "! They can spawn again."), false);
                                } else {
                                    context.getSource().sendFeedback(() ->
                                            Text.literal("§c" + entityType.getName().getString() + " was not cursed."), false);
                                }
                                return 1;
                            })));

            // Command to remove all curses
            dispatcher.register(CommandManager.literal("removeallcurses")
                    .requires(source -> source.hasPermissionLevel(2))
                    .executes(context -> {
                        int total = purgedMobs.size() + extinctMobs.size();
                        purgedMobs.clear();
                        extinctMobs.clear();
                        killCounts.clear();
                        nextKillTargets.clear();

                        context.getSource().sendFeedback(() ->
                                Text.literal("§aAll curses removed! " + total + " mob types can spawn again."), false);
                        return 1;
                    }));

            // Purge system commands
            dispatcher.register(CommandManager.literal("undertalepurge")
                    .requires(source -> source.hasPermissionLevel(2))
                    .executes(context -> {
                        isPurgeActive = true;
                        context.getSource().sendFeedback(() ->
                                Text.literal("§4UNDERTALE PURGE ACTIVATED! Kill counting has begun..."), false);
                        return 1;
                    }));

            dispatcher.register(CommandManager.literal("undertalepurgestop")
                    .requires(source -> source.hasPermissionLevel(2))
                    .executes(context -> {
                        isPurgeActive = false;
                        context.getSource().sendFeedback(() ->
                                Text.literal("§6Undertale purge stopped. Kill counting paused."), false);
                        return 1;
                    }));

            dispatcher.register(CommandManager.literal("undertalepurgereset")
                    .requires(source -> source.hasPermissionLevel(2))
                    .executes(context -> {
                        isPurgeActive = false;
                        purgedMobs.clear();
                        extinctMobs.clear();
                        killCounts.clear();
                        nextKillTargets.clear();

                        context.getSource().sendFeedback(() ->
                                Text.literal("§aUndertale purge reset! All mobs restored, counters cleared."), false);
                        return 1;
                    }));

            // Status and configuration commands - Undertale style
            dispatcher.register(CommandManager.literal("undertalestatus")
                    .requires(source -> source.hasPermissionLevel(2))
                    .executes(context -> {
                        ServerCommandSource source = context.getSource();
                        source.sendFeedback(() -> Text.literal("§6=== GENOCIDE ROUTE STATUS ==="), false);
                        source.sendFeedback(() -> Text.literal("§7Purge Active: " + (isPurgeActive ? "§4YES" : "§7NO")), false);
                        source.sendFeedback(() -> Text.literal("§7Extinction Threshold: §f" + EXTINCTION_THRESHOLD + " kills"), false);
                        source.sendFeedback(() -> Text.literal("§7Purged Species: §c" + purgedMobs.size()), false);
                        source.sendFeedback(() -> Text.literal("§7Extinct Species: §4" + extinctMobs.size()), false);

                        if (!killCounts.isEmpty()) {
                            source.sendFeedback(() -> Text.literal("§6Current Monster Kill Counts:"), false);
                            killCounts.entrySet().stream()
                                    .sorted(Map.Entry.<Identifier, Integer>comparingByValue().reversed())
                                    .limit(10)
                                    .forEach(entry -> {
                                        EntityType<?> type = Registries.ENTITY_TYPE.get(entry.getKey());
                                        source.sendFeedback(() -> Text.literal("§7- " + type.getName().getString() + ": §c" + entry.getValue() + "§7/§f" + EXTINCTION_THRESHOLD), false);
                                    });
                        }
                        return 1;
                    }));

            dispatcher.register(CommandManager.literal("undertaleconfig")
                    .requires(source -> source.hasPermissionLevel(2))
                    .then(CommandManager.argument("threshold", IntegerArgumentType.integer(1, 10000))
                            .executes(context -> {
                                int newThreshold = IntegerArgumentType.getInteger(context, "threshold");
                                EXTINCTION_THRESHOLD = newThreshold;
                                context.getSource().sendFeedback(() ->
                                        Text.literal("§6Extinction threshold set to " + newThreshold + " kills. The genocide route requires more determination."), false);
                                return 1;
                            })));

            // Command to play Undertale animation
            dispatcher.register(CommandManager.literal("playundertaleanimation")
                    .requires(source -> source.hasPermissionLevel(2))
                    .executes(context -> {
                        if (animationPlayer != null) {
                            if (animationPlayer.isPlaying()) {
                                context.getSource().sendFeedback(() ->
                                        Text.literal("§6Animation is already playing!"), false);
                            } else {
                                animationPlayer.startAnimation();
                                context.getSource().sendFeedback(() ->
                                        Text.literal("§6Starting Undertale animation..."), false);
                            }
                        } else {
                            context.getSource().sendFeedback(() ->
                                    Text.literal("§cAnimation player is not initialized!"), false);
                        }
                        return 1;
                    }));
        });
    }

    private void registerEvents() {
        // Handle entity death events
        ServerLivingEntityEvents.AFTER_DEATH.register((entity, damageSource) -> {
            if (!(entity instanceof MobEntity)) return;

            // Check if killed by player
            if (damageSource.getAttacker() instanceof ServerPlayerEntity player) {
                EntityType<?> entityType = entity.getType();
                if (entityType == null) return;

                Identifier mobId = Registries.ENTITY_TYPE.getId(entityType);
                if (mobId == null) return;

                // Handle "disable next" functionality
                if (nextKillTargets.containsKey(player.getUuid())) {
                    purgedMobs.add(mobId);
                    nextKillTargets.remove(player.getUuid());

                    player.sendMessage(Text.literal("§4" + entityType.getName().getString() +
                            " have been purged from existence forever!"), false);
                    LOGGER.info("Player {} purged mob type: {}", player.getName().getString(), mobId);
                    return;
                }

                // Handle purge counting system
                if (isPurgeActive) {
                    int currentCount = killCounts.getOrDefault(mobId, 0) + 1;
                    killCounts.put(mobId, currentCount);

                    // Check for extinction threshold
                    if (currentCount >= EXTINCTION_THRESHOLD && !extinctMobs.contains(mobId)) {
                        extinctMobs.add(mobId);
                        purgedMobs.add(mobId); // Also add to purged set

                        // Send extinction notification
                        player.sendMessage(Text.literal("§4" + entityType.getName().getString() +
                                " are now extinct! Their kind has been erased from existence! (Killed " + currentCount + " times)"), false);

                        // INSTANT EXTINCTION: Kill all remaining mobs of this type in all loaded worlds
                        MinecraftServer server = player.getServer();
                        if (server != null) {
                            int killedCount = 0;
                            for (ServerWorld serverWorld : server.getWorlds()) {
                                // Find all entities of this type and kill them instantly
                                for (Object entityObj : serverWorld.getEntitiesByType(entityType, livingEntity -> livingEntity.isAlive())) {
                                    if (entityObj instanceof MobEntity mobEntity) {
                                        // Create dramatic death effect
                                        mobEntity.damage(serverWorld.getDamageSources().genericKill(), Float.MAX_VALUE);
                                        killedCount++;
                                    }
                                }
                            }

                            // Notify about the mass extinction
                            if (killedCount > 0) {
                                final int finalKilledCount = killedCount;
                                final String mobName = entityType.getName().getString();
                                player.sendMessage(Text.literal("§c💀 GENOCIDE EVENT: " + finalKilledCount +
                                        " remaining " + mobName + " have been eliminated from all worlds!"), false);
                                LOGGER.info("Extinction event eliminated {} remaining {} mobs", finalKilledCount, mobId);
                            }
                        }

                        LOGGER.info("Mob type {} went extinct after {} kills by {}",
                                mobId, currentCount, player.getName().getString());
                    } else if (currentCount % 50 == 0) {
                        // Progress notification every 50 kills
                        player.sendMessage(Text.literal("§7" + entityType.getName().getString() +
                                " kill count: " + currentCount + "/" + EXTINCTION_THRESHOLD), true);
                    }
                }
            }
        });

        // ULTRA-FAST no-mixin spawn prevention system
        ServerEntityEvents.ENTITY_LOAD.register((entity, world) -> {
            if (entity instanceof MobEntity mobEntity && entity.getType() != null) {
                Identifier mobId = Registries.ENTITY_TYPE.getId(entity.getType());

                if (mobId != null && (purgedMobs.contains(mobId) || extinctMobs.contains(mobId))) {
                    // Immediate removal - no delay whatsoever
                    mobEntity.discard();
                    LOGGER.debug("Instantly removed extinct mob: {}", mobId);
                }
            }
        });

        // Super aggressive tick-based cleanup - every single tick for maximum speed
        ServerTickEvents.END_WORLD_TICK.register(world -> {
            try {
                // Check EVERY tick for instant removal
                world.iterateEntities().forEach(entity -> {
                    if (entity instanceof MobEntity mobEntity && mobEntity.isAlive() && !mobEntity.isRemoved()) {
                        EntityType<?> entityType = mobEntity.getType();
                        if (entityType != null) {
                            Identifier mobId = Registries.ENTITY_TYPE.getId(entityType);
                            if (mobId != null && (purgedMobs.contains(mobId) || extinctMobs.contains(mobId))) {
                                mobEntity.discard();
                                LOGGER.debug("Tick-removed extinct mob: {}", mobId);
                            }
                        }
                    }
                });
            } catch (Exception e) {
                // Ignore errors to prevent crash loops
            }
        });
    }

    private void registerServerEvents() {
        // Save data when server stops
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            saveModData(server);
        });

        // Load data when server starts
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            loadModData(server);
        });

        // Periodic autosave every 5 minutes
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            Timer timer = new Timer(true);
            timer.scheduleAtFixedRate(new TimerTask() {
                @Override
                public void run() {
                    if (server.isRunning()) {
                        saveModData(server);
                    }
                }
            }, 300000, 300000); // 5 minutes
        });
    }

    private void saveModData(MinecraftServer server) {
        try {
            PersistentStateManager stateManager = server.getOverworld().getPersistentStateManager();
            ExtinctionData data = stateManager.getOrCreate(
                    ExtinctionData::fromNbt,
                    ExtinctionData::new,
                    MOD_ID + "_data"
            );

            data.purgedMobs.clear();
            data.purgedMobs.addAll(purgedMobs);

            data.extinctMobs.clear();
            data.extinctMobs.addAll(extinctMobs);

            data.killCounts.clear();
            data.killCounts.putAll(killCounts);

            data.extinctionThreshold = EXTINCTION_THRESHOLD;
            data.isPurgeActive = isPurgeActive;

            data.markDirty();
            LOGGER.info("Undertale Extinct data saved successfully");

        } catch (Exception e) {
            LOGGER.error("Failed to save Undertale Extinct data", e);
        }
    }

    private void loadModData(MinecraftServer server) {
        try {
            PersistentStateManager stateManager = server.getOverworld().getPersistentStateManager();
            ExtinctionData data = stateManager.getOrCreate(
                    ExtinctionData::fromNbt,
                    ExtinctionData::new,
                    MOD_ID + "_data"
            );

            purgedMobs.clear();
            purgedMobs.addAll(data.purgedMobs);

            extinctMobs.clear();
            extinctMobs.addAll(data.extinctMobs);

            killCounts.clear();
            killCounts.putAll(data.killCounts);

            EXTINCTION_THRESHOLD = data.extinctionThreshold;
            isPurgeActive = data.isPurgeActive;

            LOGGER.info("Undertale Extinct data loaded: {} purged, {} extinct, {} tracked",
                    purgedMobs.size(), extinctMobs.size(), killCounts.size());

        } catch (Exception e) {
            LOGGER.error("Failed to load Undertale Extinct data", e);
        }
    }

    // Custom PersistentState class for data management
    public static class ExtinctionData extends PersistentState {
        public final Set<Identifier> purgedMobs = ConcurrentHashMap.newKeySet();
        public final Set<Identifier> extinctMobs = ConcurrentHashMap.newKeySet();
        public final Map<Identifier, Integer> killCounts = new ConcurrentHashMap<>();
        public int extinctionThreshold = 500;
        public boolean isPurgeActive = false;

        public static ExtinctionData fromNbt(NbtCompound nbt) {
            ExtinctionData data = new ExtinctionData();

            // Load purged mobs
            if (nbt.contains("purgedMobs")) {
                NbtCompound purgedData = nbt.getCompound("purgedMobs");
                for (String key : purgedData.getKeys()) {
                    if (purgedData.getBoolean(key)) {
                        data.purgedMobs.add(new Identifier(key));
                    }
                }
            }

            // Load extinct mobs
            if (nbt.contains("extinctMobs")) {
                NbtCompound extinctData = nbt.getCompound("extinctMobs");
                for (String key : extinctData.getKeys()) {
                    if (extinctData.getBoolean(key)) {
                        data.extinctMobs.add(new Identifier(key));
                    }
                }
            }

            // Load kill counts
            if (nbt.contains("killCounts")) {
                NbtCompound killData = nbt.getCompound("killCounts");
                for (String key : killData.getKeys()) {
                    data.killCounts.put(new Identifier(key), killData.getInt(key));
                }
            }

            // Load configuration
            if (nbt.contains("extinctionThreshold")) {
                data.extinctionThreshold = nbt.getInt("extinctionThreshold");
            }
            if (nbt.contains("isPurgeActive")) {
                data.isPurgeActive = nbt.getBoolean("isPurgeActive");
            }

            return data;
        }

        @Override
        public NbtCompound writeNbt(NbtCompound nbt) {
            // Save purged mobs
            NbtCompound purgedData = new NbtCompound();
            for (Identifier mobId : purgedMobs) {
                purgedData.putBoolean(mobId.toString(), true);
            }
            nbt.put("purgedMobs", purgedData);

            // Save extinct mobs
            NbtCompound extinctData = new NbtCompound();
            for (Identifier mobId : extinctMobs) {
                extinctData.putBoolean(mobId.toString(), true);
            }
            nbt.put("extinctMobs", extinctData);

            // Save kill counts
            NbtCompound killData = new NbtCompound();
            for (Map.Entry<Identifier, Integer> entry : killCounts.entrySet()) {
                killData.putInt(entry.getKey().toString(), entry.getValue());
            }
            nbt.put("killCounts", killData);

            // Save configuration
            nbt.putInt("extinctionThreshold", extinctionThreshold);
            nbt.putBoolean("isPurgeActive", isPurgeActive);

            return nbt;
        }
    }

    // Utility methods for external access (if needed for other mods)
    public static boolean isMobPurged(EntityType<?> entityType) {
        Identifier mobId = Registries.ENTITY_TYPE.getId(entityType);
        return purgedMobs.contains(mobId) || extinctMobs.contains(mobId);
    }

    public static boolean isMobExtinct(EntityType<?> entityType) {
        Identifier mobId = Registries.ENTITY_TYPE.getId(entityType);
        return extinctMobs.contains(mobId);
    }

    public static int getKillCount(EntityType<?> entityType) {
        Identifier mobId = Registries.ENTITY_TYPE.getId(entityType);
        return killCounts.getOrDefault(mobId, 0);
    }

    public static boolean isPurgeSystemActive() {
        return isPurgeActive;
    }

    public static void addPurgedMob(EntityType<?> entityType) {
        Identifier mobId = Registries.ENTITY_TYPE.getId(entityType);
        purgedMobs.add(mobId);
    }

    public static void removePurgedMob(EntityType<?> entityType) {
        Identifier mobId = Registries.ENTITY_TYPE.getId(entityType);
        purgedMobs.remove(mobId);
        extinctMobs.remove(mobId);
        killCounts.remove(mobId);
    }

    public static Set<Identifier> getPurgedMobs() {
        return new HashSet<>(purgedMobs);
    }

    public static Set<Identifier> getExtinctMobs() {
        return new HashSet<>(extinctMobs);
    }

    public static void setExtinctionThreshold(int threshold) {
        EXTINCTION_THRESHOLD = Math.max(1, threshold);
    }

    public static int getExtinctionThreshold() {
        return EXTINCTION_THRESHOLD;
    }
}