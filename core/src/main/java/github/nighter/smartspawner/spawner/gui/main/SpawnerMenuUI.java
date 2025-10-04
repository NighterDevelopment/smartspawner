package github.nighter.smartspawner.spawner.gui.main;

import github.nighter.smartspawner.SmartSpawner;
import github.nighter.smartspawner.spawner.gui.layout.GuiLayout;
import github.nighter.smartspawner.spawner.gui.layout.GuiButton;
import github.nighter.smartspawner.spawner.loot.EntityLootConfig;
import github.nighter.smartspawner.spawner.loot.LootItem;
import github.nighter.smartspawner.spawner.utils.SpawnerMobHeadTexture;
import github.nighter.smartspawner.spawner.properties.SpawnerData;
import github.nighter.smartspawner.spawner.properties.VirtualInventory;
import github.nighter.smartspawner.language.LanguageManager;
import github.nighter.smartspawner.api.events.SpawnerOpenGUIEvent;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.*;

public class SpawnerMenuUI {
    private static final int INVENTORY_SIZE = 27;
    // Remove hardcoded slot constants - now using layout config
    private static final int TICKS_PER_SECOND = 20;
    private static final Map<String, String> EMPTY_PLACEHOLDERS = Collections.emptyMap();

    // Cache frequently used formatting strings and pattern lookups
    private static final String LOOT_ITEM_FORMAT_KEY = "spawner_storage_item.loot_items";
    private static final String EMPTY_LOOT_MESSAGE_KEY = "spawner_storage_item.loot_items_empty";

    private final SmartSpawner plugin;
    private final LanguageManager languageManager;

    // Format strings - initialized in constructor to avoid repeated lookups
    private String lootItemFormat;
    private String emptyLootMessage;

    // Cache for GUI items - cleared when spawner data changes
    private final Map<String, ItemStack> itemCache = new HashMap<>();
    private final Map<String, Long> cacheTimestamps = new HashMap<>();
    private static final long CACHE_EXPIRY_TIME_MS = 30000; // 30 seconds

    public SpawnerMenuUI(SmartSpawner plugin) {
        this.plugin = plugin;
        this.languageManager = plugin.getLanguageManager();
        loadConfig();
    }

    public void loadConfig() {
        clearCache();
        this.lootItemFormat = languageManager.getGuiItemName(LOOT_ITEM_FORMAT_KEY, EMPTY_PLACEHOLDERS);
        this.emptyLootMessage = languageManager.getGuiItemName(EMPTY_LOOT_MESSAGE_KEY, EMPTY_PLACEHOLDERS);
    }

    public void clearCache() {
        itemCache.clear();
        cacheTimestamps.clear();
    }

    public void invalidateSpawnerCache(String spawnerId) {
        itemCache.entrySet().removeIf(entry -> entry.getKey().startsWith(spawnerId + "|"));
        cacheTimestamps.entrySet().removeIf(entry -> entry.getKey().startsWith(spawnerId + "|"));
    }

    private boolean isCacheEntryExpired(String cacheKey) {
        Long timestamp = cacheTimestamps.get(cacheKey);
        return timestamp == null || System.currentTimeMillis() - timestamp > CACHE_EXPIRY_TIME_MS;
    }

