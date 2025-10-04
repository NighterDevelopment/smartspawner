package github.nighter.smartspawner.spawner.properties;

import github.nighter.smartspawner.SmartSpawner;
import github.nighter.smartspawner.commands.hologram.SpawnerHologram;
import github.nighter.smartspawner.spawner.loot.EntityLootConfig;
import github.nighter.smartspawner.spawner.loot.EntityLootRegistry;
import github.nighter.smartspawner.spawner.loot.LootItem;
import github.nighter.smartspawner.spawner.sell.SellResult;
import lombok.Getter;
import lombok.Setter;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.spawner.SpawnRule;
import org.bukkit.block.spawner.SpawnerEntry;
import org.bukkit.entity.EntitySnapshot;
import org.bukkit.entity.EntityType;
import org.bukkit.inventory.ItemStack;
import org.bukkit.spawner.Spawner;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;

/**
 * SpawnerData represents a VIRTUAL spawner with enhanced functionality.
 * 
 * <h2>Virtual Spawner vs Block-Based Spawner</h2>
 * <p>
 * This class implements {@link org.bukkit.spawner.Spawner} but represents a <b>virtual</b> spawner,
 * not the physical spawner block itself. Key differences:
 * </p>
 * 
 * <h3>Virtual Spawner (SpawnerData):</h3>
 * <ul>
 *   <li>Stores data independently from the physical block</li>
 *   <li>Generates loot items instead of spawning entities</li>
 *   <li>Supports stackable spawners (multiple spawners in one block)</li>
 *   <li>Has virtual inventory for loot storage</li>
 *   <li>Can be enhanced with experience, upgrades, and custom features</li>
 * </ul>
 * 
 * <h3>Block-Based Spawner (org.bukkit.spawner.Spawner):</h3>
 * <ul>
 *   <li>Data tied directly to the spawner block</li>
 *   <li>Spawns actual entities in the world</li>
 *   <li>One spawner per block</li>
 *   <li>Standard Minecraft spawner behavior</li>
 * </ul>
 * 
 * <h2>Spawner Interface Implementation</h2>
 * <p>
 * While this class implements {@link org.bukkit.spawner.Spawner} for API compatibility,
 * some methods have special behavior for virtual spawners:
 * </p>
 * <ul>
 *   <li>{@link #getSpawnRange()} - Returns default value, not used for loot generation</li>
 *   <li>{@link #setSpawnRange(int)} - No-op, spawn range not applicable to virtual spawners</li>
 *   <li>{@link #getDelay()} - Returns spawn delay in ticks (converted from internal milliseconds)</li>
 *   <li>{@link #getRequiredPlayerRange()} - Distance required for activation (formerly spawnerRange)</li>
 *   <li>{@link #isActivated()} - True when players are in range (inverse of spawnerStop)</li>
 * </ul>
 * 
 * <h2>Activation System</h2>
 * <p>
 * Spawner activation is managed by {@link github.nighter.smartspawner.spawner.lootgen.SpawnerRangeChecker}:
 * </p>
 * <ul>
 *   <li>Checks if players are within {@link #getRequiredPlayerRange()}</li>
 *   <li>Sets activation state via {@link #isActivated()}</li>
 *   <li>When activated, loot generation tasks are started</li>
 *   <li>When deactivated, tasks are stopped to save resources</li>
 * </ul>
 * 
 * <h2>Migration Notes</h2>
 * <p>
 * For backward compatibility, deprecated methods are provided:
 * </p>
 * <ul>
 *   <li>{@code getSpawnerRange()} → use {@link #getRequiredPlayerRange()}</li>
 *   <li>{@code getSpawnerStop()} → use {@code !isActivated()}</li>
 * </ul>
 * 
 * @see org.bukkit.spawner.Spawner
 * @see github.nighter.smartspawner.spawner.lootgen.SpawnerRangeChecker
 * @see github.nighter.smartspawner.spawner.lootgen.SpawnerLootGenerator
 */
public class SpawnerData implements Spawner {
    @Getter
    private final SmartSpawner plugin;
    private final EntityLootRegistry lootRegistry;

    @Getter
    private final String spawnerId;
    @Getter
    private final Location spawnerLocation;
    @Getter
    private final ReentrantLock lock = new ReentrantLock();

    // Base values from config (immutable after load)
    private int baseMaxStoredExp;
    private int baseMaxStoragePages;
    private int baseMinMobs;
    private int baseMaxMobs;

    @Getter
    private Integer spawnerExp;
    @Getter @Setter
    private Boolean spawnerActive;
    @Getter @Setter
    private Integer spawnerRange;
    @Getter @Setter
    private Boolean spawnerStop;
    @Getter @Setter
    private Boolean isAtCapacity;
    @Getter @Setter
    private Long lastSpawnTime;
    @Getter
    private long spawnDelay;

