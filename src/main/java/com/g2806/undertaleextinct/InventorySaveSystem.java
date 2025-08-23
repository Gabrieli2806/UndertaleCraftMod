package com.g2806.undertaleextinct;

import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.registry.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.world.PersistentState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Smart inventory save/restore system that handles item duplication prevention
 * and proper item merging when restoring after death.
 */
public class InventorySaveSystem {
    private static final Logger LOGGER = LoggerFactory.getLogger("InventorySaveSystem");

    // Track saved inventories and item tracking
    private static final Map<UUID, SavedInventory> savedInventories = new ConcurrentHashMap<>();
    private static final Map<UUID, Map<String, Integer>> lastKnownItemCounts = new ConcurrentHashMap<>();

    public void initialize() {
        LOGGER.info("Initializing Inventory Save System...");
        registerCommands();
        registerEvents();
        registerServerEvents();
    }

    private void registerCommands() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            // Command to save current inventory
            dispatcher.register(CommandManager.literal("saveinventory")
                    .executes(context -> {
                        if (context.getSource().getEntity() instanceof ServerPlayerEntity player) {
                            savePlayerInventory(player);
                            context.getSource().sendFeedback(() ->
                                    Text.literal("§6✓ Inventory saved! You'll respawn with these items on death."), false);
                            return 1;
                        }
                        return 0;
                    }));

            // Command to clear saved inventory
            dispatcher.register(CommandManager.literal("clearsave")
                    .executes(context -> {
                        if (context.getSource().getEntity() instanceof ServerPlayerEntity player) {
                            savedInventories.remove(player.getUuid());
                            lastKnownItemCounts.remove(player.getUuid());
                            context.getSource().sendFeedback(() ->
                                    Text.literal("§cSaved inventory cleared."), false);
                            return 1;
                        }
                        return 0;
                    }));

            // Command to view saved inventory info
            dispatcher.register(CommandManager.literal("viewsave")
                    .executes(context -> {
                        if (context.getSource().getEntity() instanceof ServerPlayerEntity player) {
                            SavedInventory saved = savedInventories.get(player.getUuid());
                            if (saved != null) {
                                context.getSource().sendFeedback(() ->
                                        Text.literal("§6Saved Inventory: " + saved.getTotalItems() + " items, saved " +
                                                getTimeDifference(saved.saveTime) + " ago"), false);

                                // Show top 5 item types
                                Map<String, Integer> itemCounts = saved.getItemCounts();
                                itemCounts.entrySet().stream()
                                        .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                                        .limit(5)
                                        .forEach(entry -> {
                                            context.getSource().sendFeedback(() ->
                                                    Text.literal("§7- " + entry.getKey() + ": " + entry.getValue()), false);
                                        });
                            } else {
                                context.getSource().sendFeedback(() ->
                                        Text.literal("§cNo saved inventory found."), false);
                            }
                            return 1;
                        }
                        return 0;
                    }));