    public void openSpawnerMenu(Player player, SpawnerData spawner, boolean refresh) {
        // Fire SpawnerOpenGUI API event
        SpawnerOpenGUIEvent openEvent = new SpawnerOpenGUIEvent(
                player,
                spawner.getSpawnerLocation(),
                spawner.getEntityType(),
                spawner.getStackSize(),
                refresh
        );
        Bukkit.getPluginManager().callEvent(openEvent);
        
        if (openEvent.isCancelled()) {
            return;
        }

        Inventory menu = createMenu(spawner);
        GuiLayout layout = plugin.getGuiLayoutConfig().getCurrentMainLayout();

        // Populate menu items based on layout configuration
        ItemStack[] items = new ItemStack[INVENTORY_SIZE];
        
        // Add storage button if enabled in layout
        GuiButton storageButton = layout.getButton("storage");
        if (storageButton != null) {
            items[storageButton.getSlot()] = createLootStorageItem(spawner);
        }
        
        // Add spawner info button if enabled in layout - handle conditional buttons
        GuiButton spawnerInfoButton = getSpawnerInfoButton(layout, player);
        if (spawnerInfoButton != null) {
            items[spawnerInfoButton.getSlot()] = createSpawnerInfoItem(player, spawner);
        }
        
        // Add exp button if enabled in layout
        GuiButton expButton = layout.getButton("exp");
        if (expButton != null) {
            items[expButton.getSlot()] = createExpItem(spawner);
        }

        // Set all items at once instead of one by one
        for (int i = 0; i < items.length; i++) {
            if (items[i] != null) {
                menu.setItem(i, items[i]);
            }
        }

        // Open inventory and play sound if not refreshing
        player.openInventory(menu);

        if (!refresh) {
            player.playSound(player.getLocation(), Sound.BLOCK_ENDER_CHEST_OPEN, 1.0f, 1.0f);
        }

        // Force an immediate timer update for the newly opened GUI (only if timer placeholders are enabled)
        // This ensures the timer displays correctly from the start
        if (plugin.getSpawnerGuiViewManager().isTimerPlaceholdersEnabled()) {
            plugin.getSpawnerGuiViewManager().forceTimerUpdate(player, spawner);
        }
    }

    private Inventory createMenu(SpawnerData spawner) {
        // Get entity name with caching
        String entityName = languageManager.getFormattedMobName(spawner.getEntityType());
        String entityNameSmallCaps = languageManager.getSmallCaps(languageManager.getFormattedMobName(spawner.getEntityType()));

        // Use string builder for efficient placeholder creation
        Map<String, String> placeholders = new HashMap<>(4);
        placeholders.put("entity", entityName);
        placeholders.put("ᴇɴᴛɪᴛʏ", entityNameSmallCaps);
        placeholders.put("amount", String.valueOf(spawner.getStackSize()));

        String title;
        if (spawner.getStackSize() > 1) {
            title = languageManager.getGuiTitle("gui_title_main.stacked_spawner", placeholders);
        } else {
            title = languageManager.getGuiTitle("gui_title_main.single_spawner", placeholders);
        }

        return Bukkit.createInventory(new SpawnerMenuHolder(spawner), INVENTORY_SIZE, title);
    }

    public ItemStack createLootStorageItem(SpawnerData spawner) {
        // Generate cache key based on spawner state
        VirtualInventory virtualInventory = spawner.getVirtualInventory();
        int currentItems = virtualInventory.getUsedSlots();
        int maxSlots = spawner.getMaxSpawnerLootSlots();
        String cacheKey = spawner.getSpawnerId() + "|storage|" + currentItems + "|" + maxSlots + "|" + virtualInventory.hashCode();
        
        // Check cache first
        ItemStack cachedItem = itemCache.get(cacheKey);
        if (cachedItem != null && !isCacheEntryExpired(cacheKey)) {
            return cachedItem.clone();
        }

        // Get important data upfront
        int percentStorage = calculatePercentage(currentItems, maxSlots);

        // Not in cache, create new item
        ItemStack chestItem = new ItemStack(Material.CHEST);
        ItemMeta chestMeta = chestItem.getItemMeta();
        if (chestMeta == null) return chestItem;

        // Build base placeholders
        Map<String, String> placeholders = new HashMap<>(4);
        placeholders.put("max_slots", languageManager.formatNumber(maxSlots));
        placeholders.put("current_items", String.valueOf(currentItems));
        placeholders.put("percent_storage", String.valueOf(percentStorage));

        // Get consolidated items and prepare the loot items section
        Map<VirtualInventory.ItemSignature, Long> storedItems = virtualInventory.getConsolidatedItems();

        // Build the loot items section efficiently
        String lootItemsText = buildLootItemsText(spawner.getEntityType(), storedItems);
        placeholders.put("loot_items", lootItemsText);

        // Set display name
        chestMeta.setDisplayName(languageManager.getGuiItemName("spawner_storage_item.name", placeholders));

        // Get lore efficiently
        List<String> lore = languageManager.getGuiItemLoreWithMultilinePlaceholders("spawner_storage_item.lore", placeholders);
        chestMeta.setLore(lore);
        chestItem.setItemMeta(chestMeta);

        // Cache the result
        itemCache.put(cacheKey, chestItem.clone());
        cacheTimestamps.put(cacheKey, System.currentTimeMillis());

        return chestItem;
    }