    @Getter
    private EntityType entityType;
    @Getter @Setter
    private EntityLootConfig lootConfig;

    // Calculated values based on stackSize
    @Getter
    private int maxStoragePages;
    @Getter @Setter
    private int maxSpawnerLootSlots;
    @Getter @Setter
    private int maxStoredExp;
    @Getter @Setter
    private int minMobs;
    @Getter @Setter
    private int maxMobs;

    @Getter
    private int stackSize;
    @Getter @Setter
    private int maxStackSize;

    @Getter @Setter
    private VirtualInventory virtualInventory;
    @Getter
    private final Set<Material> filteredItems = new HashSet<>();

    private final AtomicBoolean interacted = new AtomicBoolean(false);
    @Getter @Setter
    private String lastInteractedPlayer;

    @Getter
    private SellResult lastSellResult;
    @Getter
    private boolean lastSellProcessed;

    // Accumulated sell value for optimization
    @Getter
    private volatile double accumulatedSellValue;
    private volatile boolean sellValueDirty;

    private SpawnerHologram hologram;
    @Getter @Setter
    private long cachedSpawnDelay = 0;

    // Sort preference for spawner storage
    @Getter @Setter
    private Material preferredSortItem;

    public SpawnerData(String id, Location location, EntityType type, SmartSpawner plugin) {
        this.plugin = plugin;
        this.spawnerId = id;
        this.spawnerLocation = location;
        this.entityType = type;
        this.lootRegistry = plugin.getEntityLootRegistry();

        initializeDefaults();
        loadConfigurationValues();
        calculateStackBasedValues();
        initializeComponents();
    }

    private void initializeDefaults() {
        this.spawnerExp = 0;
        this.spawnerActive = true;
        this.spawnerStop = true;
        this.isAtCapacity = false;
        this.stackSize = 1;
        this.lastSpawnTime = System.currentTimeMillis();
        this.preferredSortItem = null; // Initialize sort preference as null
        this.accumulatedSellValue = 0.0;
        this.sellValueDirty = true;
    }

    public void loadConfigurationValues() {
        this.baseMaxStoredExp = plugin.getConfig().getInt("spawner_properties.default.max_stored_exp", 1000);
        this.baseMaxStoragePages = plugin.getConfig().getInt("spawner_properties.default.max_storage_pages", 1);
        this.baseMinMobs = plugin.getConfig().getInt("spawner_properties.default.min_mobs", 1);
        this.baseMaxMobs = plugin.getConfig().getInt("spawner_properties.default.max_mobs", 4);
        this.maxStackSize = plugin.getConfig().getInt("spawner_properties.default.max_stack_size", 1000);
        this.spawnDelay = plugin.getTimeFromConfig("spawner_properties.default.delay", "25s");
        this.spawnerRange = plugin.getConfig().getInt("spawner_properties.default.range", 16);
        this.lootConfig = lootRegistry.getLootConfig(entityType);
    }

    public void recalculateAfterConfigReload() {
        calculateStackBasedValues();
        if (virtualInventory != null && virtualInventory.getMaxSlots() != maxSpawnerLootSlots) {
            recreateVirtualInventory();
        }
        // Mark sell value as dirty after config reload since prices may have changed
        this.sellValueDirty = true;
        updateHologramData();

        // Invalidate GUI cache after config reload
        if (plugin.getSpawnerMenuUI() != null) {
            plugin.getSpawnerMenuUI().invalidateSpawnerCache(this.spawnerId);
        }
        if (plugin.getSpawnerMenuFormUI() != null) {
            plugin.getSpawnerMenuFormUI().invalidateSpawnerCache(this.spawnerId);
        }
    }

    private void calculateStackBasedValues() {
        this.maxStoredExp = baseMaxStoredExp * stackSize;
        this.maxStoragePages = baseMaxStoragePages * stackSize;
        this.maxSpawnerLootSlots = maxStoragePages * 45;
        this.minMobs = baseMinMobs * stackSize;
        this.maxMobs = baseMaxMobs * stackSize;
        this.spawnerExp = Math.min(this.spawnerExp, this.maxStoredExp);
    }

    public void setSpawnDelay(long baseSpawnerDelay) {
        this.spawnDelay = baseSpawnerDelay > 0 ? baseSpawnerDelay : 400;
        if (baseSpawnerDelay <= 0) {
            plugin.getLogger().warning("Invalid delay value. Setting to default: 400");
        }
    }

