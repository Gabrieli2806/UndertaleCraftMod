package com.g2806.undertaleextinct;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.command.argument.RegistryEntryArgumentType;
import net.minecraft.command.argument.EntityArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.mob.*;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.entity.passive.SnifferEntity;
import net.minecraft.entity.boss.dragon.EnderDragonEntity;
import net.minecraft.entity.SpawnReason;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.random.Random;
import net.minecraft.world.SpawnHelper;
import net.minecraft.world.biome.source.BiomeAccess;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.world.World;
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
import net.minecraft.client.MinecraftClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class UndertaleExtinct implements ModInitializer {
    public static final String MOD_ID = "undertaleextinct";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    // Configuration
    private static int EXTINCTION_THRESHOLD = 500;
    private static ModConfig config;

    // State management
    private static boolean isPurgeActive = false;
    private static boolean isNetherSaved = false;
    private static boolean isOverworldSaved = false;
    private static boolean isEndSaved = false;
    private static final Set<Identifier> purgedMobs = ConcurrentHashMap.newKeySet();
    private static final Set<Identifier> extinctMobs = ConcurrentHashMap.newKeySet();
    private static final Map<Identifier, Integer> killCounts = new ConcurrentHashMap<>();
    private static final Map<UUID, Identifier> nextKillTargets = new ConcurrentHashMap<>();

    // All vanilla hostile and neutral mobs that can be made extinct
    private static final Set<Identifier> VANILLA_MOBS = Set.of(
        // Hostile mobs
        new Identifier("minecraft", "zombie"),
        new Identifier("minecraft", "skeleton"),
        new Identifier("minecraft", "creeper"),
        new Identifier("minecraft", "spider"),
        new Identifier("minecraft", "cave_spider"),
        new Identifier("minecraft", "witch"),
        new Identifier("minecraft", "enderman"),
        new Identifier("minecraft", "blaze"),
        new Identifier("minecraft", "ghast"),
        new Identifier("minecraft", "magma_cube"),
        new Identifier("minecraft", "slime"),
        new Identifier("minecraft", "silverfish"),
        new Identifier("minecraft", "endermite"),
        new Identifier("minecraft", "guardian"),
        new Identifier("minecraft", "elder_guardian"),
        new Identifier("minecraft", "shulker"),
        new Identifier("minecraft", "phantom"),
        new Identifier("minecraft", "drowned"),
        new Identifier("minecraft", "husk"),
        new Identifier("minecraft", "stray"),
        new Identifier("minecraft", "wither_skeleton"),
        new Identifier("minecraft", "zombie_villager"),
        new Identifier("minecraft", "pillager"),
        new Identifier("minecraft", "vindicator"),
        new Identifier("minecraft", "evoker"),
        new Identifier("minecraft", "ravager"),
        new Identifier("minecraft", "vex"),
        new Identifier("minecraft", "piglin"),
        new Identifier("minecraft", "piglin_brute"),
        new Identifier("minecraft", "hoglin"),
        new Identifier("minecraft", "zoglin"),
        new Identifier("minecraft", "zombified_piglin"),
        new Identifier("minecraft", "warden"),
        // Neutral mobs that can be hostile
        new Identifier("minecraft", "zombie_pigman"), // Legacy name
        new Identifier("minecraft", "iron_golem"),
        new Identifier("minecraft", "wolf"),
        new Identifier("minecraft", "polar_bear"),
        new Identifier("minecraft", "llama"),
        new Identifier("minecraft", "trader_llama"),
        new Identifier("minecraft", "panda"),
        new Identifier("minecraft", "bee"),
        new Identifier("minecraft", "dolphin"),
        new Identifier("minecraft", "goat"),
        // Boss mobs
        new Identifier("minecraft", "ender_dragon"),
        new Identifier("minecraft", "wither")
    );

    private static boolean allVanillaMobsExtinctNotified = false;
    private static boolean allMobSpawningDisabled = false;
    
    // Animation player instance
    private static AnimationPlayer animationPlayer;

    @Override
    public void onInitialize() {
        LOGGER.info("Undertale Extinct mod initialized!");

        // Initialize config
        config = ModConfig.getInstance();

        // Initialize animation player
        animationPlayer = new AnimationPlayer();

        // Initialize networking
        UndertaleNetworking.registerServerPackets();

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
                        allVanillaMobsExtinctNotified = false; // Reset vanilla extinction notification
                        allMobSpawningDisabled = false; // Re-enable mob spawning

                        // Reset scoreboard objective for world purge status
                        ServerCommandSource source = context.getSource();
                        if (source.getEntity() instanceof ServerPlayerEntity player) {
                            MinecraftServer server = player.getServer();
                            if (server != null) {
                                setWorldPurgeScoreboard(server, false);
                            }
                        }

                        context.getSource().sendFeedback(() ->
                                Text.literal("§aUndertale purge reset! All mobs restored, counters cleared. Scoreboard: §eworldpurged = 0"), false);
                        return 1;
                    }));

            // Status and configuration commands - Undertale style
            dispatcher.register(CommandManager.literal("undertalestatus")
                    .requires(source -> source.hasPermissionLevel(2))
                    .executes(context -> {
                        ServerCommandSource source = context.getSource();
                        source.sendFeedback(() -> Text.literal("§6=== UNDERTALE MOD STATUS ==="), false);
                        source.sendFeedback(() -> Text.literal("§7Purge Active: " + (isPurgeActive ? "§4YES" : "§7NO")), false);
                        source.sendFeedback(() -> Text.literal("§7Nether Saved: " + (isNetherSaved ? "§aTRUE" : "§7FALSE")), false);
                        source.sendFeedback(() -> Text.literal("§7Overworld Saved: " + (isOverworldSaved ? "§aTRUE" : "§7FALSE")), false);
                        source.sendFeedback(() -> Text.literal("§7End Saved: " + (isEndSaved ? "§aTRUE" : "§7FALSE")), false);
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
                            }))
                    .then(CommandManager.literal("chatmessages")
                            .then(CommandManager.literal("on")
                                    .executes(context -> {
                                        config.setChatMessagesEnabled(true);
                                        context.getSource().sendFeedback(() ->
                                                Text.literal("§aChatmessages enabled! You will see all extinction and save notifications."), false);
                                        return 1;
                                    }))
                            .then(CommandManager.literal("off")
                                    .executes(context -> {
                                        config.setChatMessagesEnabled(false);
                                        context.getSource().sendFeedback(() ->
                                                Text.literal("§cChatmessages disabled! No more attack notifications will appear."), false);
                                        return 1;
                                    }))
                            .executes(context -> {
                                context.getSource().sendFeedback(() ->
                                        Text.literal("§7Chat messages are currently: " + (config.isChatMessagesEnabled() ? "§aENABLED" : "§cDISABLED")), false);
                                return 1;
                            }))
                    .then(CommandManager.literal("attackspeed")
                            .then(CommandManager.argument("speed", com.mojang.brigadier.arguments.DoubleArgumentType.doubleArg(0.01, 0.1))
                                    .executes(context -> {
                                        double newSpeed = com.mojang.brigadier.arguments.DoubleArgumentType.getDouble(context, "speed");
                                        config.setAttackBarSpeed(newSpeed);
                                        context.getSource().sendFeedback(() ->
                                                Text.literal("§6Attack bar speed set to " + String.format("%.3f", newSpeed) + ". Higher = faster, lower = slower."), false);
                                        return 1;
                                    }))
                            .executes(context -> {
                                context.getSource().sendFeedback(() ->
                                        Text.literal("§7Current attack bar speed: §f" + String.format("%.3f", config.getAttackBarSpeed()) + " §7(default: 0.030)"), false);
                                return 1;
                            }))
                    .executes(context -> {
                        ServerCommandSource source = context.getSource();
                        source.sendFeedback(() -> Text.literal("§6=== UNDERTALE MOD CONFIG ==="), false);
                        source.sendFeedback(() -> Text.literal("§7Chat Messages: " + (config.isChatMessagesEnabled() ? "§aENABLED" : "§cDISABLED")), false);
                        source.sendFeedback(() -> Text.literal("§7Attack Bar Speed: §f" + String.format("%.3f", config.getAttackBarSpeed()) + " §7(default: 0.030)"), false);
                        source.sendFeedback(() -> Text.literal("§7Extinction Threshold: §f" + EXTINCTION_THRESHOLD + " kills"), false);
                        source.sendFeedback(() -> Text.literal("§e/undertaleconfig chatmessages [on|off] §7- Toggle chat notifications"), false);
                        source.sendFeedback(() -> Text.literal("§e/undertaleconfig attackspeed <0.01-0.1> §7- Set attack bar speed"), false);
                        return 1;
                    }));

            // Command to play Undertale animation (self or target player)
            dispatcher.register(CommandManager.literal("playundertaleanimation")
                    .requires(source -> source.hasPermissionLevel(2))
                    .executes(context -> {
                        // Play for command sender (self)
                        ServerCommandSource source = context.getSource();
                        if (source.getEntity() instanceof ServerPlayerEntity player) {
                            UndertaleNetworking.sendStartAnimationToPlayer(player);
                            if (config.isChatMessagesEnabled()) {
                                source.sendFeedback(() -> Text.literal("§6Starting Undertale animation for you..."), false);
                            }
                            return 1;
                        }
                        return 0;
                    })
                    .then(CommandManager.argument("target", EntityArgumentType.player())
                            .executes(context -> {
                                // Play for target player
                                ServerPlayerEntity targetPlayer = EntityArgumentType.getPlayer(context, "target");
                                ServerCommandSource source = context.getSource();
                                
                                UndertaleNetworking.sendStartAnimationToPlayer(targetPlayer);
                                source.sendFeedback(() -> Text.literal("§6Starting Undertale animation for " + targetPlayer.getName().getString() + "..."), false);
                                
                                // Also notify the target player
                                targetPlayer.sendMessage(Text.literal("§6An admin started the Undertale animation for you!"), false);
                                return 1;
                            })));

            // Server-side undertaleattack command with player targeting
            dispatcher.register(CommandManager.literal("undertaleattack")
                    .executes(context -> {
                        // Start attack for command sender (self)
                        ServerCommandSource source = context.getSource();
                        if (source.getEntity() instanceof ServerPlayerEntity player) {
                            UndertaleNetworking.sendStartAttackToPlayer(player);
                            if (config.isChatMessagesEnabled()) {
                                source.sendFeedback(() -> Text.literal("§6Starting attack interface..."), false);
                            }
                            return 1;
                        }
                        return 0;
                    })
                    .then(CommandManager.argument("target", EntityArgumentType.player())
                            .requires(source -> source.hasPermissionLevel(2)) // Admin required for targeting
                            .executes(context -> {
                                // Start attack for target player
                                ServerPlayerEntity targetPlayer = EntityArgumentType.getPlayer(context, "target");
                                ServerCommandSource source = context.getSource();
                                
                                UndertaleNetworking.sendStartAttackToPlayer(targetPlayer);
                                if (config.isChatMessagesEnabled()) {
                                    source.sendFeedback(() -> Text.literal("§6Starting attack interface for " + targetPlayer.getName().getString() + "..."), false);
                                }
                                
                                // Also notify the target player (if chat messages enabled)
                                if (config.isChatMessagesEnabled()) {
                                    targetPlayer.sendMessage(Text.literal("§6An admin started the attack interface for you!"), false);
                                }
                                return 1;
                            })));
            
            // Server-side undertaleattackgun command with player targeting
            dispatcher.register(CommandManager.literal("undertaleattackgun")
                    .executes(context -> {
                        // Start gun attack for command sender (self)
                        ServerCommandSource source = context.getSource();
                        if (source.getEntity() instanceof ServerPlayerEntity player) {
                            UndertaleNetworking.sendStartGunAttackToPlayer(player);
                            if (config.isChatMessagesEnabled()) {
                                source.sendFeedback(() -> Text.literal("§6Starting gun attack interface..."), false);
                            }
                            return 1;
                        }
                        return 0;
                    })
                    .then(CommandManager.argument("target", EntityArgumentType.player())
                            .requires(source -> source.hasPermissionLevel(2)) // Admin required for targeting
                            .executes(context -> {
                                // Start gun attack for target player
                                ServerPlayerEntity targetPlayer = EntityArgumentType.getPlayer(context, "target");
                                ServerCommandSource source = context.getSource();
                                
                                UndertaleNetworking.sendStartGunAttackToPlayer(targetPlayer);
                                if (config.isChatMessagesEnabled()) {
                                    source.sendFeedback(() -> Text.literal("§6Starting gun attack interface for " + targetPlayer.getName().getString() + "..."), false);
                                }

                                // Also notify the target player (if chat messages enabled)
                                if (config.isChatMessagesEnabled()) {
                                    targetPlayer.sendMessage(Text.literal("§6An admin started the gun attack interface for you!"), false);
                                }
                                return 1;
                            })));

            // Command to save the nether and overworld - Pacifist Routes
            dispatcher.register(CommandManager.literal("undertale")
                    .then(CommandManager.literal("nethersaved")
                            .requires(source -> source.hasPermissionLevel(2))
                            .executes(context -> {
                                ServerCommandSource source = context.getSource();
                                if (source.getEntity() instanceof ServerPlayerEntity player) {
                                    if (isNetherSaved) {
                                        source.sendFeedback(() -> Text.literal("§eThe nether has already been saved!"), false);
                                        return 1;
                                    }

                                    // Activate nether saved mode
                                    isNetherSaved = true;

                                    // Grant advancement
                                    grantNetherSavedAdvancement(player);

                                    source.sendFeedback(() -> Text.literal("§a✦ THE NETHER HAS BEEN SAVED! ✦"), false);
                                    source.sendFeedback(() -> Text.literal("§6All nether inhabitants are now safe and at peace."), false);
                                    source.sendFeedback(() -> Text.literal("§7They will be neutral and regenerate in the overworld."), false);

                                    LOGGER.info("Player {} activated nether saved mode", player.getName().getString());
                                    return 1;
                                }
                                return 0;
                            }))
                    .then(CommandManager.literal("overworldsaved")
                            .requires(source -> source.hasPermissionLevel(2))
                            .executes(context -> {
                                ServerCommandSource source = context.getSource();
                                if (source.getEntity() instanceof ServerPlayerEntity player) {
                                    if (isOverworldSaved) {
                                        source.sendFeedback(() -> Text.literal("§eThe overworld has already been saved!"), false);
                                        return 1;
                                    }

                                    // Activate overworld saved mode
                                    isOverworldSaved = true;

                                    // Grant advancement
                                    grantOverworldSavedAdvancement(player);

                                    source.sendFeedback(() -> Text.literal("§a✦ THE OVERWORLD HAS BEEN SAVED! ✦"), false);
                                    source.sendFeedback(() -> Text.literal("§6All creatures now live in harmony and peace."), false);
                                    source.sendFeedback(() -> Text.literal("§7Undead have been cured, and raids are no more."), false);

                                    LOGGER.info("Player {} activated overworld saved mode", player.getName().getString());
                                    return 1;
                                }
                                return 0;
                            }))
                    .then(CommandManager.literal("endsaved")
                            .requires(source -> source.hasPermissionLevel(2))
                            .executes(context -> {
                                ServerCommandSource source = context.getSource();
                                if (source.getEntity() instanceof ServerPlayerEntity player) {
                                    if (isEndSaved) {
                                        source.sendFeedback(() -> Text.literal("§eThe end has already been saved!"), false);
                                        return 1;
                                    }

                                    // Activate end saved mode
                                    isEndSaved = true;

                                    // Grant advancement
                                    grantEndSavedAdvancement(player);

                                    source.sendFeedback(() -> Text.literal("§5✦ THE END HAS BEEN SAVED! ✦"), false);
                                    source.sendFeedback(() -> Text.literal("§6The dragons are now safe and free to roam."), false);
                                    source.sendFeedback(() -> Text.literal("§7Peaceful enderdragons will appear in the end."), false);

                                    LOGGER.info("Player {} activated end saved mode", player.getName().getString());
                                    return 1;
                                }
                                return 0;
                            }))
                    .then(CommandManager.literal("resetoverworld")
                            .requires(source -> source.hasPermissionLevel(2))
                            .executes(context -> {
                                ServerCommandSource source = context.getSource();
                                isOverworldSaved = false;

                                // Kill all sniffers in the overworld
                                if (source.getEntity() instanceof ServerPlayerEntity player) {
                                    MinecraftServer server = player.getServer();
                                    if (server != null) {
                                        int killedSniffers = 0;
                                        for (ServerWorld serverWorld : server.getWorlds()) {
                                            if (serverWorld.getRegistryKey() == World.OVERWORLD) {
                                                for (var entity : serverWorld.iterateEntities()) {
                                                    if (entity instanceof SnifferEntity sniffer) {
                                                        sniffer.damage(serverWorld.getDamageSources().genericKill(), Float.MAX_VALUE);
                                                        killedSniffers++;
                                                    }
                                                }
                                            }
                                        }
                                        final int finalKilledSniffers = killedSniffers;
                                        if (killedSniffers > 0) {
                                            source.sendFeedback(() -> Text.literal("§cOverworld saved mode reset! " + finalKilledSniffers + " sniffers removed."), false);
                                        } else {
                                            source.sendFeedback(() -> Text.literal("§cOverworld saved mode reset! All creatures return to normal."), false);
                                        }
                                    }
                                } else {
                                    source.sendFeedback(() -> Text.literal("§cOverworld saved mode reset! All creatures return to normal."), false);
                                }
                                return 1;
                            }))
                    .then(CommandManager.literal("resetnether")
                            .requires(source -> source.hasPermissionLevel(2))
                            .executes(context -> {
                                ServerCommandSource source = context.getSource();
                                isNetherSaved = false;

                                // Kill all nether mobs in the overworld
                                if (source.getEntity() instanceof ServerPlayerEntity player) {
                                    MinecraftServer server = player.getServer();
                                    if (server != null) {
                                        int killedNetherMobs = 0;
                                        for (ServerWorld serverWorld : server.getWorlds()) {
                                            if (serverWorld.getRegistryKey() == World.OVERWORLD) {
                                                for (var entity : serverWorld.iterateEntities()) {
                                                    if (entity instanceof MobEntity mobEntity && isNetherMob(mobEntity.getType())) {
                                                        mobEntity.damage(serverWorld.getDamageSources().genericKill(), Float.MAX_VALUE);
                                                        killedNetherMobs++;
                                                    }
                                                }
                                            }
                                        }
                                        final int finalKilledNetherMobs = killedNetherMobs;
                                        if (killedNetherMobs > 0) {
                                            source.sendFeedback(() -> Text.literal("§cNether saved mode reset! " + finalKilledNetherMobs + " nether creatures removed from overworld."), false);
                                        } else {
                                            source.sendFeedback(() -> Text.literal("§cNether saved mode reset! Nether creatures return to nether."), false);
                                        }
                                    }
                                } else {
                                    source.sendFeedback(() -> Text.literal("§cNether saved mode reset! Nether creatures return to nether."), false);
                                }
                                return 1;
                            }))
                    .then(CommandManager.literal("resetend")
                            .requires(source -> source.hasPermissionLevel(2))
                            .executes(context -> {
                                ServerCommandSource source = context.getSource();
                                isEndSaved = false;

                                // Kill all enderdragons in the end
                                if (source.getEntity() instanceof ServerPlayerEntity player) {
                                    MinecraftServer server = player.getServer();
                                    if (server != null) {
                                        int killedDragons = 0;
                                        for (ServerWorld serverWorld : server.getWorlds()) {
                                            if (serverWorld.getRegistryKey() == World.END) {
                                                for (var entity : serverWorld.iterateEntities()) {
                                                    if (entity instanceof EnderDragonEntity dragon) {
                                                        dragon.damage(serverWorld.getDamageSources().genericKill(), Float.MAX_VALUE);
                                                        killedDragons++;
                                                    }
                                                }
                                            }
                                        }
                                        final int finalKilledDragons = killedDragons;
                                        if (killedDragons > 0) {
                                            source.sendFeedback(() -> Text.literal("§cEnd saved mode reset! " + finalKilledDragons + " dragons killed."), false);
                                        } else {
                                            source.sendFeedback(() -> Text.literal("§cEnd saved mode reset! Dragons return to normal."), false);
                                        }
                                    }
                                } else {
                                    source.sendFeedback(() -> Text.literal("§cEnd saved mode reset! Dragons return to normal."), false);
                                }
                                return 1;
                            })));

            // Numbered attack commands (1-20) - each saves to separate scoreboard
            for (int i = 1; i <= 20; i++) {
                final int attackNumber = i; // Final for lambda
                
                // undertaleattack1 to undertaleattack20
                dispatcher.register(CommandManager.literal("undertaleattack" + i)
                        .executes(context -> {
                            // Start numbered attack for command sender (self)
                            ServerCommandSource source = context.getSource();
                            if (source.getEntity() instanceof ServerPlayerEntity player) {
                                UndertaleNetworking.sendStartNumberedAttackToPlayer(player, attackNumber);
                                if (config.isChatMessagesEnabled()) {
                                    source.sendFeedback(() -> Text.literal("§6Starting attack interface " + attackNumber + "..."), false);
                                }
                                return 1;
                            }
                            return 0;
                        })
                        .then(CommandManager.argument("target", EntityArgumentType.player())
                                .requires(source -> source.hasPermissionLevel(2)) // Admin required for targeting
                                .executes(context -> {
                                    // Start numbered attack for target player
                                    ServerPlayerEntity targetPlayer = EntityArgumentType.getPlayer(context, "target");
                                    ServerCommandSource source = context.getSource();
                                    
                                    UndertaleNetworking.sendStartNumberedAttackToPlayer(targetPlayer, attackNumber);
                                    if (config.isChatMessagesEnabled()) {
                                        source.sendFeedback(() -> Text.literal("§6Starting attack interface " + attackNumber + " for " + targetPlayer.getName().getString() + "..."), false);
                                    }

                                    // Also notify the target player (if chat messages enabled)
                                    if (config.isChatMessagesEnabled()) {
                                        targetPlayer.sendMessage(Text.literal("§6An admin started attack interface " + attackNumber + " for you!"), false);
                                    }
                                    return 1;
                                })));
                
                // undertaleattackgun1 to undertaleattackgun20
                dispatcher.register(CommandManager.literal("undertaleattackgun" + i)
                        .executes(context -> {
                            // Start numbered gun attack for command sender (self)
                            ServerCommandSource source = context.getSource();
                            if (source.getEntity() instanceof ServerPlayerEntity player) {
                                UndertaleNetworking.sendStartNumberedGunAttackToPlayer(player, attackNumber);
                                if (config.isChatMessagesEnabled()) {
                                    source.sendFeedback(() -> Text.literal("§6Starting gun attack interface " + attackNumber + "..."), false);
                                }
                                return 1;
                            }
                            return 0;
                        })
                        .then(CommandManager.argument("target", EntityArgumentType.player())
                                .requires(source -> source.hasPermissionLevel(2)) // Admin required for targeting
                                .executes(context -> {
                                    // Start numbered gun attack for target player
                                    ServerPlayerEntity targetPlayer = EntityArgumentType.getPlayer(context, "target");
                                    ServerCommandSource source = context.getSource();
                                    
                                    UndertaleNetworking.sendStartNumberedGunAttackToPlayer(targetPlayer, attackNumber);
                                    if (config.isChatMessagesEnabled()) {
                                        source.sendFeedback(() -> Text.literal("§6Starting gun attack interface " + attackNumber + " for " + targetPlayer.getName().getString() + "..."), false);
                                    }

                                    // Also notify the target player (if chat messages enabled)
                                    if (config.isChatMessagesEnabled()) {
                                        targetPlayer.sendMessage(Text.literal("§6An admin started gun attack interface " + attackNumber + " for you!"), false);
                                    }
                                    return 1;
                                })));
            }
            
            // Attack scoreboard commands
            dispatcher.register(CommandManager.literal("attackstats")
                    .requires(source -> source.hasPermissionLevel(0)) // Allow all players
                    .executes(context -> {
                        ServerCommandSource source = context.getSource();
                        if (source.getEntity() instanceof ServerPlayerEntity player) {
                            showPlayerStats(player);
                            return 1;
                        }
                        return 0;
                    }));
                    
            dispatcher.register(CommandManager.literal("attackleaderboard")
                    .requires(source -> source.hasPermissionLevel(0)) // Allow all players
                    .executes(context -> {
                        ServerCommandSource source = context.getSource();
                        if (source.getEntity() instanceof ServerPlayerEntity player) {
                            showLeaderboard(player, 10);
                            return 1;
                        }
                        return 0;
                    }));
                    
            dispatcher.register(CommandManager.literal("resetattackstats")
                    .requires(source -> source.hasPermissionLevel(2)) // Admin only
                    .executes(context -> {
                        ServerCommandSource source = context.getSource();
                        if (source.getEntity() instanceof ServerPlayerEntity player) {
                            UndertaleScoreboard.resetPlayerScores(player.getUuid());
                            source.sendFeedback(() -> Text.literal("§aReset attack stats for " + player.getName().getString()), false);
                            return 1;
                        }
                        return 0;
                    }));

            // Vanilla mob extinction progress command
            dispatcher.register(CommandManager.literal("vanillaextinction")
                    .requires(source -> source.hasPermissionLevel(0)) // Allow all players
                    .executes(context -> {
                        addVanillaExtinctionCommand(context.getSource());
                        return 1;
                    }));

            // Command to instantly exterminate all vanilla mobs
            dispatcher.register(CommandManager.literal("vanillaextinctionfull")
                    .requires(source -> source.hasPermissionLevel(2)) // Admin only
                    .executes(context -> {
                        ServerCommandSource source = context.getSource();
                        if (source.getEntity() instanceof ServerPlayerEntity player) {
                            executeFullVanillaExtinction(player, source);
                            return 1;
                        }
                        return 0;
                    }));
        });
    }

    private void grantNetherSavedAdvancement(ServerPlayerEntity player) {
        try {
            // Actually grant the advancement
            var advancementManager = player.getServer().getAdvancementLoader();
            var advancement = advancementManager.get(new net.minecraft.util.Identifier("undertaleextinct", "save_the_nether"));

            if (advancement != null) {
                var playerAdvancementTracker = player.getAdvancementTracker();
                if (!playerAdvancementTracker.getProgress(advancement).isDone()) {
                    // Grant all criteria for this advancement
                    for (String criteria : advancement.getCriteria().keySet()) {
                        playerAdvancementTracker.grantCriterion(advancement, criteria);
                    }
                }
            }

            // Send legendary advancement message and effects
            player.sendMessage(Text.literal("§6✦ LEGENDARY ADVANCEMENT UNLOCKED ✦"), false);
            player.sendMessage(Text.literal("§e★ Save the Nether ★"), false);
            player.sendMessage(Text.literal("§7Save the nether and its inhabitants from the flames"), false);

            // Add visual and audio effects for legendary achievement
            player.playSound(net.minecraft.sound.SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, 1.0f, 1.0f);

            // Additional epic sounds for legendary feel
            player.playSound(net.minecraft.sound.SoundEvents.ENTITY_ENDER_DRAGON_DEATH, 0.3f, 1.5f);

        } catch (Exception e) {
            LOGGER.error("Failed to grant nether saved advancement effects", e);
        }
    }

    private void grantOverworldSavedAdvancement(ServerPlayerEntity player) {
        try {
            // Actually grant the advancement
            var advancementManager = player.getServer().getAdvancementLoader();
            var advancement = advancementManager.get(new net.minecraft.util.Identifier("undertaleextinct", "save_the_world"));

            if (advancement != null) {
                var playerAdvancementTracker = player.getAdvancementTracker();
                if (!playerAdvancementTracker.getProgress(advancement).isDone()) {
                    // Grant all criteria for this advancement
                    for (String criteria : advancement.getCriteria().keySet()) {
                        playerAdvancementTracker.grantCriterion(advancement, criteria);
                    }
                }
            }

            // Send legendary advancement message and effects
            player.sendMessage(Text.literal("§6✦ LEGENDARY ADVANCEMENT UNLOCKED ✦"), false);
            player.sendMessage(Text.literal("§a★ Save the World ★"), false);
            player.sendMessage(Text.literal("§7Save the world from infection and zombies, and have everyone living in harmony"), false);

            // Add visual and audio effects for legendary achievement
            player.playSound(net.minecraft.sound.SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, 1.0f, 1.0f);

            // Additional epic sounds for legendary feel - different from nether
            player.playSound(net.minecraft.sound.SoundEvents.BLOCK_BELL_USE, 0.5f, 1.2f);

        } catch (Exception e) {
            LOGGER.error("Failed to grant overworld saved advancement effects", e);
        }
    }

    private void grantEndSavedAdvancement(ServerPlayerEntity player) {
        try {
            // Actually grant the advancement
            var advancementManager = player.getServer().getAdvancementLoader();
            var advancement = advancementManager.get(new net.minecraft.util.Identifier("undertaleextinct", "save_the_dragons"));

            if (advancement != null) {
                var playerAdvancementTracker = player.getAdvancementTracker();
                if (!playerAdvancementTracker.getProgress(advancement).isDone()) {
                    // Grant all criteria for this advancement
                    for (String criteria : advancement.getCriteria().keySet()) {
                        playerAdvancementTracker.grantCriterion(advancement, criteria);
                    }
                }
            }

            // Send legendary advancement message and effects
            player.sendMessage(Text.literal("§6✦ LEGENDARY ADVANCEMENT UNLOCKED ✦"), false);
            player.sendMessage(Text.literal("§5★ Save the Dragons ★"), false);
            player.sendMessage(Text.literal("§7Save the enderdragon from becoming extinct"), false);

            // Add visual and audio effects for legendary achievement
            player.playSound(net.minecraft.sound.SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, 1.0f, 1.0f);

            // Additional epic sounds for legendary feel - dragon-themed
            player.playSound(net.minecraft.sound.SoundEvents.ENTITY_ENDER_DRAGON_AMBIENT, 0.3f, 1.0f);

        } catch (Exception e) {
            LOGGER.error("Failed to grant end saved advancement effects", e);
        }
    }

    private void showPlayerStats(ServerPlayerEntity player) {
        UndertaleScoreboard.PlayerScores scores = UndertaleScoreboard.getPlayerScores(player.getUuid());
        
        if (scores.totalAttacks == 0) {
            player.sendMessage(Text.literal("§7You haven't made any attacks yet! Use §6/undertaleattack §7to try."), false);
            return;
        }
        
        player.sendMessage(Text.literal("§6=== YOUR ATTACK STATS ==="), false);
        player.sendMessage(Text.literal("§7Last Attack: §f" + scores.lastAttackValue + "/100"), false);
        player.sendMessage(Text.literal("§7Best Attack: §f" + scores.bestAttackValue + "/100 " + getScoreRating(scores.bestAttackValue)), false);
        player.sendMessage(Text.literal("§7Average: §f" + String.format("%.1f", scores.averageAttackValue) + "/100"), false);
        player.sendMessage(Text.literal("§7Total Attacks: §f" + scores.totalAttacks), false);
        player.sendMessage(Text.literal("§7Perfect Attacks: §a" + scores.perfectAttacks + " §7(45-55 range)"), false);
        
        if (scores.perfectAttacks > 0) {
            float perfectRate = (float) scores.perfectAttacks / scores.totalAttacks * 100;
            player.sendMessage(Text.literal("§7Perfect Rate: §a" + String.format("%.1f", perfectRate) + "%"), false);
        }
    }
    
    private void showLeaderboard(ServerPlayerEntity requestingPlayer, int limit) {
        var leaderboard = UndertaleScoreboard.getLeaderboard(limit);
        
        if (leaderboard.isEmpty()) {
            requestingPlayer.sendMessage(Text.literal("§7No attack scores recorded yet!"), false);
            return;
        }
        
        requestingPlayer.sendMessage(Text.literal("§6=== ATTACK LEADERBOARD ==="), false);
        
        int rank = 1;
        for (var entry : leaderboard) {
            UUID playerId = entry.getKey();
            UndertaleScoreboard.PlayerScores scores = entry.getValue();
            
            // Try to get player name
            String playerName = "Unknown Player";
            ServerPlayerEntity player = requestingPlayer.getServer().getPlayerManager().getPlayer(playerId);
            if (player != null) {
                playerName = player.getName().getString();
            }
            
            String rankText = getRankText(rank);
            String scoreRating = getScoreRating(scores.bestAttackValue);
            
            requestingPlayer.sendMessage(Text.literal(
                rankText + " §f" + playerName + 
                " §7- Best: §f" + scores.bestAttackValue + "/100 " + scoreRating +
                " §7(Avg: " + String.format("%.1f", scores.averageAttackValue) + ")"
            ), false);
            
            rank++;
        }
    }
    
    private String getRankText(int rank) {
        return switch (rank) {
            case 1 -> "§6👑";
            case 2 -> "§7🥈";
            case 3 -> "§c🥉";
            default -> "§7" + rank + ".";
        };
    }
    
    private String getScoreRating(int score) {
        if (score >= 45 && score <= 55) {
            return "§a✦PERFECT✦";
        } else if (score >= 30 && score <= 70) {
            return "§e⚔Good⚔";
        } else if (score >= 15 && score <= 85) {
            return "§6◊Okay◊";
        } else {
            return "§c✗Miss✗";
        }
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

                    if (config.isChatMessagesEnabled()) {
                        player.sendMessage(Text.literal("§4" + entityType.getName().getString() +
                                " have been purged from existence forever!"), false);
                    }
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
                        if (config.isChatMessagesEnabled()) {
                            player.sendMessage(Text.literal("§4" + entityType.getName().getString() +
                                    " are now extinct! Their kind has been erased from existence! (Killed " + currentCount + " times)"), false);
                        }

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
                            if (killedCount > 0 && config.isChatMessagesEnabled()) {
                                final int finalKilledCount = killedCount;
                                final String mobName = entityType.getName().getString();
                                player.sendMessage(Text.literal("§c💀 GENOCIDE EVENT: " + finalKilledCount +
                                        " remaining " + mobName + " have been eliminated from all worlds!"), false);
                                LOGGER.info("Extinction event eliminated {} remaining {} mobs", finalKilledCount, mobId);
                            }
                        }

                        LOGGER.info("Mob type {} went extinct after {} kills by {}",
                                mobId, currentCount, player.getName().getString());

                        // Check if all vanilla mobs are now extinct
                        checkAllVanillaMobsExtinct(player);
                    } else if (currentCount % 50 == 0 && config.isChatMessagesEnabled()) {
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

                // If all mob spawning is disabled (after vanillaextinctionfull), remove ALL mobs
                if (allMobSpawningDisabled) {
                    mobEntity.discard();
                    return;
                }


                // Handle nether saved mode spawning rules
                if (isNetherSaved) {
                    handleNetherSavedSpawning(mobEntity, world);

                    // Percentage-based nether mob spawning in overworld
                    if (world.getRegistryKey() == World.OVERWORLD && !isNetherMob(mobEntity.getType()) && !isAquaticMob(mobEntity.getType())) {
                        // 25% chance to spawn a nether mob when any NON-AQUATIC overworld mob spawns
                        if (world instanceof ServerWorld) {
                        ServerWorld serverWorld = (ServerWorld) world;
                            if (serverWorld.getRandom().nextFloat() < 0.25f) {
                                spawnNetherMobNearLocation(serverWorld, mobEntity.getBlockPos());
                            }
                        }
                    }
                }

                // Handle overworld saved mode spawning rules
                if (isOverworldSaved) {
                    handleOverworldSavedSpawning(mobEntity, world);

                    // Percentage-based pillager spawning when villagers spawn
                    if (world.getRegistryKey() == World.OVERWORLD && mobEntity.getType() == EntityType.VILLAGER) {
                        // 60% chance to spawn a peaceful pillager when a villager spawns
                        if (world instanceof ServerWorld) {
                        ServerWorld serverWorld = (ServerWorld) world;
                            if (serverWorld.getRandom().nextFloat() < 0.60f) {
                                spawnPeacefulPillagerNearLocation(serverWorld, mobEntity.getBlockPos());
                            }
                        }
                    }
                }

                if (mobId != null && (purgedMobs.contains(mobId) || extinctMobs.contains(mobId))) {
                    // Don't remove mobs that were spawned as part of nether saved system
                    if (!mobEntity.getCommandTags().contains("nether_saved_spawn")) {
                        // Immediate removal - no delay whatsoever
                        mobEntity.discard();
                        LOGGER.info("Instantly removed extinct mob: {} at {} in {}", mobId, mobEntity.getBlockPos(), world.getRegistryKey().getValue());
                    } else {
                        LOGGER.info("Protected nether saved mob from extinction: {} at {} in {}", mobId, mobEntity.getBlockPos(), world.getRegistryKey().getValue());
                    }
                }
            }
        });

        // Super aggressive tick-based cleanup - every single tick for maximum speed
        ServerTickEvents.END_WORLD_TICK.register(world -> {
            try {
                // Check EVERY tick for instant removal and nether saved effects
                world.iterateEntities().forEach(entity -> {
                    if (entity instanceof MobEntity mobEntity && mobEntity.isAlive() && !mobEntity.isRemoved()) {
                        EntityType<?> entityType = mobEntity.getType();
                        if (entityType != null) {
                            Identifier mobId = Registries.ENTITY_TYPE.getId(entityType);

                            // If all mob spawning is disabled, remove ALL mobs immediately
                            if (allMobSpawningDisabled) {
                                mobEntity.discard();
                                return;
                            }

                            // Handle extinct/purged mob removal
                            if (mobId != null && (purgedMobs.contains(mobId) || extinctMobs.contains(mobId))) {
                                // Don't remove mobs that were spawned as part of nether saved system
                                if (!mobEntity.getCommandTags().contains("nether_saved_spawn")) {
                                    mobEntity.discard();
                                    LOGGER.info("Tick-removed extinct mob: {} at {} in {}", mobId, mobEntity.getBlockPos(), world.getRegistryKey().getValue());
                                    return;
                                } else {
                                    LOGGER.debug("Protected nether saved mob from tick removal: {} at {}", mobId, mobEntity.getBlockPos());
                                }
                            }

                            // Handle nether saved mode for existing mobs every 100 ticks (5 seconds)
                            if (isNetherSaved && world.getTime() % 100 == 0) {
                                handleExistingNetherMob(mobEntity, world);
                            }


                            // Handle overworld saved mode for existing mobs every 100 ticks (5 seconds)
                            if (isOverworldSaved && world.getTime() % 100 == 0) {
                                handleExistingOverworldMob(mobEntity, world);
                            }
                        }
                    }
                });

                // Handle raid disabling every 200 ticks (10 seconds)
                if (isOverworldSaved && world.getTime() % 200 == 0) {
                    disableActiveRaids(world);
                }

                // Timer-based nether mob spawning removed - now handled via percentage-based spawning on natural spawns

                // Handle natural sniffer spawning every 600 ticks (30 seconds) - only when overworld is saved
                if (isOverworldSaved && world.getRegistryKey() == World.OVERWORLD && world.getTime() % 600 == 0) {
                    spawnSniffersNaturally(world);
                }

                // Handle natural pillager spawning every 400 ticks (20 seconds) when overworld is saved
                if (isOverworldSaved && world.getRegistryKey() == World.OVERWORLD && world.getTime() % 400 == 0) {
                    spawnPillagersNaturally(world);
                }


                // Timer-based pillager spawning removed - now handled via percentage-based spawning when villagers spawn

                // Handle peaceful enderdragon spawning in the end every 200 ticks (10 seconds)
                if (isEndSaved && world.getRegistryKey() == World.END && world.getTime() % 200 == 0) {
                    spawnPeacefulEnderdragons(world);
                }
            } catch (Exception e) {
                // Ignore errors to prevent crash loops
            }

            // Additional aggressive mob neutralization check
            try {
                if (isOverworldSaved) {
                    world.iterateEntities().forEach(entity -> {
                        if (entity instanceof MobEntity mobEntity && mobEntity.isAlive() && !mobEntity.isRemoved()) {
                            // Make all mobs neutral EVERY TICK - super aggressive
                            // Remove any targets immediately
                            if (mobEntity.getTarget() != null) {
                                mobEntity.setTarget(null);
                                mobEntity.setAttacker(null);
                                LOGGER.debug("Neutralized aggressive mob: {}", Registries.ENTITY_TYPE.getId(mobEntity.getType()));
                            }

                            // Re-apply peaceful effects every 20 ticks if they don't have spared tag
                            if (world.getTime() % 20 == 0 && !mobEntity.getCommandTags().contains("spared")) {
                                applyOverworldSavedEffects(mobEntity, world);
                            }
                        }
                    });
                }
            } catch (Exception e) {
                // Ignore errors to prevent crash loops
            }
        });
    }

    private void handleNetherSavedSpawning(MobEntity mobEntity, net.minecraft.world.World world) {
        EntityType<?> entityType = mobEntity.getType();
        Identifier mobId = Registries.ENTITY_TYPE.getId(entityType);

        // Check if this is in the nether
        boolean isInNether = world.getRegistryKey() == World.NETHER;
        boolean isInOverworld = world.getRegistryKey() == World.OVERWORLD;

        // List of nether creatures that should be affected
        boolean isNetherCreature = isNetherMob(entityType);

        if (isNetherCreature) {
            if (isInNether) {
                // In nether: Allow skeletons, endermen, piglins, piglin brutes, hoglins and striders to spawn naturally
                // Block dangerous mobs like blazes, magma cubes, wither skeletons, ghasts
                if (entityType == EntityType.BLAZE ||
                    entityType == EntityType.MAGMA_CUBE ||
                    entityType == EntityType.WITHER_SKELETON ||
                    entityType == EntityType.GHAST ||
                    entityType == EntityType.ZOMBIFIED_PIGLIN ||
                    entityType == EntityType.ZOGLIN) {
                    mobEntity.discard();
                    LOGGER.debug("Removed dangerous nether creature from nether (nether saved mode): {}", mobId);
                    return;
                } else {
                    // Apply nether saved effects to allowed creatures and protect them from extinction system
                    mobEntity.addCommandTag("nether_saved_spawn"); // Protect from extinction system
                    applyNetherSavedEffects(mobEntity);
                }
            } else if (isInOverworld) {
                // In overworld: Allow nether creatures but with modifications
                // Exclude zombie variants, zoglins, and striders
                if (entityType == EntityType.ZOMBIFIED_PIGLIN ||
                    entityType == EntityType.ZOGLIN ||
                    entityType == EntityType.STRIDER) {
                    mobEntity.discard();
                    LOGGER.debug("Removed restricted nether creature from overworld: {}", mobId);
                    return;
                }

                // Apply special effects to allowed nether creatures
                applyNetherSavedEffects(mobEntity);
            }
        }
    }

    private boolean isNetherMob(EntityType<?> entityType) {
        return entityType == EntityType.BLAZE ||
               entityType == EntityType.GHAST ||
               entityType == EntityType.MAGMA_CUBE ||
               entityType == EntityType.STRIDER ||
               entityType == EntityType.HOGLIN ||
               entityType == EntityType.PIGLIN ||
               entityType == EntityType.PIGLIN_BRUTE ||
               entityType == EntityType.WITHER_SKELETON ||
               entityType == EntityType.ZOMBIFIED_PIGLIN ||
               entityType == EntityType.ZOGLIN;
    }

    private boolean isAquaticMob(EntityType<?> entityType) {
        return entityType == EntityType.COD ||
               entityType == EntityType.SALMON ||
               entityType == EntityType.TROPICAL_FISH ||
               entityType == EntityType.PUFFERFISH ||
               entityType == EntityType.SQUID ||
               entityType == EntityType.GLOW_SQUID ||
               entityType == EntityType.DOLPHIN ||
               entityType == EntityType.TURTLE ||
               entityType == EntityType.AXOLOTL ||
               entityType == EntityType.TADPOLE ||
               entityType == EntityType.FROG;
    }

    private void applyNetherSavedEffects(MobEntity mobEntity) {
        // Make all nether mobs neutral (non-aggressive)
        mobEntity.setAiDisabled(false);
        mobEntity.setTarget(null);  // Remove any current targets
        mobEntity.setAttacker(null); // Remove attacker memory

        // Apply infinite regeneration to piglins and zoglins
        EntityType<?> entityType = mobEntity.getType();
        if (entityType == EntityType.PIGLIN || entityType == EntityType.PIGLIN_BRUTE ||
            entityType == EntityType.ZOGLIN || entityType == EntityType.HOGLIN) {

            // Apply infinite regeneration effect
            mobEntity.addStatusEffect(new StatusEffectInstance(StatusEffects.REGENERATION, Integer.MAX_VALUE, 0, true, false));

            // Make piglins immune to zombification and make their weapons deal no damage
            if (mobEntity instanceof PiglinEntity piglin) {
                piglin.setImmuneToZombification(true);
                // Don't remove weapons, but make them deal no damage
                mobEntity.addStatusEffect(new StatusEffectInstance(StatusEffects.WEAKNESS, Integer.MAX_VALUE, 10, true, false));
            }
            if (mobEntity instanceof PiglinBruteEntity piglinBrute) {
                piglinBrute.setImmuneToZombification(true);
                // Don't remove weapons, but make them deal no damage
                mobEntity.addStatusEffect(new StatusEffectInstance(StatusEffects.WEAKNESS, Integer.MAX_VALUE, 10, true, false));
            }
            // For zoglins and hoglins, apply weakness to prevent damage
            if (mobEntity instanceof ZoglinEntity || mobEntity instanceof HoglinEntity) {
                mobEntity.addStatusEffect(new StatusEffectInstance(StatusEffects.WEAKNESS, Integer.MAX_VALUE, 10, true, false));
            }
        }

        // Try to make mobs neutral by disabling AI temporarily and re-enabling it
        // This will reset their targeting behavior
        boolean wasAiDisabled = mobEntity.isAiDisabled();
        if (!wasAiDisabled) {
            mobEntity.setAiDisabled(true);
            mobEntity.setAiDisabled(false);
        }

        LOGGER.info("Applied nether saved effects to: {} at {} in {}",
            Registries.ENTITY_TYPE.getId(entityType),
            mobEntity.getBlockPos(),
            mobEntity.getWorld().getRegistryKey().getValue());
    }

    private void handleOverworldSavedSpawning(MobEntity mobEntity, net.minecraft.world.World world) {
        EntityType<?> entityType = mobEntity.getType();
        Identifier mobId = Registries.ENTITY_TYPE.getId(entityType);

        // Check if this is an undead mob or iron golem
        if (isUndeadMob(entityType) || entityType == EntityType.IRON_GOLEM) {
            mobEntity.discard();
            LOGGER.debug("Removed undead/iron golem in overworld saved mode: {}", mobId);
            return;
        }

        // Apply peaceful effects to all mobs
        applyOverworldSavedEffects(mobEntity, world);

        // Handle special villager-pillager coexistence
        handleVillagerPillagerCoexistence(mobEntity, world);
    }

    private boolean isUndeadMob(EntityType<?> entityType) {
        // Only truly undead mobs that should be removed - exclude living creatures
        return entityType == EntityType.ZOMBIE ||
               entityType == EntityType.SKELETON ||
               entityType == EntityType.SLIME ||
               entityType == EntityType.PHANTOM ||
               entityType == EntityType.DROWNED ||
               entityType == EntityType.HUSK ||
               entityType == EntityType.STRAY ||
               entityType == EntityType.ZOMBIE_VILLAGER ||
               entityType == EntityType.WITHER_SKELETON ||
               entityType == EntityType.WITHER ||
               entityType == EntityType.ZOMBIE_HORSE ||
               entityType == EntityType.SKELETON_HORSE;
    }

    private void applyOverworldSavedEffects(MobEntity mobEntity, net.minecraft.world.World world) {
        EntityType<?> entityType = mobEntity.getType();

        // Aggressively neutralize the mob
        mobEntity.setTarget(null);  // Remove current target immediately
        mobEntity.setAttacker(null); // Remove attacker memory

        // Make all mobs neutral by resetting AI
        boolean wasAiDisabled = mobEntity.isAiDisabled();
        if (!wasAiDisabled) {
            mobEntity.setAiDisabled(true);
            mobEntity.setAiDisabled(false);
        }

        // Add peaceful status effects - make them completely non-threatening
        mobEntity.addStatusEffect(new StatusEffectInstance(StatusEffects.RESISTANCE, 1200, 2, true, false)); // Take less damage to encourage peaceful behavior

        // Add "spared" tag and add to peaceful team if AI changes aren't sufficient
        if (!mobEntity.getCommandTags().contains("spared")) {
            mobEntity.addCommandTag("spared");
            addMobToTeam(mobEntity, world);
        }

        // Special handling for specific mob types - keep AI and make them peaceful
        if (isIllagerMob(entityType)) {
            // Apply regeneration to illagers to represent their "redemption" but keep AI
            mobEntity.addStatusEffect(new StatusEffectInstance(StatusEffects.REGENERATION, 100, 0, true, false));
        }

        LOGGER.debug("Applied overworld saved effects to: {}", Registries.ENTITY_TYPE.getId(entityType));
    }

    private boolean isIllagerMob(EntityType<?> entityType) {
        return entityType == EntityType.PILLAGER ||
               entityType == EntityType.VINDICATOR ||
               entityType == EntityType.EVOKER ||
               entityType == EntityType.RAVAGER ||
               entityType == EntityType.VEX;
    }

    private void handleVillagerPillagerCoexistence(MobEntity mobEntity, net.minecraft.world.World world) {
        EntityType<?> entityType = mobEntity.getType();

        // Special handling for illagers - they should no longer be hostile to villagers
        if (isIllagerMob(entityType)) {
            // Remove any existing targets that are villagers
            mobEntity.setTarget(null);

            // Apply additional peaceful effects
            mobEntity.addStatusEffect(new StatusEffectInstance(StatusEffects.SPEED, 200, 0, true, false));

            LOGGER.debug("Made illager peaceful towards villagers: {}", Registries.ENTITY_TYPE.getId(entityType));
        }
    }

    private void handleExistingNetherMob(MobEntity mobEntity, net.minecraft.world.World world) {
        EntityType<?> entityType = mobEntity.getType();
        boolean isInNether = world.getRegistryKey() == World.NETHER;
        boolean isNetherCreature = isNetherMob(entityType);

        if (isNetherCreature) {
            if (isInNether) {
                // In nether: Allow skeletons, endermen, piglins, piglin brutes, hoglins and striders to exist
                // Remove only dangerous mobs like blazes, magma cubes, wither skeletons, ghasts
                if (entityType == EntityType.BLAZE ||
                    entityType == EntityType.MAGMA_CUBE ||
                    entityType == EntityType.WITHER_SKELETON ||
                    entityType == EntityType.GHAST ||
                    entityType == EntityType.ZOMBIFIED_PIGLIN ||
                    entityType == EntityType.ZOGLIN) {
                    mobEntity.discard();
                    LOGGER.info("Removed existing dangerous nether creature from nether: {}", Registries.ENTITY_TYPE.getId(entityType));
                } else {
                    // Apply nether saved effects to allowed creatures and protect them
                    if (!mobEntity.getCommandTags().contains("nether_saved_spawn")) {
                        mobEntity.addCommandTag("nether_saved_spawn"); // Protect from extinction system
                    }
                    applyNetherSavedEffects(mobEntity);
                }
            } else {
                // In overworld: Remove restricted nether creatures (zoglins, striders)
                if (entityType == EntityType.ZOGLIN || entityType == EntityType.STRIDER) {
                    mobEntity.discard();
                    LOGGER.info("Removed existing restricted nether creature from overworld: {}", Registries.ENTITY_TYPE.getId(entityType));
                } else {
                    // Apply effects to allowed nether creatures in overworld
                    applyNetherSavedEffects(mobEntity);
                }
            }
        }
    }

    private void handleExistingOverworldMob(MobEntity mobEntity, net.minecraft.world.World world) {
        EntityType<?> entityType = mobEntity.getType();

        // Remove undead mobs and iron golems
        if (isUndeadMob(entityType) || entityType == EntityType.IRON_GOLEM) {
            mobEntity.discard();
            LOGGER.debug("Removed existing undead/iron golem: {}", Registries.ENTITY_TYPE.getId(entityType));
        } else {
            // Apply peaceful effects to all other mobs
            applyOverworldSavedEffects(mobEntity, world);
        }
    }

    private void disableActiveRaids(net.minecraft.world.World world) {
        // Raids are disabled through the illager mob behavior changes
        // Illagers are now neutral and don't attack villagers
        LOGGER.debug("Raids prevented through peaceful illager behavior");
    }

    private void addMobToTeam(MobEntity mobEntity, net.minecraft.world.World world) {
        if (world instanceof ServerWorld serverWorld) {
            try {
                // Get or create the "spared" team
                var scoreboardManager = serverWorld.getServer().getScoreboard();
                var sparedTeam = scoreboardManager.getTeam("spared");

                if (sparedTeam == null) {
                    sparedTeam = scoreboardManager.addTeam("spared");
                    sparedTeam.setFriendlyFireAllowed(false); // Prevent team members from attacking each other
                    LOGGER.info("Created 'spared' team for peaceful mobs");
                }

                // Add mob to the team
                String mobName = mobEntity.getUuid().toString();
                scoreboardManager.addPlayerToTeam(mobName, sparedTeam);

                LOGGER.debug("Added mob to spared team: {}", Registries.ENTITY_TYPE.getId(mobEntity.getType()));
            } catch (Exception e) {
                LOGGER.debug("Failed to add mob to team: {}", e.getMessage());
            }
        }
    }

    private void spawnNetherMobsInOverworld(net.minecraft.world.World world) {
        if (world instanceof ServerWorld serverWorld) {
            try {
                // Get all players in the world
                var players = serverWorld.getPlayers();
                if (players.isEmpty()) {
                    LOGGER.info("No players found for nether mob spawning");
                    return;
                }

                LOGGER.info("Found {} players for nether mob spawning", players.size());

                // Spawn nether mobs around random players
                int spawnAttempts = Math.min(3, players.size());
                for (int i = 0; i < spawnAttempts; i++) {
                    var player = players.get(serverWorld.getRandom().nextInt(players.size()));
                    LOGGER.info("Attempting to spawn nether mob near player: {}", player.getName().getString());
                    spawnNetherMobNearPlayer(serverWorld, player);
                }
            } catch (Exception e) {
                LOGGER.error("Failed to spawn nether mobs: {}", e.getMessage());
            }
        }
    }

    private void spawnNetherMobNearPlayer(ServerWorld world, net.minecraft.server.network.ServerPlayerEntity player) {
        Random random = world.getRandom();

        // Don't spawn too close to player (min 16 blocks, max 48 blocks away)
        int distance = 16 + random.nextInt(32);
        double angle = random.nextDouble() * 2 * Math.PI;

        int spawnX = (int) (player.getX() + Math.cos(angle) * distance);
        int spawnZ = (int) (player.getZ() + Math.sin(angle) * distance);

        // Find a suitable Y coordinate
        BlockPos spawnPos = findSuitableSpawnPos(world, new BlockPos(spawnX, player.getBlockY(), spawnZ));
        if (spawnPos == null) return;

        // Select a random nether mob type for overworld spawning (no striders - they need lava)
        EntityType<?>[] netherMobTypes = {
            EntityType.GHAST, EntityType.HOGLIN,
            EntityType.PIGLIN, EntityType.PIGLIN_BRUTE
        };

        EntityType<?> mobType = netherMobTypes[random.nextInt(netherMobTypes.length)];

        try {
            // Create and spawn the mob
            MobEntity mob = (MobEntity) mobType.create(world);
            if (mob != null) {
                // Special handling for ghasts - spawn 20 blocks above and check safety
                double spawnY = spawnPos.getY();
                if (mobType == EntityType.GHAST) {
                    spawnY += 20; // Spawn ghasts 20 blocks higher

                    // Check if ghast spawn location is safe
                    BlockPos ghastSpawnPos = new BlockPos((int)(spawnPos.getX() + 0.5), (int)spawnY, (int)(spawnPos.getZ() + 0.5));
                    if (!isSafeGhastSpawnLocation(world, ghastSpawnPos)) {
                        LOGGER.debug("Unsafe ghast spawn location at {}, cancelling spawn", ghastSpawnPos);
                        return; // Don't spawn ghast in unsafe location
                    }
                }

                mob.refreshPositionAndAngles(spawnPos.getX() + 0.5, spawnY, spawnPos.getZ() + 0.5,
                    random.nextFloat() * 360.0F, 0.0F);

                // Add special tag to protect from extinction system
                mob.addCommandTag("nether_saved_spawn");

                // Apply nether saved effects immediately
                applyNetherSavedEffects(mob);

                // Spawn the mob
                world.spawnEntity(mob);
                LOGGER.debug("Spawned peaceful {} at {} for nether saved mode",
                    Registries.ENTITY_TYPE.getId(mobType), spawnPos);
            }
        } catch (Exception e) {
            LOGGER.debug("Failed to spawn nether mob {}: {}", mobType, e.getMessage());
        }
    }

    private BlockPos findSuitableSpawnPos(ServerWorld world, BlockPos centerPos) {
        // Try to find a suitable spawn location within a small range
        for (int attempts = 0; attempts < 10; attempts++) {
            int x = centerPos.getX() + world.getRandom().nextInt(10) - 5;
            int z = centerPos.getZ() + world.getRandom().nextInt(10) - 5;

            // Find the top solid block
            for (int y = world.getTopY(); y > world.getBottomY(); y--) {
                BlockPos pos = new BlockPos(x, y, z);
                if (world.getBlockState(pos).isOpaque() &&
                    world.getBlockState(pos.up()).isAir() &&
                    world.getBlockState(pos.up(2)).isAir()) {
                    return pos.up();
                }
            }
        }
        return null;
    }

    private boolean isSafeGhastSpawnLocation(ServerWorld world, BlockPos pos) {
        // Check if position is below Y=63
        if (pos.getY() < 63) {
            return false;
        }

        // Check for sufficient air space (ghasts are 4x4x4)
        for (int x = -2; x <= 2; x++) {
            for (int y = -2; y <= 2; y++) {
                for (int z = -2; z <= 2; z++) {
                    BlockPos checkPos = pos.add(x, y, z);
                    if (!world.getBlockState(checkPos).isAir()) {
                        return false; // Not enough air space
                    }
                }
            }
        }

        // Check for water or lava that could damage the ghast
        for (int x = -3; x <= 3; x++) {
            for (int y = -3; y <= 3; y++) {
                for (int z = -3; z <= 3; z++) {
                    BlockPos checkPos = pos.add(x, y, z);
                    var blockState = world.getBlockState(checkPos);

                    // Avoid spawning near water (damages ghasts) or lava
                    if (blockState.isOf(net.minecraft.block.Blocks.WATER) ||
                        blockState.isOf(net.minecraft.block.Blocks.LAVA)) {
                        return false;
                    }
                }
            }
        }

        // Check if there's solid ground below (within reasonable distance) to avoid spawning in void
        boolean foundGround = false;
        for (int checkY = pos.getY() - 1; checkY >= Math.max(pos.getY() - 20, world.getBottomY()); checkY--) {
            if (!world.getBlockState(new BlockPos(pos.getX(), checkY, pos.getZ())).isAir()) {
                foundGround = true;
                break;
            }
        }

        return foundGround;
    }

    private void spawnNetherMobNearLocation(ServerWorld world, BlockPos location) {
        Random random = world.getRandom();

        // Spawn 10-20 blocks away from the original spawn location
        int distance = 10 + random.nextInt(10);
        double angle = random.nextDouble() * 2 * Math.PI;

        int spawnX = (int) (location.getX() + Math.cos(angle) * distance);
        int spawnZ = (int) (location.getZ() + Math.sin(angle) * distance);

        // Find a suitable Y coordinate
        BlockPos spawnPos = findSuitableSpawnPos(world, new BlockPos(spawnX, location.getY(), spawnZ));
        if (spawnPos == null) return;

        // Select a random nether mob type for overworld spawning (no striders - they need lava)
        EntityType<?>[] netherMobTypes = {
            EntityType.GHAST, EntityType.HOGLIN,
            EntityType.PIGLIN, EntityType.PIGLIN_BRUTE
        };

        EntityType<?> mobType = netherMobTypes[random.nextInt(netherMobTypes.length)];

        try {
            // Create and spawn the mob
            MobEntity mob = (MobEntity) mobType.create(world);
            if (mob != null) {
                // Special handling for ghasts - spawn 20 blocks above and check safety
                double spawnY = spawnPos.getY();
                if (mobType == EntityType.GHAST) {
                    spawnY += 20; // Spawn ghasts 20 blocks higher

                    // Check if ghast spawn location is safe
                    BlockPos ghastSpawnPos = new BlockPos((int)(spawnPos.getX() + 0.5), (int)spawnY, (int)(spawnPos.getZ() + 0.5));
                    if (!isSafeGhastSpawnLocation(world, ghastSpawnPos)) {
                        LOGGER.debug("Unsafe ghast spawn location at {}, cancelling spawn", ghastSpawnPos);
                        return; // Don't spawn ghast in unsafe location
                    }
                }

                mob.refreshPositionAndAngles(spawnPos.getX() + 0.5, spawnY, spawnPos.getZ() + 0.5,
                    random.nextFloat() * 360.0F, 0.0F);

                // Add special tag to protect from extinction system
                mob.addCommandTag("nether_saved_spawn");

                // Apply nether saved effects immediately
                applyNetherSavedEffects(mob);

                // Spawn the mob
                world.spawnEntity(mob);
                LOGGER.debug("Spawned percentage-based {} at {} for nether saved mode",
                    Registries.ENTITY_TYPE.getId(mobType), spawnPos);
            }
        } catch (Exception e) {
            LOGGER.debug("Failed to spawn percentage-based nether mob: {}", e.getMessage());
        }
    }

    private void spawnPeacefulPillagerNearLocation(ServerWorld world, BlockPos location) {
        Random random = world.getRandom();

        // Spawn 8-16 blocks away from the villager location
        int distance = 8 + random.nextInt(8);
        double angle = random.nextDouble() * 2 * Math.PI;

        int spawnX = (int) (location.getX() + Math.cos(angle) * distance);
        int spawnZ = (int) (location.getZ() + Math.sin(angle) * distance);

        // Find a suitable Y coordinate
        BlockPos spawnPos = findSuitableSpawnPos(world, new BlockPos(spawnX, location.getY(), spawnZ));
        if (spawnPos == null) return;

        try {
            // Create and spawn the pillager
            PillagerEntity pillager = EntityType.PILLAGER.create(world);
            if (pillager != null) {
                pillager.refreshPositionAndAngles(spawnPos.getX() + 0.5, spawnPos.getY(), spawnPos.getZ() + 0.5,
                    random.nextFloat() * 360.0F, 0.0F);

                // Apply overworld saved effects immediately (peaceful, spared tag, team)
                applyOverworldSavedEffects(pillager, world);

                // Additional peaceful effects for pillagers
                pillager.addStatusEffect(new StatusEffectInstance(StatusEffects.REGENERATION, 600, 0, true, false));
                pillager.setTarget(null); // Ensure no hostility

                world.spawnEntity(pillager);
                LOGGER.debug("Spawned peaceful pillager near villager at {} for overworld saved mode", spawnPos);
            }
        } catch (Exception e) {
            LOGGER.debug("Failed to spawn peaceful pillager near villager: {}", e.getMessage());
        }
    }

    private void spawnSniffersNaturally(net.minecraft.world.World world) {
        if (world instanceof ServerWorld serverWorld) {
            try {
                // Get all players in the world
                var players = serverWorld.getPlayers();
                if (players.isEmpty()) return;

                // Limit sniffer spawning - only spawn if there are fewer than 5 sniffers in the world
                long snifferCount = 0;
                for (var entity : serverWorld.iterateEntities()) {
                    if (entity instanceof SnifferEntity) {
                        snifferCount++;
                    }
                }
                if (snifferCount >= 5) return;

                // 25% chance to spawn a sniffer
                if (serverWorld.getRandom().nextFloat() > 0.25f) return;

                // Spawn sniffer around a random player
                var player = players.get(serverWorld.getRandom().nextInt(players.size()));
                spawnSnifferNearPlayer(serverWorld, player);

            } catch (Exception e) {
                LOGGER.debug("Failed to spawn sniffers: {}", e.getMessage());
            }
        }
    }

    private void spawnSnifferNearPlayer(ServerWorld world, net.minecraft.server.network.ServerPlayerEntity player) {
        Random random = world.getRandom();

        // Spawn 20-40 blocks away from player
        int distance = 20 + random.nextInt(20);
        double angle = random.nextDouble() * 2 * Math.PI;

        int spawnX = (int) (player.getX() + Math.cos(angle) * distance);
        int spawnZ = (int) (player.getZ() + Math.sin(angle) * distance);

        // Find a suitable Y coordinate
        BlockPos spawnPos = findSuitableSpawnPos(world, new BlockPos(spawnX, player.getBlockY(), spawnZ));
        if (spawnPos == null) return;

        try {
            // Create and spawn the sniffer
            SnifferEntity sniffer = EntityType.SNIFFER.create(world);
            if (sniffer != null) {
                sniffer.refreshPositionAndAngles(spawnPos.getX() + 0.5, spawnPos.getY(), spawnPos.getZ() + 0.5,
                    random.nextFloat() * 360.0F, 0.0F);

                world.spawnEntity(sniffer);
                LOGGER.debug("Spawned natural sniffer at {} near player {}", spawnPos, player.getName().getString());
            }
        } catch (Exception e) {
            LOGGER.debug("Failed to spawn sniffer: {}", e.getMessage());
        }
    }

    private void spawnPillagersNaturally(net.minecraft.world.World world) {
        if (world instanceof ServerWorld serverWorld) {
            try {
                // Get all players in the world
                var players = serverWorld.getPlayers();
                if (players.isEmpty()) return;

                // Limit pillager spawning - only spawn if there are fewer than 15 pillagers in the world
                long pillagerCount = 0;
                for (var entity : serverWorld.iterateEntities()) {
                    if (entity instanceof PillagerEntity) {
                        pillagerCount++;
                    }
                }
                if (pillagerCount >= 15) return;

                // 40% chance to spawn a pillager
                if (serverWorld.getRandom().nextFloat() > 0.40f) return;

                // Spawn peaceful pillager around a random player
                var player = players.get(serverWorld.getRandom().nextInt(players.size()));
                spawnPeacefulPillagerNearPlayer(serverWorld, player);

            } catch (Exception e) {
                LOGGER.debug("Failed to spawn natural pillagers: {}", e.getMessage());
            }
        }
    }

    private void spawnPeacefulEnderdragons(net.minecraft.world.World world) {
        if (world instanceof ServerWorld serverWorld) {
            try {
                // Limit enderdragon spawning - only spawn if there are fewer than 8 dragons in the end
                long dragonCount = 0;
                for (var entity : serverWorld.iterateEntities()) {
                    if (entity instanceof EnderDragonEntity) {
                        dragonCount++;
                    }
                }
                if (dragonCount >= 8) return;

                // 85% chance to spawn a dragon (much higher rate)
                if (serverWorld.getRandom().nextFloat() > 0.85f) return;

                spawnPeacefulEnderdragon(serverWorld);

            } catch (Exception e) {
                LOGGER.debug("Failed to spawn peaceful enderdragons: {}", e.getMessage());
            }
        }
    }

    private void spawnPeacefulEnderdragon(ServerWorld world) {
        Random random = world.getRandom();

        // Spawn at a high altitude in the end
        int spawnX = random.nextInt(200) - 100; // -100 to 100
        int spawnY = 80 + random.nextInt(40);   // 80 to 120
        int spawnZ = random.nextInt(200) - 100; // -100 to 100

        BlockPos spawnPos = new BlockPos(spawnX, spawnY, spawnZ);

        try {
            // Create and spawn the enderdragon
            EnderDragonEntity dragon = EntityType.ENDER_DRAGON.create(world);
            if (dragon != null) {
                dragon.refreshPositionAndAngles(spawnPos.getX(), spawnPos.getY(), spawnPos.getZ(),
                    random.nextFloat() * 360.0F, 0.0F);

                // Make the dragon peaceful
                dragon.setAiDisabled(false);
                dragon.setTarget(null);

                // Apply regeneration effect to represent the dragon being "saved"
                dragon.addStatusEffect(new StatusEffectInstance(StatusEffects.REGENERATION, 200, 1, true, false));

                world.spawnEntity(dragon);
                LOGGER.debug("Spawned peaceful enderdragon at {} in end saved mode", spawnPos);
            }
        } catch (Exception e) {
            LOGGER.debug("Failed to spawn peaceful enderdragon: {}", e.getMessage());
        }
    }

    private void spawnPillagersInVillages(net.minecraft.world.World world) {
        if (world instanceof ServerWorld serverWorld) {
            try {
                // Get all players in the world
                var players = serverWorld.getPlayers();
                if (players.isEmpty()) return;

                // 20% chance to spawn a pillager near a village
                if (serverWorld.getRandom().nextFloat() > 0.20f) return;

                // Spawn peaceful pillager around a random player (representing village area)
                var player = players.get(serverWorld.getRandom().nextInt(players.size()));
                spawnPeacefulPillagerNearPlayer(serverWorld, player);

            } catch (Exception e) {
                LOGGER.debug("Failed to spawn pillagers in villages: {}", e.getMessage());
            }
        }
    }


    private void spawnPeacefulPillagerNearPlayer(ServerWorld world, net.minecraft.server.network.ServerPlayerEntity player) {
        Random random = world.getRandom();

        // Spawn 15-30 blocks away from player (village distance)
        int distance = 15 + random.nextInt(15);
        double angle = random.nextDouble() * 2 * Math.PI;

        int spawnX = (int) (player.getX() + Math.cos(angle) * distance);
        int spawnZ = (int) (player.getZ() + Math.sin(angle) * distance);

        // Find a suitable Y coordinate
        BlockPos spawnPos = findSuitableSpawnPos(world, new BlockPos(spawnX, player.getBlockY(), spawnZ));
        if (spawnPos == null) return;

        try {
            // Create and spawn the pillager
            PillagerEntity pillager = EntityType.PILLAGER.create(world);
            if (pillager != null) {
                pillager.refreshPositionAndAngles(spawnPos.getX() + 0.5, spawnPos.getY(), spawnPos.getZ() + 0.5,
                    random.nextFloat() * 360.0F, 0.0F);

                // Apply overworld saved effects immediately (peaceful, spared tag, team)
                applyOverworldSavedEffects(pillager, world);

                // Additional peaceful effects for pillagers
                pillager.addStatusEffect(new StatusEffectInstance(StatusEffects.REGENERATION, 600, 0, true, false));
                pillager.setTarget(null); // Ensure no hostility

                world.spawnEntity(pillager);
                LOGGER.debug("Spawned peaceful pillager near village at: {}", spawnPos);
            }
        } catch (Exception e) {
            LOGGER.debug("Failed to spawn peaceful pillager: {}", e.getMessage());
        }
    }

    private void registerServerEvents() {
        // Save data when server stops
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            saveModData(server);
            UndertaleScoreboard.saveScores(server);
        });

        // Load data when server starts
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            loadModData(server);
            UndertaleScoreboard.loadScores(server);
            // Initialize scoreboard based on current purge status
            setWorldPurgeScoreboard(server, allMobSpawningDisabled);
        });

        // Periodic autosave every 5 minutes
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            Timer timer = new Timer(true);
            timer.scheduleAtFixedRate(new TimerTask() {
                @Override
                public void run() {
                    if (server.isRunning()) {
                        saveModData(server);
                        UndertaleScoreboard.saveScores(server);
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
            data.isNetherSaved = isNetherSaved;
            data.isOverworldSaved = isOverworldSaved;
            data.isEndSaved = isEndSaved;
            data.allVanillaMobsExtinctNotified = allVanillaMobsExtinctNotified;
            data.allMobSpawningDisabled = allMobSpawningDisabled;

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
            isNetherSaved = data.isNetherSaved;
            isOverworldSaved = data.isOverworldSaved;
            isEndSaved = data.isEndSaved;
            allVanillaMobsExtinctNotified = data.allVanillaMobsExtinctNotified;
            allMobSpawningDisabled = data.allMobSpawningDisabled;

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
        public boolean isNetherSaved = false;
        public boolean isOverworldSaved = false;
        public boolean isEndSaved = false;
        public boolean allVanillaMobsExtinctNotified = false;
        public boolean allMobSpawningDisabled = false;

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
            if (nbt.contains("isNetherSaved")) {
                data.isNetherSaved = nbt.getBoolean("isNetherSaved");
            }
            if (nbt.contains("isOverworldSaved")) {
                data.isOverworldSaved = nbt.getBoolean("isOverworldSaved");
            }
            if (nbt.contains("isEndSaved")) {
                data.isEndSaved = nbt.getBoolean("isEndSaved");
            }
            if (nbt.contains("allVanillaMobsExtinctNotified")) {
                data.allVanillaMobsExtinctNotified = nbt.getBoolean("allVanillaMobsExtinctNotified");
            }
            if (nbt.contains("allMobSpawningDisabled")) {
                data.allMobSpawningDisabled = nbt.getBoolean("allMobSpawningDisabled");
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
            nbt.putBoolean("isNetherSaved", isNetherSaved);
            nbt.putBoolean("isOverworldSaved", isOverworldSaved);
            nbt.putBoolean("isEndSaved", isEndSaved);
            nbt.putBoolean("allVanillaMobsExtinctNotified", allVanillaMobsExtinctNotified);
            nbt.putBoolean("allMobSpawningDisabled", allMobSpawningDisabled);

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

    private static void checkAllVanillaMobsExtinct(ServerPlayerEntity player) {
        if (allVanillaMobsExtinctNotified) {
            return; // Already notified
        }

        // Check if all vanilla mobs are extinct
        boolean allExtinct = true;
        int extinctCount = 0;
        final int totalVanillaMobs = VANILLA_MOBS.size();

        for (Identifier vanillaMob : VANILLA_MOBS) {
            if (extinctMobs.contains(vanillaMob) || purgedMobs.contains(vanillaMob)) {
                extinctCount++;
            } else {
                allExtinct = false;
            }
        }

        final int finalExtinctCount = extinctCount;
        // Calculate percentage of vanilla mobs extinct
        int percentageExtinct = (extinctCount * 100) / totalVanillaMobs;

        // Send periodic progress updates
        if (percentageExtinct >= 25 && percentageExtinct % 25 == 0 && config.isChatMessagesEnabled()) {
            String message = switch (percentageExtinct) {
                case 25 -> "§6¼ of all vanilla monsters have been exterminated... The world grows quieter.";
                case 50 -> "§c½ of all vanilla monsters are extinct... Nature is out of balance.";
                case 75 -> "§4¾ of all vanilla monsters have vanished... The apocalypse approaches.";
                default -> "";
            };
            if (!message.isEmpty()) {
                player.sendMessage(Text.literal(message + " (" + finalExtinctCount + "/" + totalVanillaMobs + ")"), false);
            }
        }

        // If all vanilla mobs are extinct, send special notification
        if (allExtinct) {
            allVanillaMobsExtinctNotified = true;

            if (config.isChatMessagesEnabled()) {
                // Epic extinction completion message
                player.sendMessage(Text.literal(""), false);
                player.sendMessage(Text.literal("§4§l=============================================="), false);
                player.sendMessage(Text.literal("§4§l        🗭 TOTAL VANILLA EXTINCTION 🗭"), false);
                player.sendMessage(Text.literal("§4§l=============================================="), false);
                player.sendMessage(Text.literal(""), false);
                player.sendMessage(Text.literal("§c💀 ALL " + totalVanillaMobs + " VANILLA MONSTER SPECIES ARE EXTINCT! 💀"), false);
                player.sendMessage(Text.literal(""), false);
                player.sendMessage(Text.literal("§7The world is now completely devoid of vanilla monsters."), false);
                player.sendMessage(Text.literal("§7No zombies. No skeletons. No creepers. Nothing."), false);
                player.sendMessage(Text.literal("§7You have achieved what no player has done before:"), false);
                player.sendMessage(Text.literal("§4§lCOMPLETE MONSTER GENOCIDE"), false);
                player.sendMessage(Text.literal(""), false);
                player.sendMessage(Text.literal("§6The silence is deafening... but perhaps there's still hope"), false);
                player.sendMessage(Text.literal("§6for salvation through the power of mercy and love."), false);
                player.sendMessage(Text.literal(""), false);
                player.sendMessage(Text.literal("§e§l✦ ACHIEVEMENT UNLOCKED: Monster Extinction Master ✦"), false);
                player.sendMessage(Text.literal("§4§l=============================================="), false);
            }

            // Grant special advancement or effect
            grantVanillaExtinctionAdvancement(player);

            LOGGER.info("Player {} has made ALL vanilla mobs extinct! Total: {}/{}",
                    player.getName().getString(), finalExtinctCount, totalVanillaMobs);
        }
    }

    private static void grantVanillaExtinctionAdvancement(ServerPlayerEntity player) {
        try {
            // Grant a special status effect to mark this achievement
            player.addStatusEffect(new StatusEffectInstance(StatusEffects.HERO_OF_THE_VILLAGE, 72000, 2, true, true)); // 1 hour

            // Play epic sound effects
            player.playSound(net.minecraft.sound.SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, 1.0f, 0.5f);
            player.playSound(net.minecraft.sound.SoundEvents.ENTITY_ENDER_DRAGON_DEATH, 0.5f, 0.5f);
            player.playSound(net.minecraft.sound.SoundEvents.ENTITY_WITHER_DEATH, 0.3f, 0.7f);

            LOGGER.info("Granted vanilla extinction master effects to {}", player.getName().getString());

        } catch (Exception e) {
            LOGGER.error("Failed to grant vanilla extinction advancement effects", e);
        }
    }

    // Add command to check vanilla extinction progress
    public static void addVanillaExtinctionCommand(ServerCommandSource source) {
        int extinctCount = 0;
        final int totalVanillaMobs = VANILLA_MOBS.size();

        for (Identifier vanillaMob : VANILLA_MOBS) {
            if (extinctMobs.contains(vanillaMob) || purgedMobs.contains(vanillaMob)) {
                extinctCount++;
            }
        }

        final int finalExtinctCount = extinctCount;
        final int percentageExtinct = (extinctCount * 100) / totalVanillaMobs;

        source.sendFeedback(() -> Text.literal("§6=== VANILLA MOB EXTINCTION PROGRESS ==="), false);
        source.sendFeedback(() -> Text.literal("§7Extinct Species: §c" + finalExtinctCount + "§7/§f" + totalVanillaMobs + " §7(" + percentageExtinct + "%)"), false);

        if (extinctCount == totalVanillaMobs) {
            source.sendFeedback(() -> Text.literal("§4§l🗭 ALL VANILLA MOBS ARE EXTINCT! 🗭"), false);
        } else {
            final int remaining = totalVanillaMobs - extinctCount;
            source.sendFeedback(() -> Text.literal("§7Remaining: §a" + remaining + " §7species still exist"), false);

            // Show some of the remaining mobs
            if (remaining <= 10) {
                source.sendFeedback(() -> Text.literal("§7Still alive:"), false);
                for (Identifier vanillaMob : VANILLA_MOBS) {
                    if (!extinctMobs.contains(vanillaMob) && !purgedMobs.contains(vanillaMob)) {
                        final String mobName = vanillaMob.getPath().replace("_", " ");
                        source.sendFeedback(() -> Text.literal("§a- " + mobName), false);
                    }
                }
            }
        }
    }

    private static void executeFullVanillaExtinction(ServerPlayerEntity player, ServerCommandSource source) {
        source.sendFeedback(() -> Text.literal("§4§l=== INITIATING FULL VANILLA EXTINCTION ==="), false);
        source.sendFeedback(() -> Text.literal("§cExecuting complete genocide protocol..."), false);

        MinecraftServer server = player.getServer();
        if (server == null) {
            source.sendFeedback(() -> Text.literal("§cError: Server not available"), false);
            return;
        }

        int totalExterminated = 0;
        int totalKilled = 0;

        // Add all vanilla mobs to extinct and purged lists (like /exterminate command)
        for (Identifier vanillaMob : VANILLA_MOBS) {
            if (!extinctMobs.contains(vanillaMob) && !purgedMobs.contains(vanillaMob)) {
                extinctMobs.add(vanillaMob);
                purgedMobs.add(vanillaMob);
                killCounts.put(vanillaMob, EXTINCTION_THRESHOLD); // Set kill count to threshold
                totalExterminated++;
            }
        }

        // Kill all existing vanilla mobs in all loaded worlds (like /exterminate command)
        for (ServerWorld serverWorld : server.getWorlds()) {
            for (Identifier vanillaMob : VANILLA_MOBS) {
                // Get the EntityType from the Identifier
                EntityType<?> entityType = Registries.ENTITY_TYPE.get(vanillaMob);
                if (entityType != null) {
                    // Kill all existing mobs of this type (same logic as /exterminate)
                    for (Object entityObj : serverWorld.getEntitiesByType(entityType, livingEntity -> livingEntity.isAlive())) {
                        if (entityObj instanceof MobEntity mobEntity) {
                            // Don't kill mobs that were spawned as part of nether/overworld saved system
                            if (!mobEntity.getCommandTags().contains("nether_saved_spawn") &&
                                !mobEntity.getCommandTags().contains("spared")) {
                                mobEntity.damage(serverWorld.getDamageSources().genericKill(), Float.MAX_VALUE);
                                totalKilled++;
                            }
                        }
                    }
                }
            }
        }

        final int finalTotalExterminated = totalExterminated;
        final int finalTotalKilled = totalKilled;
        final int totalVanillaMobs = VANILLA_MOBS.size();

        // Send completion messages
        source.sendFeedback(() -> Text.literal("§4💀 FULL VANILLA EXTINCTION COMPLETE! 💀"), false);
        source.sendFeedback(() -> Text.literal("§cSpecies marked extinct: §f" + finalTotalExterminated + "§c/§f" + totalVanillaMobs), false);
        source.sendFeedback(() -> Text.literal("§cLiving mobs eliminated: §f" + finalTotalKilled), false);

        // Enable complete mob spawning disable - no mobs can spawn anywhere
        allMobSpawningDisabled = true;

        // Set scoreboard objective for world purge status
        setWorldPurgeScoreboard(server, true);

        // Force the vanilla extinction check and notification
        allVanillaMobsExtinctNotified = false; // Reset so we can trigger the notification
        checkAllVanillaMobsExtinct(player);

        // Additional feedback about complete spawning disable
        source.sendFeedback(() -> Text.literal("§4§lWARNING: ALL mob spawning has been permanently disabled!"), false);
        source.sendFeedback(() -> Text.literal("§7The world is now completely lifeless - no mobs will spawn anywhere."), false);
        source.sendFeedback(() -> Text.literal("§7Scoreboard: §eworldpurged = 1"), false);

        LOGGER.info("Player {} executed full vanilla extinction: {} species extinct, {} mobs killed",
                   player.getName().getString(), finalTotalExterminated, finalTotalKilled);
    }

    private static void setWorldPurgeScoreboard(MinecraftServer server, boolean purged) {
        try {
            var scoreboard = server.getScoreboard();

            // Create or get the "worldpurged" objective
            var objective = scoreboard.getNullableObjective("worldpurged");
            if (objective == null) {
                objective = scoreboard.addObjective("worldpurged",
                    net.minecraft.scoreboard.ScoreboardCriterion.DUMMY,
                    Text.literal("World Purge Status"),
                    net.minecraft.scoreboard.ScoreboardCriterion.RenderType.INTEGER);
                LOGGER.info("Created worldpurged scoreboard objective");
            }

            // Set the score for a global entity (the server itself)
            String globalEntity = "#global";
            var score = scoreboard.getPlayerScore(globalEntity, objective);
            score.setScore(purged ? 1 : 0);

            LOGGER.info("Set worldpurged scoreboard to {} for global tracking", purged ? 1 : 0);

        } catch (Exception e) {
            LOGGER.error("Failed to set worldpurged scoreboard: {}", e.getMessage());
        }
    }

    // Utility method to check world purge status via scoreboard
    public static boolean isWorldPurged(MinecraftServer server) {
        try {
            var scoreboard = server.getScoreboard();
            var objective = scoreboard.getNullableObjective("worldpurged");
            if (objective == null) {
                return false;
            }

            String globalEntity = "#global";
            var score = scoreboard.getPlayerScore(globalEntity, objective);
            return score.getScore() == 1;

        } catch (Exception e) {
            LOGGER.debug("Failed to check worldpurged scoreboard: {}", e.getMessage());
            return false;
        }
    }
}