    private String buildLootItemsText(EntityType entityType, Map<VirtualInventory.ItemSignature, Long> storedItems) {
        // Create material-to-amount map for quick lookups
        Map<Material, Long> materialAmountMap = new HashMap<>();
        for (Map.Entry<VirtualInventory.ItemSignature, Long> entry : storedItems.entrySet()) {
            Material material = entry.getKey().getTemplateRef().getType();
            materialAmountMap.merge(material, entry.getValue(), Long::sum);
        }

        // Get possible loot items
        EntityLootConfig lootConfig = plugin.getEntityLootRegistry().getLootConfig(entityType);
        List<LootItem> possibleLootItems = lootConfig != null
                ? lootConfig.getAllItems()
                : Collections.emptyList();

        // Return early for empty cases
        if (possibleLootItems.isEmpty() && storedItems.isEmpty()) {
            return emptyLootMessage;
        }

        // Use StringBuilder for efficient string concatenation
        StringBuilder builder = new StringBuilder(Math.max(possibleLootItems.size(), storedItems.size()) * 40);

        if (!possibleLootItems.isEmpty()) {
            // Sort items by name for consistent display
            possibleLootItems.sort(Comparator.comparing(item -> languageManager.getVanillaItemName(item.getMaterial())));

            for (LootItem lootItem : possibleLootItems) {
                Material material = lootItem.getMaterial();
                long amount = materialAmountMap.getOrDefault(material, 0L);

                String materialName = languageManager.getVanillaItemName(material);
                String materialNameSmallCaps = languageManager.getSmallCaps(languageManager.getVanillaItemName(material));
                String formattedAmount = languageManager.formatNumber(amount);
                String chance = String.format("%.1f", lootItem.getChance()) + "%";

                // Format the line with minimal string operations
                String line = lootItemFormat
                        .replace("%item_name%", materialName)
                        .replace("%ɪᴛᴇᴍ_ɴᴀᴍᴇ%", materialNameSmallCaps)
                        .replace("%amount%", formattedAmount)
                        .replace("%raw_amount%", String.valueOf(amount))
                        .replace("%chance%", chance);

                builder.append(line).append('\n');
            }
        } else if (!storedItems.isEmpty()) {
            // Sort items by name
            List<Map.Entry<VirtualInventory.ItemSignature, Long>> sortedItems =
                    new ArrayList<>(storedItems.entrySet());
            sortedItems.sort(Comparator.comparing(e -> e.getKey().getMaterialName()));

            for (Map.Entry<VirtualInventory.ItemSignature, Long> entry : sortedItems) {
                ItemStack templateItem = entry.getKey().getTemplateRef();
                Material material = templateItem.getType();
                long amount = entry.getValue();

                String materialName = languageManager.getVanillaItemName(material);
                String materialNameSmallCaps = languageManager.getSmallCaps(languageManager.getVanillaItemName(material));
                String formattedAmount = languageManager.formatNumber(amount);

                // Format with minimal replacements
                String line = lootItemFormat
                        .replace("%item_name%", materialName)
                        .replace("%ɪᴛᴇᴍ_ɴᴀᴍᴇ%", materialNameSmallCaps)
                        .replace("%amount%", formattedAmount)
                        .replace("%raw_amount%", String.valueOf(amount))
                        .replace("%chance%", "");

                builder.append(line).append('\n');
            }
        }

        // Remove trailing newline if it exists
        int length = builder.length();
        if (length > 0 && builder.charAt(length - 1) == '\n') {
            builder.setLength(length - 1);
        }

        return builder.toString();
    }