    private void initializeComponents() {
        this.virtualInventory = new VirtualInventory(maxSpawnerLootSlots);
        if (plugin.getConfig().getBoolean("hologram.enabled", false)) {
            createHologram();
        }
    }

    private void createHologram() {
        this.hologram = new SpawnerHologram(spawnerLocation);
        this.hologram.createHologram();
        updateHologramData();
    }

    public void setStackSize(int stackSize) {
        lock.lock();
        try {
            updateStackSize(stackSize);
        } finally {
            lock.unlock();
        }
    }

    private void updateStackSize(int newStackSize) {
        if (newStackSize <= 0) {
            this.stackSize = 1;
            plugin.getLogger().warning("Invalid stack size. Setting to 1");
            return;
        }

        if (newStackSize > this.maxStackSize) {
            this.stackSize = this.maxStackSize;
            plugin.getLogger().warning("Stack size exceeds maximum. Setting to " + this.stackSize);
            return;
        }

        this.stackSize = newStackSize;
        Map<VirtualInventory.ItemSignature, Long> currentItems = virtualInventory.getConsolidatedItems();

        calculateStackBasedValues();

        VirtualInventory newInventory = new VirtualInventory(this.maxSpawnerLootSlots);
        transferItemsToNewInventory(currentItems, newInventory);
        this.virtualInventory = newInventory;

        this.lastSpawnTime = System.currentTimeMillis();
        updateHologramData();

        // Invalidate GUI cache when stack size changes
        if (plugin.getSpawnerMenuUI() != null) {
            plugin.getSpawnerMenuUI().invalidateSpawnerCache(this.spawnerId);
        }
        if (plugin.getSpawnerMenuFormUI() != null) {
            plugin.getSpawnerMenuFormUI().invalidateSpawnerCache(this.spawnerId);
        }
    }

    private void recreateVirtualInventory() {
        if (virtualInventory == null) return;

        Map<VirtualInventory.ItemSignature, Long> currentItems = virtualInventory.getConsolidatedItems();
        VirtualInventory newInventory = new VirtualInventory(maxSpawnerLootSlots);
        transferItemsToNewInventory(currentItems, newInventory);
        this.virtualInventory = newInventory;
    }

    private void transferItemsToNewInventory(Map<VirtualInventory.ItemSignature, Long> items,
                                             VirtualInventory newInventory) {
        List<ItemStack> itemsToTransfer = new ArrayList<>();

        items.forEach((signature, amount) -> {
            ItemStack template = signature.getTemplate();
            while (amount > 0) {
                int batchSize = (int) Math.min(amount, Integer.MAX_VALUE);
                ItemStack batch = template.clone();
                batch.setAmount(batchSize);
                itemsToTransfer.add(batch);
                amount -= batchSize;
            }
        });

        newInventory.addItems(itemsToTransfer);
    }

    public void setSpawnerExp(int exp) {
        this.spawnerExp = Math.min(Math.max(0, exp), maxStoredExp);
        updateHologramData();

        // Invalidate GUI cache when experience changes
        if (plugin.getSpawnerMenuUI() != null) {
            plugin.getSpawnerMenuUI().invalidateSpawnerCache(this.spawnerId);
        }
        if (plugin.getSpawnerMenuFormUI() != null) {
            plugin.getSpawnerMenuFormUI().invalidateSpawnerCache(this.spawnerId);
        }
    }

    public void setSpawnerExpData(int exp) {
        this.spawnerExp = exp;
    }

    public void updateHologramData() {
        if (hologram != null) {
            hologram.updateData(stackSize, entityType, spawnerExp, maxStoredExp,
                    virtualInventory.getUsedSlots(), maxSpawnerLootSlots);
        }
    }

    public void reloadHologramData() {
        if (hologram != null) {
            hologram.remove();
            createHologram();
        }
    }

    public void refreshHologram() {
        if (plugin.getConfig().getBoolean("hologram.enabled", false)) {
            if (hologram == null) {
                createHologram();
            }
        } else if (hologram != null) {
            removeHologram();
        }
    }

    public void removeHologram() {
        if (hologram != null) {
            hologram.remove();
            hologram = null;
        }
    }

    public boolean isCompletelyFull() {
        return virtualInventory.getUsedSlots() >= maxSpawnerLootSlots && spawnerExp >= maxStoredExp;
    }

    public boolean updateCapacityStatus() {
        boolean newStatus = isCompletelyFull();
        if (newStatus != isAtCapacity) {
            isAtCapacity = newStatus;
            return true;
        }
        return false;
    }

    public void setEntityType(EntityType newType) {
        this.entityType = newType;
        this.lootConfig = lootRegistry.getLootConfig(newType);
        // Mark sell value as dirty since entity type and prices changed
        this.sellValueDirty = true;
        updateHologramData();
    }