            // Command to manually restore inventory (admin/testing)
            dispatcher.register(CommandManager.literal("restoreinventory")
                    .requires(source -> source.hasPermissionLevel(2))
                    .executes(context -> {
                        if (context.getSource().getEntity() instanceof ServerPlayerEntity player) {
                            if (restorePlayerInventory(player)) {
                                context.getSource().sendFeedback(() ->
                                        Text.literal("§aInventory restored successfully!"), false);
                            } else {
                                context.getSource().sendFeedback(() ->
                                        Text.literal("§cNo saved inventory to restore."), false);
                            }
                            return 1;
                        }
                        return 0;
                    }));
        });
    }

    private void registerEvents() {
        // Save inventory before death and restore after respawn
        ServerPlayerEvents.AFTER_RESPAWN.register((oldPlayer, newPlayer, alive) -> {
            if (!alive) { // Player died and respawned
                LOGGER.info("Player {} died and respawned, attempting inventory restore", newPlayer.getName().getString());

                // Small delay to ensure player is fully loaded
                newPlayer.getServer().execute(() -> {
                    if (restorePlayerInventory(newPlayer)) {
                        newPlayer.sendMessage(Text.literal("§6Your saved inventory has been restored!"), false);
                    }
                });
            }
        });

        // Track item changes periodically to detect duplication attempts
        // This runs when player joins to initialize tracking
        ServerPlayerEvents.AFTER_RESPAWN.register((oldPlayer, newPlayer, alive) -> {
            if (alive) { // Player joined/respawned normally
                updateItemTracking(newPlayer);
            }
        });
    }

    private void registerServerEvents() {
        // Save data when server stops
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            saveAllData(server);
        });

        // Load data when server starts
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            loadAllData(server);
        });
    }

    private void savePlayerInventory(ServerPlayerEntity player) {
        PlayerInventory inventory = player.getInventory();
        SavedInventory savedInv = new SavedInventory();

        // Save main inventory
        for (int i = 0; i < inventory.main.size(); i++) {
            ItemStack stack = inventory.main.get(i);
            if (!stack.isEmpty()) {
                savedInv.mainInventory.put(i, stack.copy());
            }
        }

        // Save armor
        for (int i = 0; i < inventory.armor.size(); i++) {
            ItemStack stack = inventory.armor.get(i);
            if (!stack.isEmpty()) {
                savedInv.armorInventory.put(i, stack.copy());
            }
        }

        // Save offhand
        for (int i = 0; i < inventory.offHand.size(); i++) {
            ItemStack stack = inventory.offHand.get(i);
            if (!stack.isEmpty()) {
                savedInv.offhandInventory.put(i, stack.copy());
            }
        }

        savedInv.saveTime = System.currentTimeMillis();
        savedInventories.put(player.getUuid(), savedInv);

        // Update item tracking
        updateItemTracking(player);

        LOGGER.info("Saved inventory for player {} with {} items",
                player.getName().getString(), savedInv.getTotalItems());
    }

    private boolean restorePlayerInventory(ServerPlayerEntity player) {
        SavedInventory saved = savedInventories.get(player.getUuid());
        if (saved == null) return false;

        PlayerInventory inventory = player.getInventory();
        Map<String, Integer> currentItems = getCurrentItemCounts(player);
        Map<String, Integer> savedItems = saved.getItemCounts();
        Map<String, Integer> lastKnownItems = lastKnownItemCounts.getOrDefault(player.getUuid(), new HashMap<>());

        // Clear current inventory
        inventory.clear();

        // Smart restoration logic
        Map<String, Integer> itemsToRestore = calculateItemsToRestore(savedItems, currentItems, lastKnownItems);

        // Restore main inventory with smart merging
        for (Map.Entry<Integer, ItemStack> entry : saved.mainInventory.entrySet()) {
            ItemStack originalStack = entry.getValue().copy();
            String itemKey = getItemKey(originalStack);

            Integer restoreAmount = itemsToRestore.get(itemKey);
            if (restoreAmount != null && restoreAmount > 0) {
                // Calculate how much of this stack to restore
                int stackAmount = Math.min(originalStack.getCount(), restoreAmount);
                if (stackAmount > 0) {
                    ItemStack restoreStack = originalStack.copy();
                    restoreStack.setCount(stackAmount);
                    inventory.main.set(entry.getKey(), restoreStack);

                    // Reduce the amount we still need to restore
                    itemsToRestore.put(itemKey, restoreAmount - stackAmount);
                }
            }
        }

        // Restore armor (always restore armor completely)
        for (Map.Entry<Integer, ItemStack> entry : saved.armorInventory.entrySet()) {
            inventory.armor.set(entry.getKey(), entry.getValue().copy());
        }

        // Restore offhand (always restore offhand completely)
        for (Map.Entry<Integer, ItemStack> entry : saved.offhandInventory.entrySet()) {
            inventory.offHand.set(entry.getKey(), entry.getValue().copy());
        }

        // Update item tracking after restoration
        updateItemTracking(player);

        LOGGER.info("Restored inventory for player {} using smart merging", player.getName().getString());
        return true;
    }

    private Map<String, Integer> calculateItemsToRestore(Map<String, Integer> savedItems,
                                                         Map<String, Integer> currentItems,
                                                         Map<String, Integer> lastKnownItems) {
        Map<String, Integer> itemsToRestore = new HashMap<>();

        for (Map.Entry<String, Integer> savedEntry : savedItems.entrySet()) {
            String itemType = savedEntry.getKey();
            int savedAmount = savedEntry.getValue();
            int currentAmount = currentItems.getOrDefault(itemType, 0);
            int lastKnownAmount = lastKnownItems.getOrDefault(itemType, 0);

            // Calculate what should be restored
            int itemsGainedSinceLastSave = Math.max(0, currentAmount - lastKnownAmount);

            // Strategy: Restore saved amount + any items gained since save
            // This prevents both duplication and loss of newly acquired items
            int restoreAmount = savedAmount + itemsGainedSinceLastSave;

            if (restoreAmount > 0) {
                itemsToRestore.put(itemType, restoreAmount);
            }

            LOGGER.debug("Item {}: saved={}, current={}, lastKnown={}, restoring={}",
                    itemType, savedAmount, currentAmount, lastKnownAmount, restoreAmount);
        }

        return itemsToRestore;
    }

    private void updateItemTracking(ServerPlayerEntity player) {
        Map<String, Integer> currentItems = getCurrentItemCounts(player);
        lastKnownItemCounts.put(player.getUuid(), currentItems);
    }

    private Map<String, Integer> getCurrentItemCounts(ServerPlayerEntity player) {
        Map<String, Integer> itemCounts = new HashMap<>();
        PlayerInventory inventory = player.getInventory();

        // Count main inventory items
        for (ItemStack stack : inventory.main) {
            if (!stack.isEmpty()) {
                String key = getItemKey(stack);
                itemCounts.put(key, itemCounts.getOrDefault(key, 0) + stack.getCount());
            }
        }

        return itemCounts;
    }

    private String getItemKey(ItemStack stack) {
        Identifier id = Registries.ITEM.getId(stack.getItem());
        // Include NBT hash for items with different data (enchantments, etc.)
        if (stack.hasNbt()) {
            return id.toString() + "#" + stack.getNbt().hashCode();
        }
        return id.toString();
    }

    private String getTimeDifference(long saveTime) {
        long diff = System.currentTimeMillis() - saveTime;
        long seconds = diff / 1000;
        long minutes = seconds / 60;
        long hours = minutes / 60;

        if (hours > 0) return hours + " hour(s)";
        if (minutes > 0) return minutes + " minute(s)";
        return seconds + " second(s)";
    }

    private void saveAllData(MinecraftServer server) {
        try {
            InventoryData data = server.getOverworld().getPersistentStateManager()
                    .getOrCreate(InventoryData::fromNbt, InventoryData::new, "inventory_save_data");

            data.savedInventories.clear();
            data.savedInventories.putAll(savedInventories);

            data.lastKnownItemCounts.clear();
            data.lastKnownItemCounts.putAll(lastKnownItemCounts);

            data.markDirty();
            LOGGER.info("Inventory save data saved successfully");
        } catch (Exception e) {
            LOGGER.error("Failed to save inventory data", e);
        }
    }

    private void loadAllData(MinecraftServer server) {
        try {
            InventoryData data = server.getOverworld().getPersistentStateManager()
                    .getOrCreate(InventoryData::fromNbt, InventoryData::new, "inventory_save_data");

            savedInventories.clear();
            savedInventories.putAll(data.savedInventories);

            lastKnownItemCounts.clear();
            lastKnownItemCounts.putAll(data.lastKnownItemCounts);

            LOGGER.info("Inventory save data loaded: {} saved inventories", savedInventories.size());
        } catch (Exception e) {
            LOGGER.error("Failed to load inventory data", e);
        }
    }

    // Data classes
    public static class SavedInventory {
        public final Map<Integer, ItemStack> mainInventory = new HashMap<>();
        public final Map<Integer, ItemStack> armorInventory = new HashMap<>();
        public final Map<Integer, ItemStack> offhandInventory = new HashMap<>();
        public long saveTime = System.currentTimeMillis();

        public int getTotalItems() {
            int total = 0;
            for (ItemStack stack : mainInventory.values()) {
                total += stack.getCount();
            }
            for (ItemStack stack : armorInventory.values()) {
                total += stack.getCount();
            }
            for (ItemStack stack : offhandInventory.values()) {
                total += stack.getCount();
            }
            return total;
        }

        public Map<String, Integer> getItemCounts() {
            Map<String, Integer> counts = new HashMap<>();

            for (ItemStack stack : mainInventory.values()) {
                String key = getItemKey(stack);
                counts.put(key, counts.getOrDefault(key, 0) + stack.getCount());
            }

            return counts;
        }

        private String getItemKey(ItemStack stack) {
            Identifier id = Registries.ITEM.getId(stack.getItem());
            if (stack.hasNbt()) {
                return id.toString() + "#" + stack.getNbt().hashCode();
            }
            return id.toString();
        }
    }

    // Persistent data storage
    public static class InventoryData extends PersistentState {
        public final Map<UUID, SavedInventory> savedInventories = new ConcurrentHashMap<>();
        public final Map<UUID, Map<String, Integer>> lastKnownItemCounts = new ConcurrentHashMap<>();

        public static InventoryData fromNbt(NbtCompound nbt) {
            InventoryData data = new InventoryData();

            // Load saved inventories
            if (nbt.contains("savedInventories")) {
                NbtCompound savedInvs = nbt.getCompound("savedInventories");
                for (String playerUuid : savedInvs.getKeys()) {
                    try {
                        UUID uuid = UUID.fromString(playerUuid);
                        NbtCompound playerData = savedInvs.getCompound(playerUuid);
                        SavedInventory savedInv = loadSavedInventory(playerData);
                        data.savedInventories.put(uuid, savedInv);
                    } catch (Exception e) {
                        LOGGER.warn("Failed to load saved inventory for player {}: {}", playerUuid, e.getMessage());
                    }
                }
            }

            // Load item tracking data
            if (nbt.contains("itemTracking")) {
                NbtCompound itemTracking = nbt.getCompound("itemTracking");
                for (String playerUuid : itemTracking.getKeys()) {
                    try {
                        UUID uuid = UUID.fromString(playerUuid);
                        NbtCompound itemCounts = itemTracking.getCompound(playerUuid);
                        Map<String, Integer> counts = new HashMap<>();

                        for (String itemKey : itemCounts.getKeys()) {
                            counts.put(itemKey, itemCounts.getInt(itemKey));
                        }

                        data.lastKnownItemCounts.put(uuid, counts);
                    } catch (Exception e) {
                        LOGGER.warn("Failed to load item tracking for player {}: {}", playerUuid, e.getMessage());
                    }
                }
            }

            return data;
        }

        @Override
        public NbtCompound writeNbt(NbtCompound nbt) {
            // Save inventories
            NbtCompound savedInvs = new NbtCompound();
            for (Map.Entry<UUID, SavedInventory> entry : savedInventories.entrySet()) {
                NbtCompound playerData = saveSavedInventory(entry.getValue());
                savedInvs.put(entry.getKey().toString(), playerData);
            }
            nbt.put("savedInventories", savedInvs);

            // Save item tracking
            NbtCompound itemTracking = new NbtCompound();
            for (Map.Entry<UUID, Map<String, Integer>> entry : lastKnownItemCounts.entrySet()) {
                NbtCompound itemCounts = new NbtCompound();
                for (Map.Entry<String, Integer> itemEntry : entry.getValue().entrySet()) {
                    itemCounts.putInt(itemEntry.getKey(), itemEntry.getValue());
                }
                itemTracking.put(entry.getKey().toString(), itemCounts);
            }
            nbt.put("itemTracking", itemTracking);

            return nbt;
        }

        private static SavedInventory loadSavedInventory(NbtCompound nbt) {
            SavedInventory savedInv = new SavedInventory();

            if (nbt.contains("saveTime")) {
                savedInv.saveTime = nbt.getLong("saveTime");
            }

            // Load main inventory
            if (nbt.contains("mainInventory")) {
                NbtCompound mainInv = nbt.getCompound("mainInventory");
                for (String slot : mainInv.getKeys()) {
                    int slotIndex = Integer.parseInt(slot);
                    ItemStack stack = ItemStack.fromNbt(mainInv.getCompound(slot));
                    if (!stack.isEmpty()) {
                        savedInv.mainInventory.put(slotIndex, stack);
                    }
                }
            }

            // Load armor
            if (nbt.contains("armorInventory")) {
                NbtCompound armorInv = nbt.getCompound("armorInventory");
                for (String slot : armorInv.getKeys()) {
                    int slotIndex = Integer.parseInt(slot);
                    ItemStack stack = ItemStack.fromNbt(armorInv.getCompound(slot));
                    if (!stack.isEmpty()) {
                        savedInv.armorInventory.put(slotIndex, stack);
                    }
                }
            }

            // Load offhand
            if (nbt.contains("offhandInventory")) {
                NbtCompound offhandInv = nbt.getCompound("offhandInventory");
                for (String slot : offhandInv.getKeys()) {
                    int slotIndex = Integer.parseInt(slot);
                    ItemStack stack = ItemStack.fromNbt(offhandInv.getCompound(slot));
                    if (!stack.isEmpty()) {
                        savedInv.offhandInventory.put(slotIndex, stack);
                    }
                }
            }

            return savedInv;
        }

        private static NbtCompound saveSavedInventory(SavedInventory savedInv) {
            NbtCompound nbt = new NbtCompound();
            nbt.putLong("saveTime", savedInv.saveTime);

            // Save main inventory
            NbtCompound mainInv = new NbtCompound();
            for (Map.Entry<Integer, ItemStack> entry : savedInv.mainInventory.entrySet()) {
                NbtCompound stackNbt = new NbtCompound();
                entry.getValue().writeNbt(stackNbt);
                mainInv.put(entry.getKey().toString(), stackNbt);
            }
            nbt.put("mainInventory", mainInv);

            // Save armor
            NbtCompound armorInv = new NbtCompound();
            for (Map.Entry<Integer, ItemStack> entry : savedInv.armorInventory.entrySet()) {
                NbtCompound stackNbt = new NbtCompound();
                entry.getValue().writeNbt(stackNbt);
                armorInv.put(entry.getKey().toString(), stackNbt);
            }
            nbt.put("armorInventory", armorInv);

            // Save offhand
            NbtCompound offhandInv = new NbtCompound();
            for (Map.Entry<Integer, ItemStack> entry : savedInv.offhandInventory.entrySet()) {
                NbtCompound stackNbt = new NbtCompound();
                entry.getValue().writeNbt(stackNbt);
                offhandInv.put(entry.getKey().toString(), stackNbt);
            }
            nbt.put("offhandInventory", offhandInv);

            return nbt;
        }
    }
}