    public ItemStack createSpawnerInfoItem(Player player, SpawnerData spawner) {
        // Get layout configuration first for cache key calculation
        GuiLayout layout = plugin.getGuiLayoutConfig().getCurrentMainLayout();
        GuiButton spawnerInfoButton = layout.getButton("spawner_info");
        
        // Get important data upfront
        EntityType entityType = spawner.getEntityType();
        int stackSize = spawner.getStackSize();
        VirtualInventory virtualInventory = spawner.getVirtualInventory();
        int currentItems = virtualInventory.getUsedSlots();
        int maxSlots = spawner.getMaxSpawnerLootSlots();

        // Calculate percentages with decimal precision - do this once
        double percentStorageDecimal = maxSlots > 0 ? ((double) currentItems / maxSlots) * 100 : 0;
        String formattedPercentStorage = String.format("%.1f", percentStorageDecimal);

        long currentExp = spawner.getSpawnerExp();
        long maxExp = spawner.getMaxStoredExp();
        double percentExpDecimal = maxExp > 0 ? ((double) currentExp / maxExp) * 100 : 0;
        String formattedPercentExp = String.format("%.1f", percentExpDecimal);

        // Create cache key including all relevant state
        boolean hasShopPermission = plugin.hasSellIntegration() && player.hasPermission("smartspawner.sellall");

        // Not in cache, create the ItemStack        
        ItemStack spawnerItem;
        if (spawnerInfoButton != null && spawnerInfoButton.getMaterial() == Material.PLAYER_HEAD) {
            // Use custom head texture for MOB_HEAD material
            spawnerItem = SpawnerMobHeadTexture.getCustomHead(entityType, player);
        } else if (spawnerInfoButton != null) {
            // Use the configured material
            spawnerItem = new ItemStack(spawnerInfoButton.getMaterial());
        } else {
            // Fallback to default behavior
            spawnerItem = SpawnerMobHeadTexture.getCustomHead(entityType, player);
        }
        
        ItemMeta spawnerMeta = spawnerItem.getItemMeta();
        if (spawnerMeta == null) return spawnerItem;

        // Get entity names with proper formatting - using cache
        String entityName = languageManager.getFormattedMobName(entityType);
        String entityNameSmallCaps = languageManager.getSmallCaps(languageManager.getFormattedMobName(entityType));

        // Prepare all placeholders - reuse the map rather than creating a new one each time
        Map<String, String> placeholders = new HashMap<>(16); // Preallocate with expected capacity

        // Entity information
        placeholders.put("entity", entityName);
        placeholders.put("ᴇɴᴛɪᴛʏ", entityNameSmallCaps);
        placeholders.put("entity_type", entityType.toString());

        // Stack information
        placeholders.put("stack_size", String.valueOf(stackSize));

        // Spawner settings
        placeholders.put("range", String.valueOf(spawner.getRequiredPlayerRange()));
        long delaySeconds = spawner.getSpawnDelay() / TICKS_PER_SECOND;
        placeholders.put("delay", String.valueOf(delaySeconds));
        placeholders.put("delay_raw", String.valueOf(spawner.getSpawnDelay()));
        placeholders.put("min_mobs", String.valueOf(spawner.getMinMobs()));
        placeholders.put("max_mobs", String.valueOf(spawner.getMaxMobs()));

        // Storage information
        placeholders.put("current_items", String.valueOf(currentItems));
        placeholders.put("max_items", languageManager.formatNumber(maxSlots));
        placeholders.put("formatted_storage", formattedPercentStorage);

        // Experience information
        String formattedCurrentExp = languageManager.formatNumber(currentExp);
        String formattedMaxExp = languageManager.formatNumber(maxExp);

        placeholders.put("current_exp", formattedCurrentExp);
        placeholders.put("max_exp", formattedMaxExp);
        placeholders.put("raw_current_exp", String.valueOf(currentExp));
        placeholders.put("raw_max_exp", String.valueOf(maxExp));
        placeholders.put("formatted_exp", formattedPercentExp);

        // Total sell price information
        double totalSellPrice = spawner.getAccumulatedSellValue();
        placeholders.put("total_sell_price", languageManager.formatNumber(totalSellPrice));

        // Set display name with the specified placeholders
        spawnerMeta.setDisplayName(languageManager.getGuiItemName("spawner_info_item.name", placeholders));

        // Select appropriate lore based on shop integration availability
        String loreKey = hasShopPermission
                ? "spawner_info_item.lore"
                : "spawner_info_item.lore_no_shop";

        // Get and set lore with placeholders
        List<String> lore = languageManager.getGuiItemLoreWithMultilinePlaceholders(loreKey, placeholders);
        spawnerMeta.setLore(lore);
        spawnerItem.setItemMeta(spawnerMeta);
        return spawnerItem;
    }