    public boolean toggleItemFilter(Material material) {
        boolean wasFiltered = filteredItems.contains(material);
        if (wasFiltered) {
            filteredItems.remove(material);
        } else {
            filteredItems.add(material);
        }
        return !wasFiltered;
    }

    public List<LootItem> getValidLootItems() {
        if (lootConfig == null) {
            return Collections.emptyList();
        }
        return lootConfig.getAllItems().stream()
                .filter(this::isLootItemValid)
                .collect(Collectors.toList());
    }

    private boolean isLootItemValid(LootItem item) {
        ItemStack example = item.createItemStack(new Random());
        return example != null && !filteredItems.contains(example.getType());
    }

    public int getEntityExperienceValue() {
        return lootConfig != null ? lootConfig.getExperience() : 0;
    }

    public void setLootConfig() {
        this.lootConfig = lootRegistry.getLootConfig(entityType);
        // Mark sell value as dirty since prices may have changed
        this.sellValueDirty = true;
    }

    public void setLastSellResult(SellResult sellResult) {
        this.lastSellResult = sellResult;
        this.lastSellProcessed = false;
    }

    public void markLastSellAsProcessed() {
        this.lastSellProcessed = true;
    }

    public boolean isInteracted() {
        return interacted.get();
    }

    public void markInteracted() {
        interacted.compareAndSet(false, true);
    }

    public void clearInteracted() {
        interacted.compareAndSet(true, false);
    }

    public void updateLastInteractedPlayer(String playerName) {
        this.lastInteractedPlayer = playerName;
        markInteracted();
    }

    /**
     * Marks the sell value as dirty, requiring recalculation
     */
    public void markSellValueDirty() {
        this.sellValueDirty = true;
    }

    /**
     * Updates the accumulated sell value for specific items being added
     * @param itemsAdded Map of item signatures to quantities added
     * @param priceCache Price cache from loot config
     */
    public void incrementSellValue(Map<VirtualInventory.ItemSignature, Long> itemsAdded,
                                   Map<String, Double> priceCache) {
        if (itemsAdded == null || itemsAdded.isEmpty()) {
            return;
        }

        double addedValue = 0.0;
        for (Map.Entry<VirtualInventory.ItemSignature, Long> entry : itemsAdded.entrySet()) {
            ItemStack template = entry.getKey().getTemplate();
            long amount = entry.getValue();
            double itemPrice = findItemPrice(template, priceCache);
            if (itemPrice > 0.0) {
                addedValue += itemPrice * amount;
            }
        }

        this.accumulatedSellValue += addedValue;
        this.sellValueDirty = false;
    }

    /**
     * Decrements the accumulated sell value when items are removed
     * @param itemsRemoved List of items removed
     * @param priceCache Price cache from loot config
     */
    public void decrementSellValue(List<ItemStack> itemsRemoved, Map<String, Double> priceCache) {
        if (itemsRemoved == null || itemsRemoved.isEmpty()) {
            return;
        }

        // Consolidate removed items
        Map<VirtualInventory.ItemSignature, Long> consolidated = new java.util.HashMap<>();
        for (ItemStack item : itemsRemoved) {
            if (item == null || item.getAmount() <= 0) continue;
            VirtualInventory.ItemSignature sig = new VirtualInventory.ItemSignature(item);
            consolidated.merge(sig, (long) item.getAmount(), Long::sum);
        }

        double removedValue = 0.0;
        for (Map.Entry<VirtualInventory.ItemSignature, Long> entry : consolidated.entrySet()) {
            ItemStack template = entry.getKey().getTemplate();
            long amount = entry.getValue();
            double itemPrice = findItemPrice(template, priceCache);
            if (itemPrice > 0.0) {
                removedValue += itemPrice * amount;
            }
        }

        this.accumulatedSellValue = Math.max(0.0, this.accumulatedSellValue - removedValue);
    }

    /**
     * Forces a full recalculation of the accumulated sell value
     * Should be called when the cache is dirty or on spawner load
     */
    public void recalculateSellValue() {
        if (lootConfig == null) {
            this.accumulatedSellValue = 0.0;
            this.sellValueDirty = false;
            return;
        }

        // Get price cache
        Map<String, Double> priceCache = createPriceCache();

        // Calculate from current inventory
        Map<VirtualInventory.ItemSignature, Long> items = virtualInventory.getConsolidatedItems();
        double totalValue = 0.0;

        for (Map.Entry<VirtualInventory.ItemSignature, Long> entry : items.entrySet()) {
            ItemStack template = entry.getKey().getTemplate();
            long amount = entry.getValue();
            double itemPrice = findItemPrice(template, priceCache);
            if (itemPrice > 0.0) {
                totalValue += itemPrice * amount;
            }
        }

        this.accumulatedSellValue = totalValue;
        this.sellValueDirty = false;
    }

    /**
     * Gets the price cache from loot config
     */
    public Map<String, Double> createPriceCache() {
        if (lootConfig == null) {
            return new java.util.HashMap<>();
        }

        Map<String, Double> cache = new java.util.HashMap<>();
        java.util.List<LootItem> allLootItems = lootConfig.getAllItems();

        for (LootItem lootItem : allLootItems) {
            if (lootItem.getSellPrice() > 0.0) {
                ItemStack template = lootItem.createItemStack(new java.util.Random());
                if (template != null) {
                    String key = createItemKey(template);
                    cache.put(key, lootItem.getSellPrice());
                }
            }
        }

        return cache;
    }

    /**
     * Finds item price using the cache
     */
    private double findItemPrice(ItemStack item, Map<String, Double> priceCache) {
        if (item == null || priceCache == null) {
            return 0.0;
        }
        String itemKey = createItemKey(item);
        Double price = priceCache.get(itemKey);
        return price != null ? price : 0.0;
    }

    /**
     * Creates a unique key for an item (same logic as SpawnerSellManager)
     */
    private String createItemKey(ItemStack item) {
        if (item == null) {
            return "null";
        }

        StringBuilder key = new StringBuilder();
        key.append(item.getType().name());

        // Add enchantments if present
        if (item.hasItemMeta() && item.getItemMeta().hasEnchants()) {
            key.append("_enchants:");
            item.getItemMeta().getEnchants().entrySet().stream()
                    .sorted(java.util.Map.Entry.comparingByKey(java.util.Comparator.comparing(enchantment -> enchantment.getKey().toString())))
                    .forEach(entry -> key.append(entry.getKey().getKey()).append(":").append(entry.getValue()).append(","));
        }

        // Add custom model data if present
        if (item.hasItemMeta() && item.getItemMeta().hasCustomModelData()) {
            key.append("_cmd:").append(item.getItemMeta().getCustomModelData());
        }

        // Add display name if present
        if (item.hasItemMeta() && item.getItemMeta().hasDisplayName()) {
            key.append("_name:").append(item.getItemMeta().getDisplayName());
        }

        return key.toString();
    }

    /**
     * Checks if sell value needs recalculation
     */
    public boolean isSellValueDirty() {
        return sellValueDirty;
    }

    /**
     * Adds items to virtual inventory and updates accumulated sell value
     * This is the preferred method to add items to maintain accurate sell value cache
     * @param items Items to add
     */
    public void addItemsAndUpdateSellValue(List<ItemStack> items) {
        if (items == null || items.isEmpty()) {
            return;
        }

        // Consolidate items being added for efficient price lookup
        Map<VirtualInventory.ItemSignature, Long> itemsToAdd = new java.util.HashMap<>();
        for (ItemStack item : items) {
            if (item == null || item.getAmount() <= 0) continue;
            VirtualInventory.ItemSignature sig = new VirtualInventory.ItemSignature(item);
            itemsToAdd.merge(sig, (long) item.getAmount(), Long::sum);
        }

        // Add to inventory
        virtualInventory.addItems(items);

        // Update sell value
        if (!sellValueDirty) {
            Map<String, Double> priceCache = createPriceCache();
            incrementSellValue(itemsToAdd, priceCache);
        }
    }

    /**
     * Removes items from virtual inventory and updates accumulated sell value
     * @param items Items to remove
     * @return true if items were removed successfully
     */
    public boolean removeItemsAndUpdateSellValue(List<ItemStack> items) {
        if (items == null || items.isEmpty()) {
            return true;
        }

        // Remove from inventory
        boolean removed = virtualInventory.removeItems(items);

        // Update sell value if removal was successful
        if (removed && !sellValueDirty) {
            Map<String, Double> priceCache = createPriceCache();
            decrementSellValue(items, priceCache);
        }

        return removed;
    }

    /**
     * Get the spawner's creature type.
     *
     * @return The creature type or null if it not set.
     */
    @Nullable
    @Override
    public EntityType getSpawnedType() {
        return this.entityType;
    }

    /**
     * Set the spawner's creature type. <br>
     * This will override any entities that have been added with {@link #addPotentialSpawn}
     *
     * @param creatureType The creature type or null to clear.
     */
    @Override
    public void setSpawnedType(@Nullable EntityType creatureType) {
        if (creatureType != null) {
            setEntityType(creatureType);
        }
    }