    public ItemStack createExpItem(SpawnerData spawner) {
        // Get important data upfront
        long currentExp = spawner.getSpawnerExp();
        long maxExp = spawner.getMaxStoredExp();
        int percentExp = calculatePercentage(currentExp, maxExp);

        // Create cache key for this specific spawner's exp state
        String cacheKey = spawner.getSpawnerId() + "|exp|" + currentExp + "|" + maxExp;

        // Check cache first
        ItemStack cachedItem = itemCache.get(cacheKey);
        if (cachedItem != null && !isCacheEntryExpired(cacheKey)) {
            return cachedItem.clone();
        }

        // Not in cache, create the ItemStack
        ItemStack expItem = new ItemStack(Material.EXPERIENCE_BOTTLE);
        ItemMeta expMeta = expItem.getItemMeta();
        if (expMeta == null) return expItem;

        // Format numbers once for display
        String formattedExp = languageManager.formatNumber(currentExp);
        String formattedMaxExp = languageManager.formatNumber(maxExp);

        // Prepare all placeholders
        Map<String, String> placeholders = new HashMap<>(5); // Preallocate with expected capacity
        placeholders.put("current_exp", formattedExp);
        placeholders.put("raw_current_exp", String.valueOf(currentExp));
        placeholders.put("max_exp", formattedMaxExp);
        placeholders.put("percent_exp", String.valueOf(percentExp));
        placeholders.put("u_max_exp", String.valueOf(maxExp));

        // Set name and lore
        expMeta.setDisplayName(languageManager.getGuiItemName("exp_info_item.name", placeholders));
        List<String> loreExp = languageManager.getGuiItemLoreAsList("exp_info_item.lore", placeholders);
        expMeta.setLore(loreExp);

        expItem.setItemMeta(expMeta);

        // Cache the result
        itemCache.put(cacheKey, expItem.clone());
        cacheTimestamps.put(cacheKey, System.currentTimeMillis());

        return expItem;
    }

    private int calculatePercentage(long current, long maximum) {
        return maximum > 0 ? (int) ((double) current / maximum * 100) : 0;
    }

    private GuiButton getSpawnerInfoButton(GuiLayout layout, Player player) {
        // Check for shop integration permission
        boolean hasShopPermission = plugin.hasSellIntegration() && player.hasPermission("smartspawner.sellall");

        // Try to get the appropriate conditional button first
        if (hasShopPermission) {
            GuiButton shopButton = layout.getButton("spawner_info_with_shop");
            if (shopButton != null) {
                return shopButton;
            }
        } else {
            GuiButton noShopButton = layout.getButton("spawner_info_no_shop");
            if (noShopButton != null) {
                return noShopButton;
            }
        }

        // Fallback to the generic spawner_info button if conditional ones don't exist
        return layout.getButton("spawner_info");
    }
}