    /**
     * Get the spawner's delay.
     * <br>
     * This is the delay, in ticks, until the spawner will spawn its next mob.
     *
     * @return The delay.
     */
    @Override
    public int getDelay() {
        // Convert milliseconds to ticks (1 tick = 50ms)
        return (int) (this.spawnDelay / 50L);
    }

    /**
     * {@inheritDoc}
     * <br>
     * If set to -1, the spawn delay will be reset to a random value between
     * {@link #getMinSpawnDelay} and {@link #getMaxSpawnDelay()}.
     *
     * @param delay The delay.
     */
    @Override
    public void setDelay(int delay) {
        // Convert ticks to milliseconds (1 tick = 50ms)
        this.spawnDelay = delay * 50L;
    }

    /**
     * Get the maximum distance(squared) a player can be in order for this
     * spawner to be active.
     * <br>
     * If this value is less than or equal to 0, this spawner is always active
     * (given that there are players online).
     * <br>
     * Default value is 16.
     *
     * @return the maximum distance(squared) a player can be in order for this
     * spawner to be active.
     */
    @Override
    public int getRequiredPlayerRange() {
        return this.spawnerRange != null ? this.spawnerRange : 16;
    }

    /**
     * Set the maximum distance (squared) a player can be in order for this
     * spawner to be active.
     * <br>
     * Setting this value to less than or equal to 0 will make this spawner
     * always active (given that there are players online).
     *
     * @param requiredPlayerRange the maximum distance (squared) a player can be
     *                            in order for this spawner to be active.
     */
    @Override
    public void setRequiredPlayerRange(int requiredPlayerRange) {
        this.spawnerRange = requiredPlayerRange;
    }

    /**
     * Get the radius around which the spawner will attempt to spawn mobs in.
     * <br>
     * This area is square, includes the block the spawner is in, and is
     * centered on the spawner's x,z coordinates - not the spawner itself.
     * <br>
     * It is 2 blocks high, centered on the spawner's y-coordinate (its bottom);
     * thus allowing mobs to spawn as high as its top surface and as low
     * as 1 block below its bottom surface.
     * <br>
     * Default value is 4.
     *
     * @return the spawn range
     */
    @Override
    public int getSpawnRange() {
        // This spawner is virtual and doesn't actually spawn entities in the world,
        // so we return a default value. The actual spawning is handled by loot generation.
        return 4;
    }

    /**
     * Set the new spawn range.
     * <br>
     *
     * @param spawnRange the new spawn range
     * @see #getSpawnRange()
     */
    @Override
    public void setSpawnRange(int spawnRange) {
        // This spawner is virtual and doesn't actually spawn entities in the world.
        // Spawn range setting is not applicable to virtual spawners.
        // This is a no-op implementation to satisfy the Spawner interface.
    }

    /**
     * Gets the {@link EntitySnapshot} that will be spawned by this spawner or null
     * if no entities have been assigned to this spawner. <br>
     * <p>
     * All applicable data from the spawner will be copied, such as custom name,
     * health, and velocity. <br>
     *
     * @return the entity snapshot or null if no entities have been assigned to this
     * spawner.
     */
    @Override
    public @Nullable EntitySnapshot getSpawnedEntity() {
        return null;
    }

    /**
     * Sets the entity that will be spawned by this spawner. <br>
     * This will override any previous entries that have been added with
     * {@link #addPotentialSpawn}
     * <p>
     * All applicable data from the snapshot will be copied, such as custom name,
     * health, and velocity. <br>
     *
     * @param snapshot the entity snapshot or null to clear
     */
    @Override
    public void setSpawnedEntity(@Nullable EntitySnapshot snapshot) {

    }

    /**
     * Sets the {@link SpawnerEntry} that will be spawned by this spawner. <br>
     * This will override any previous entries that have been added with
     * {@link #addPotentialSpawn}
     *
     * @param spawnerEntry the spawner entry to use
     */
    @Override
    public void setSpawnedEntity(@NotNull SpawnerEntry spawnerEntry) {

    }

    /**
     * Adds a new {@link EntitySnapshot} to the list of entities this spawner can
     * spawn.
     * <p>
     * The weight will determine how often this entry is chosen to spawn, higher
     * weighted entries will spawn more often than lower weighted ones. <br>
     * The {@link SpawnRule} will determine under what conditions this entry can
     * spawn, passing null will use the default conditions for the given entity.
     *
     * @param snapshot  the snapshot that will be spawned
     * @param weight    the weight
     * @param spawnRule the spawn rule for this entity, or null
     */
    @Override
    public void addPotentialSpawn(@NotNull EntitySnapshot snapshot, int weight, @Nullable SpawnRule spawnRule) {

    }

    /**
     * Adds a new {@link SpawnerEntry} to the list of entities this spawner can
     * spawn.
     *
     * @param spawnerEntry the spawner entry to use
     * @see #addPotentialSpawn(EntitySnapshot, int, SpawnRule)
     */
    @Override
    public void addPotentialSpawn(@NotNull SpawnerEntry spawnerEntry) {

    }

    /**
     * Sets the list of {@link SpawnerEntry} this spawner can spawn. <br>
     * This will override any previous entries added with
     * {@link #addPotentialSpawn}
     *
     * @param entries the list of entries
     */
    @Override
    public void setPotentialSpawns(@NotNull Collection<SpawnerEntry> entries) {

    }

    /**
     * Gets a list of potential spawns from this spawner or an empty list if no
     * entities have been assigned to this spawner. <br>
     * Changes made to the returned list will not be reflected in the spawner unless
     * applied with {@link #setPotentialSpawns}
     *
     * @return a list of potential spawns from this spawner, or an empty list if no
     * entities have been assigned to this spawner
     * @see #getSpawnedType()
     */
    @NotNull
    @Override
    public List<SpawnerEntry> getPotentialSpawns() {
        return List.of();
    }

    /**
     * The minimum spawn delay amount (in ticks).
     * <br>
     * This value is used when the spawner resets its delay (for any reason).
     * It will choose a random number between {@link #getMinSpawnDelay()}
     * and {@link #getMaxSpawnDelay()} for its next {@link #getDelay()}.
     * <br>
     * Default value is 200 ticks.
     *
     * @return the minimum spawn delay amount
     */
    @Override
    public int getMinSpawnDelay() {
        return 0;
    }

    /**
     * Set the minimum spawn delay amount (in ticks).
     *
     * @param delay the minimum spawn delay amount
     * @see #getMinSpawnDelay()
     */
    @Override
    public void setMinSpawnDelay(int delay) {

    }

    /**
     * The maximum spawn delay amount (in ticks).
     * <br>
     * This value is used when the spawner resets its delay (for any reason).
     * It will choose a random number between {@link #getMinSpawnDelay()}
     * and {@link #getMaxSpawnDelay()} for its next {@link #getDelay()}.
     * <br>
     * This value <b>must</b> be greater than 0 and less than or equal to
     * {@link #getMaxSpawnDelay()}.
     * <br>
     * Default value is 800 ticks.
     *
     * @return the maximum spawn delay amount
     */
    @Override
    public int getMaxSpawnDelay() {
        return 0;
    }

    /**
     * Set the maximum spawn delay amount (in ticks).
     * <br>
     * This value <b>must</b> be greater than 0, as well as greater than or
     * equal to {@link #getMinSpawnDelay()}
     *
     * @param delay the new maximum spawn delay amount
     * @see #getMaxSpawnDelay()
     */
    @Override
    public void setMaxSpawnDelay(int delay) {

    }

    /**
     * Get how many mobs attempt to spawn.
     * <br>
     * Default value is 4.
     *
     * @return the current spawn count
     */
    @Override
    public int getSpawnCount() {
        return 0;
    }

    /**
     * Set how many mobs attempt to spawn.
     *
     * @param spawnCount the new spawn count
     */
    @Override
    public void setSpawnCount(int spawnCount) {

    }

    /**
     * Set the new maximum amount of similar entities that are allowed to be
     * within spawning range of this spawner.
     * <br>
     * If more than the maximum number of entities are within range, the spawner
     * will not spawn and try again with a new {@link #getDelay()}.
     * <br>
     * Default value is 16.
     *
     * @return the maximum number of nearby, similar, entities
     */
    @Override
    public int getMaxNearbyEntities() {
        return 0;
    }

    /**
     * Set the maximum number of similar entities that are allowed to be within
     * spawning range of this spawner.
     * <br>
     * Similar entities are entities that are of the same {@link EntityType}
     *
     * @param maxNearbyEntities the maximum number of nearby, similar, entities
     */
    @Override
    public void setMaxNearbyEntities(int maxNearbyEntities) {

    }

    /**
     * Check if spawner is activated (a player is close enough)
     *
     * @return True if a player is close enough to activate it
     */
    @Override
    public boolean isActivated() {
        // spawnerStop == true means no players in range (stopped)
        // spawnerStop == false means players in range (activated)
        // So isActivated is the inverse of spawnerStop
        return !this.spawnerStop;
    }

    /**
     * Resets the spawn delay timer within the min/max range
     */
    @Override
    public void resetTimer() {
        // Reset the timer to current time, which will restart the delay countdown
        this.lastSpawnTime = System.currentTimeMillis();
    }

    /**
     * Sets the {@link EntityType} to {@link EntityType#ITEM} and sets the data to the given
     * {@link ItemStack ItemStack}.
     * <p>
     * {@link #setSpawnCount(int)} does not dictate the amount of items in the stack spawned, but rather how many
     * stacks should be spawned.
     *
     * @param itemStack The item to spawn. Must not {@link Material#isAir be air}.
     * @see #setSpawnedType(EntityType)
     */
    @Override
    public void setSpawnedItem(@NotNull ItemStack itemStack) {
        // Virtual spawner doesn't spawn items directly, no-op
    }

    // ==================== Additional Helper Methods ====================
    // These methods provide convenience access and maintain compatibility
    // with existing code while using the Spawner interface internally

    /**
     * Legacy method - use getRequiredPlayerRange() instead.
     * @deprecated Use {@link #getRequiredPlayerRange()} for Bukkit compatibility
     */
    @Deprecated
    public Integer getSpawnerRange() {
        return getRequiredPlayerRange();
    }

    /**
     * Legacy method - use setRequiredPlayerRange() instead.
     * @deprecated Use {@link #setRequiredPlayerRange(int)} for Bukkit compatibility
     */
    @Deprecated
    public void setSpawnerRange(Integer range) {
        if (range != null) {
            setRequiredPlayerRange(range);
        }
    }

    /**
     * Check if the spawner is stopped (no players in range).
     * This is the inverse of isActivated().
     * @return true if spawner is stopped (no players in range)
     */
    public Boolean getSpawnerStop() {
        return !isActivated();
    }

    /**
     * Set the spawner stopped state.
     * This is the inverse of the activated state.
     * @param stopped true to stop the spawner (no players in range)
     */
    public void setSpawnerStop(Boolean stopped) {
        this.spawnerStop = stopped;
    }

    /**
     * Get the spawn delay in milliseconds (internal format).
     * For ticks, use getDelay().
     * @return spawn delay in milliseconds
     */
    public long getSpawnDelay() {
        return this.spawnDelay;
    }

    /**
     * Syncs data from a physical CreatureSpawner block to this virtual SpawnerData.
     * This method extracts relevant information from the spawner block and applies it
     * to the virtual spawner, bridging the gap between Bukkit's block-based spawner
     * and our virtual spawner system.
     * 
     * <p>Note: This is a one-way sync FROM the block TO SpawnerData. The virtual spawner
     * maintains its own state independently after initial sync.</p>
     * 
     * @param blockSpawner The CreatureSpawner block to sync from
     * @throws IllegalArgumentException if blockSpawner is null
     */
    public void syncFromBlock(@NotNull org.bukkit.block.CreatureSpawner blockSpawner) {
        if (blockSpawner == null) {
            throw new IllegalArgumentException("Block spawner cannot be null");
        }

        // Sync entity type
        EntityType blockEntityType = blockSpawner.getSpawnedType();
        if (blockEntityType != null && blockEntityType != EntityType.UNKNOWN) {
            setSpawnedType(blockEntityType);
        }

        // Sync delay (convert from ticks to milliseconds)
        int delayTicks = blockSpawner.getDelay();
        if (delayTicks > 0) {
            setDelay(delayTicks);
        }

        // Sync required player range
        int range = blockSpawner.getRequiredPlayerRange();
        if (range > 0) {
            setRequiredPlayerRange(range);
        }

        // Note: isActivated() is managed by SpawnerRangeChecker, not synced from block
        // The block's activation state is transient, while our virtual spawner
        // has its own activation monitoring system

        plugin.debug("Synced virtual spawner " + spawnerId + " from block spawner");
    }

    /**
     * Applies this virtual spawner's data to a physical CreatureSpawner block.
     * This is the reverse of syncFromBlock() - it pushes our virtual spawner's
     * configuration to the physical block.
     * 
     * <p>This is useful when you need to update the visual representation of the
     * spawner block to match the virtual spawner's current state.</p>
     * 
     * @param blockSpawner The CreatureSpawner block to apply data to
     * @throws IllegalArgumentException if blockSpawner is null
     */
    public void applyToBlock(@NotNull org.bukkit.block.CreatureSpawner blockSpawner) {
        if (blockSpawner == null) {
            throw new IllegalArgumentException("Block spawner cannot be null");
        }

        // Apply entity type
        if (this.entityType != null && this.entityType != EntityType.UNKNOWN) {
            blockSpawner.setSpawnedType(this.entityType);
        }

        // Apply delay (our internal format to block's tick format)
        blockSpawner.setDelay(getDelay());

        // Apply required player range
        blockSpawner.setRequiredPlayerRange(getRequiredPlayerRange());

        // Update the block
        blockSpawner.update(true, false);

        plugin.debug("Applied virtual spawner " + spawnerId + " data to block spawner");
    }
}