package github.nighter.smartspawner.spawner.gui.synchronization;

import github.nighter.smartspawner.SmartSpawner;
import github.nighter.smartspawner.spawner.gui.main.SpawnerMenuHolder;
import github.nighter.smartspawner.spawner.gui.storage.StoragePageHolder;
import github.nighter.smartspawner.spawner.gui.storage.filter.FilterConfigHolder;
import github.nighter.smartspawner.spawner.gui.main.SpawnerMenuUI;
import github.nighter.smartspawner.spawner.gui.storage.SpawnerStorageUI;
import github.nighter.smartspawner.spawner.gui.layout.GuiLayout;
import github.nighter.smartspawner.spawner.gui.layout.GuiButton;
import github.nighter.smartspawner.spawner.properties.SpawnerData;
import github.nighter.smartspawner.language.LanguageManager;
import github.nighter.smartspawner.Scheduler;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * SpawnerGuiViewManager responsible for managing all spawner GUI interactions and updates.
 * Handles the tracking, updating, and synchronization of open spawner GUI interfaces with improved performance.
 */
public class SpawnerGuiViewManager implements Listener {
    private static final long UPDATE_INTERVAL_TICKS = 20L; // 1 second updates
    private static final long INITIAL_DELAY_TICKS = 20L;   // Match the update interval
    private static final int ITEMS_PER_PAGE = 45;

    // Performance optimization: batch processing interval
    private static final int MAX_PLAYERS_PER_BATCH = 10;   // Limit players processed per batch

    // Cached slot positions - initialized once when config loads, re-initialized on reload
    private volatile int cachedStorageSlot = -1;
    private volatile int cachedExpSlot = -1;
    private volatile int cachedSpawnerInfoSlot = -1;

    // Update flags - using bit flags for efficient state tracking
    private static final int UPDATE_CHEST = 1;
    private static final int UPDATE_INFO = 2;
    private static final int UPDATE_EXP = 4;
    private static final int UPDATE_ALL = UPDATE_CHEST | UPDATE_INFO | UPDATE_EXP;

    private final SmartSpawner plugin;
    private final LanguageManager languageManager;
    private final SpawnerStorageUI spawnerStorageUI;
    private final SpawnerMenuUI spawnerMenuUI;

    // Optimized data structures to track viewers
    private final Map<UUID, SpawnerViewerInfo> playerToSpawnerMap;
    private final Map<String, Set<UUID>> spawnerToPlayersMap;
    private final Set<Class<? extends InventoryHolder>> validHolderTypes;

    // Additional tracking for filter GUI viewers to prevent duplication exploits
    private final Map<String, Set<UUID>> spawnerToFilterViewersMap;

    // NEW: Separate tracking for main menu viewers only (for timer updates)
    private final Map<UUID, SpawnerViewerInfo> mainMenuViewers = new ConcurrentHashMap<>();
    private final Map<String, Set<UUID>> spawnerToMainMenuViewers = new ConcurrentHashMap<>();

    // Batched update tracking to reduce inventory updates
    private final Set<UUID> pendingUpdates = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Integer> updateFlags = new ConcurrentHashMap<>();

    // Performance optimization: track last update times to avoid unnecessary updates
    private final Map<UUID, Long> lastTimerUpdate = new ConcurrentHashMap<>();
    private final Map<UUID, String> lastTimerValue = new ConcurrentHashMap<>();

    private Scheduler.Task updateTask;
    private volatile boolean isTaskRunning;

    // For timer optimizations - these avoid constant string lookups
    private String cachedInactiveText;
    private String cachedFullText;

    // Timer placeholder detection - cache whether GUI uses timer placeholders
    private volatile Boolean hasTimerPlaceholders = null;

    // Static class to hold viewer info more efficiently
    private static class SpawnerViewerInfo {
        final SpawnerData spawnerData;
        final long lastUpdateTime;
        final ViewerType viewerType;

        SpawnerViewerInfo(SpawnerData spawnerData, ViewerType viewerType) {
            this.spawnerData = spawnerData;
            this.lastUpdateTime = System.currentTimeMillis();
            this.viewerType = viewerType;
        }
    }

    // Enum to track different viewer types
    private enum ViewerType {
        MAIN_MENU,    // SpawnerMenuHolder - needs timer updates
        STORAGE,      // StoragePageHolder - no timer updates needed
        FILTER        // FilterConfigHolder - no timer updates needed
    }

    public SpawnerGuiViewManager(SmartSpawner plugin) {
        this.plugin = plugin;
        this.languageManager = plugin.getLanguageManager();
        this.spawnerStorageUI = plugin.getSpawnerStorageUI();
        this.spawnerMenuUI = plugin.getSpawnerMenuUI();
        this.playerToSpawnerMap = new ConcurrentHashMap<>();
        this.spawnerToPlayersMap = new ConcurrentHashMap<>();
        this.spawnerToFilterViewersMap = new ConcurrentHashMap<>();
        this.isTaskRunning = false;
        this.validHolderTypes = Set.of(
                SpawnerMenuHolder.class,
                StoragePageHolder.class,
                FilterConfigHolder.class
        );

        // Preload commonly used strings to avoid repeated lookups
        initCachedStrings();

        // Initialize all slot positions from layout configuration
        initializeSlotPositions();
    }

    private void initCachedStrings() {
        // Cache status text messages for timer display
        this.cachedInactiveText = languageManager.getGuiItemName("spawner_info_item.lore_inactive");
        this.cachedFullText = languageManager.getGuiItemName("spawner_info_item.lore_full");

        // Detect if timer placeholders are used in GUI configuration
        checkTimerPlaceholderUsage();
    }

    /**
     * Check if the GUI configuration uses %time% placeholders.
     * This optimization allows us to skip timer processing entirely for servers
     * that don't use timer displays in their spawner GUIs.
     */
    private void checkTimerPlaceholderUsage() {
        try {
            // Check both regular and no-shop lore configurations
            String[] loreLines = languageManager.getGuiItemLore("spawner_info_item.lore");
            String[] loreNoShopLines = languageManager.getGuiItemLore("spawner_info_item.lore_no_shop");

            // Check if any lore line contains %time% placeholder
            boolean hasTimers = false;

            if (loreLines != null) {
                for (String line : loreLines) {
                    if (line != null && line.contains("%time%")) {
                        hasTimers = true;
                        break;
                    }
                }
            }

            if (!hasTimers && loreNoShopLines != null) {
                for (String line : loreNoShopLines) {
                    if (line != null && line.contains("%time%")) {
                        hasTimers = true;
                        break;
                    }
                }
            }

            this.hasTimerPlaceholders = hasTimers;

        } catch (Exception e) {
            // Fallback to enabled if we can't determine
            this.hasTimerPlaceholders = true;
        }
    }

    /**
     * Check if timer placeholders are enabled in the GUI configuration.
     * This allows other components to skip timer-related processing when not needed.
     */
    public boolean isTimerPlaceholdersEnabled() {
        return hasTimerPlaceholders == null || hasTimerPlaceholders;
    }

    /**
     * Re-check timer placeholder usage after configuration reload.
     * This should be called after the language manager reloads to detect
     * changes in GUI configuration that add or remove timer displays.
     */
    public void recheckTimerPlaceholders() {
        // Reset cached strings with new language data
        initCachedStrings();

        // If timer placeholders were disabled but are now enabled, 
        // start timer updates for current viewers (if not already running)
        if (hasTimerPlaceholders != null && hasTimerPlaceholders && !playerToSpawnerMap.isEmpty() && !isTaskRunning) {
            startUpdateTask();
        }
        // If timer placeholders were enabled but are now disabled,
        // the update task should continue running for pending updates processing
        // (no need to stop the task, timer processing will be skipped in updateGuiForSpawnerInfo)
    }

    // ===============================================================
    //                      Task Management
    // ===============================================================

    private synchronized void startUpdateTask() {
        if (isTaskRunning) {
            return;
        }

        // Start task if we have any viewers (for pending updates) or main menu viewers (for timer updates)
        if (playerToSpawnerMap.isEmpty()) {
            return;
        }

        updateTask = Scheduler.runTaskTimer(this::updateGuiForSpawnerInfo,
                INITIAL_DELAY_TICKS, UPDATE_INTERVAL_TICKS);
        isTaskRunning = true;
    }

    public synchronized void stopUpdateTask() {
        if (!isTaskRunning) {
            return;
        }

        if (updateTask != null) {
            updateTask.cancel();
            updateTask = null;
        }

        isTaskRunning = false;
    }

    private boolean isValidGuiSession(Player player) {
        return player != null && player.isOnline();
    }

    private boolean isValidHolder(InventoryHolder holder) {
        return holder != null && validHolderTypes.contains(holder.getClass());
    }

    // ===============================================================
    //                      Viewer Tracking
    // ===============================================================

    public void trackViewer(UUID playerId, SpawnerData spawner, ViewerType viewerType) {
        // Track all viewers for general operations  
        playerToSpawnerMap.put(playerId, new SpawnerViewerInfo(spawner, viewerType));
        spawnerToPlayersMap.computeIfAbsent(spawner.getSpawnerId(), k -> ConcurrentHashMap.newKeySet())
                .add(playerId);

        // Separately track main menu viewers for timer updates
        if (viewerType == ViewerType.MAIN_MENU) {
            mainMenuViewers.put(playerId, new SpawnerViewerInfo(spawner, viewerType));
            spawnerToMainMenuViewers.computeIfAbsent(spawner.getSpawnerId(), k -> ConcurrentHashMap.newKeySet())
                    .add(playerId);
        }

        // Separately track filter GUI viewers to prevent duplication exploits
        if (viewerType == ViewerType.FILTER) {
            spawnerToFilterViewersMap.computeIfAbsent(spawner.getSpawnerId(), k -> ConcurrentHashMap.newKeySet())
                    .add(playerId);
        }

        // Start update task if we have any viewers (for pending updates processing)
        if (!isTaskRunning && !playerToSpawnerMap.isEmpty()) {
            startUpdateTask();
        }
    }

    public void untrackViewer(UUID playerId) {
        SpawnerViewerInfo info = playerToSpawnerMap.remove(playerId);
        if (info != null) {
            SpawnerData spawner = info.spawnerData;
            Set<UUID> viewers = spawnerToPlayersMap.get(spawner.getSpawnerId());
            if (viewers != null) {
                viewers.remove(playerId);
                if (viewers.isEmpty()) {
                    spawnerToPlayersMap.remove(spawner.getSpawnerId());
                }
            }
        }

        // Also remove from main menu tracking if present
        SpawnerViewerInfo mainMenuInfo = mainMenuViewers.remove(playerId);
        if (mainMenuInfo != null) {
            SpawnerData spawner = mainMenuInfo.spawnerData;
            Set<UUID> mainMenuViewerSet = spawnerToMainMenuViewers.get(spawner.getSpawnerId());
            if (mainMenuViewerSet != null) {
                mainMenuViewerSet.remove(playerId);
                if (mainMenuViewerSet.isEmpty()) {
                    spawnerToMainMenuViewers.remove(spawner.getSpawnerId());
                }
            }
        }

        // Also remove from filter viewer tracking if present
        if (info != null) {
            String spawnerId = info.spawnerData.getSpawnerId();
            Set<UUID> filterViewers = spawnerToFilterViewersMap.get(spawnerId);
            if (filterViewers != null) {
                filterViewers.remove(playerId);
                if (filterViewers.isEmpty()) {
                    spawnerToFilterViewersMap.remove(spawnerId);
                }
            }
        }

        // Also remove from pending updates and performance tracking
        pendingUpdates.remove(playerId);
        updateFlags.remove(playerId);
        lastTimerUpdate.remove(playerId);
        lastTimerValue.remove(playerId);

        // Stop update task only when no viewers remain at all (for any GUI type)
        if (playerToSpawnerMap.isEmpty() && isTaskRunning) {
            stopUpdateTask();
        }
    }

    public Set<Player> getViewers(String spawnerId) {
        Set<UUID> viewerIds = spawnerToPlayersMap.get(spawnerId);
        if (viewerIds == null || viewerIds.isEmpty()) {
            return Collections.emptySet();
        }

        // More efficient collection of online players
        Set<Player> onlineViewers = new HashSet<>(viewerIds.size());
        for (UUID id : viewerIds) {
            Player player = Bukkit.getPlayer(id);
            if (player != null && player.isOnline()) {
                onlineViewers.add(player);
            }
        }
        return onlineViewers;
    }

    public boolean hasViewers(SpawnerData spawner) {
        Set<UUID> viewers = spawnerToPlayersMap.get(spawner.getSpawnerId());
        return viewers != null && !viewers.isEmpty();
    }

    public void clearAllTrackedGuis() {
        playerToSpawnerMap.clear();
        spawnerToPlayersMap.clear();
        mainMenuViewers.clear();
        spawnerToMainMenuViewers.clear();
        spawnerToFilterViewersMap.clear();
        pendingUpdates.clear();
        updateFlags.clear();
        lastTimerUpdate.clear();
        lastTimerValue.clear();
    }

    /**
     * Initialize all GUI slot positions from the current layout configuration.
     * This is called once during construction and again when layout is reloaded
     * for optimal performance.
     */
    private void initializeSlotPositions() {
        // Get the current layout
        GuiLayout layout = plugin.getGuiLayoutConfig().getCurrentMainLayout();
        if (layout == null) {
            // Set all slots to -1 if no layout is available
            cachedStorageSlot = -1;
            cachedExpSlot = -1;
            cachedSpawnerInfoSlot = -1;
            return;
        }

        // Initialize storage slot
        GuiButton storageButton = layout.getButton("storage");
        cachedStorageSlot = storageButton != null ? storageButton.getSlot() : -1;

        // Initialize exp slot
        GuiButton expButton = layout.getButton("exp");
        cachedExpSlot = expButton != null ? expButton.getSlot() : -1;

        // Initialize spawner info slot using the same logic as SpawnerMenuUI
        GuiButton spawnerInfoButton = null;

        // Check for shop integration to determine which button to use
        if (plugin.hasSellIntegration()) {
            spawnerInfoButton = layout.getButton("spawner_info_with_shop");
        }

        if (spawnerInfoButton == null) {
            spawnerInfoButton = layout.getButton("spawner_info_no_shop");
        }

        if (spawnerInfoButton == null) {
            spawnerInfoButton = layout.getButton("spawner_info");
        }

        cachedSpawnerInfoSlot = spawnerInfoButton != null ? spawnerInfoButton.getSlot() : -1;
    }

    /**
     * Get the storage slot from the cached layout configuration.
     *
     * @return the slot number for the storage button, or -1 if not found
     */
    private int getStorageSlot() {
        return cachedStorageSlot;
    }

    /**
     * Get the exp slot from the cached layout configuration.
     *
     * @return the slot number for the exp button, or -1 if not found
     */
    private int getExpSlot() {
        return cachedExpSlot;
    }

    /**
     * Get the spawner info slot from the cached layout configuration.
     *
     * @return the slot number for the spawner info button, or -1 if not found
     */
    private int getSpawnerInfoSlot() {
        return cachedSpawnerInfoSlot;
    }

    /**
     * Clear all cached slot positions and re-initialize them when GUI layout changes.
     * This method is called when layout configuration is reloaded.
     */
    public void clearSlotCache() {
        // Re-initialize all slot positions from the updated layout
        initializeSlotPositions();
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInventoryOpen(InventoryOpenEvent event) {
        if (!(event.getPlayer() instanceof Player player)) return;

        InventoryHolder holder = event.getInventory().getHolder(false);
        if (!isValidHolder(holder)) return;

        UUID playerId = player.getUniqueId();
        SpawnerData spawnerData = null;
        ViewerType viewerType = null;

        if (holder instanceof SpawnerMenuHolder spawnerHolder) {
            spawnerData = spawnerHolder.getSpawnerData();
            viewerType = ViewerType.MAIN_MENU;
        } else if (holder instanceof StoragePageHolder storageHolder) {
            spawnerData = storageHolder.getSpawnerData();
            viewerType = ViewerType.STORAGE;
        } else if (holder instanceof FilterConfigHolder filterHolder) {
            spawnerData = filterHolder.getSpawnerData();
            viewerType = ViewerType.FILTER;
        }

        if (spawnerData != null && viewerType != null) {
            trackViewer(playerId, spawnerData, viewerType);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onInventoryClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player player)) return;

        // Get the spawner data before untracking to add interaction tracking
        SpawnerViewerInfo info = playerToSpawnerMap.get(player.getUniqueId());
        if (info != null && info.spawnerData != null) {
            // Track player interaction for last interaction field when closing spawner GUI
            info.spawnerData.updateLastInteractedPlayer(player.getName());
        }

        // Simply untrack - no need for complex location-based logic here
        untrackViewer(player.getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        untrackViewer(event.getPlayer().getUniqueId());
    }

    // ===============================================================
    //                      Update Logic
    // ===============================================================

    private void updateGuiForSpawnerInfo() {
        // Always process batched updates first (storage, exp, etc.) regardless of timer placeholders
        processPendingUpdates();

        // Skip timer-specific processing if GUI doesn't use timer placeholders
        if (!isTimerPlaceholdersEnabled()) {
            // Check if we should stop the task (only if no viewers at all)
            if (playerToSpawnerMap.isEmpty()) {
                stopUpdateTask();
            }
            return;
        }

        // Only process main menu viewers for timer updates
        if (mainMenuViewers.isEmpty()) {
            // Check if we should stop the task (only if no viewers at all)
            if (playerToSpawnerMap.isEmpty()) {
                stopUpdateTask();
            }
            return;
        }

        long currentTime = System.currentTimeMillis();

        // Group main menu viewers by spawner to optimize timer calculations for multiple players viewing same spawner
        Map<String, List<UUID>> spawnerViewers = new HashMap<>();

        for (Map.Entry<UUID, SpawnerViewerInfo> entry : mainMenuViewers.entrySet()) {
            UUID playerId = entry.getKey();
            SpawnerViewerInfo viewerInfo = entry.getValue();
            SpawnerData spawner = viewerInfo.spawnerData;
            String spawnerId = spawner.getSpawnerId();

            Player player = Bukkit.getPlayer(playerId);
            if (!isValidGuiSession(player)) {
                untrackViewer(playerId);
                continue;
            }

            // Additional check: ensure player actually has main menu open
            Inventory openInventory = player.getOpenInventory().getTopInventory();
            if (openInventory == null || !(openInventory.getHolder(false) instanceof SpawnerMenuHolder)) {
                // Player switched to different inventory type or closed, untrack from main menu
                untrackViewer(playerId);
                continue;
            }

            // Performance optimization: skip update if we updated recently
            Long lastUpdate = lastTimerUpdate.get(playerId);
            if (lastUpdate != null && (currentTime - lastUpdate) < 800) { // 800ms minimum between updates
                continue;
            }

            spawnerViewers.computeIfAbsent(spawnerId, k -> new ArrayList<>()).add(playerId);
        }

        int processedPlayers = 0;

        // Process spawners in batches - calculate timer once per spawner, apply to all viewers
        for (Map.Entry<String, List<UUID>> spawnerGroup : spawnerViewers.entrySet()) {
            String spawnerId = spawnerGroup.getKey();
            List<UUID> viewers = spawnerGroup.getValue();

            if (viewers.isEmpty()) continue;

            // Get spawner data from first viewer
            UUID firstViewer = viewers.get(0);
            SpawnerViewerInfo viewerInfo = mainMenuViewers.get(firstViewer);
            if (viewerInfo == null) continue;

            SpawnerData spawner = viewerInfo.spawnerData;

            // Calculate timer value once for this spawner
            long timeUntilNextSpawn = calculateTimeUntilNextSpawn(spawner);
            String newTimerValue;

            if (spawner.getIsAtCapacity()) {
                newTimerValue = cachedFullText;
            } else if (timeUntilNextSpawn == -1) {
                newTimerValue = cachedInactiveText;
            } else {
                newTimerValue = formatTime(timeUntilNextSpawn);
            }

            // Apply to all viewers of this spawner
            for (UUID playerId : viewers) {
                // Batch limit for performance
                if (processedPlayers >= MAX_PLAYERS_PER_BATCH) {
                    break;
                }

                // Race condition prevention: double-check that player is still in main menu viewers
                if (!mainMenuViewers.containsKey(playerId)) {
                    continue; // Player was removed by another thread
                }

                Player player = Bukkit.getPlayer(playerId);
                if (!isValidGuiSession(player)) {
                    untrackViewer(playerId);
                    continue;
                }

                // Check if timer value actually changed for this player
                String lastValue = lastTimerValue.get(playerId);
                if (lastValue != null && lastValue.equals(newTimerValue)) {
                    continue; // Skip if timer hasn't changed
                }

                // Update tracking atomically to prevent race conditions
                lastTimerUpdate.put(playerId, currentTime);
                lastTimerValue.put(playerId, newTimerValue);

                processedPlayers++;

                // Using location to make sure we're on the correct region thread
                Location playerLocation = player.getLocation();
                if (playerLocation != null) {
                    final String finalTimerValue = newTimerValue;
                    final UUID finalPlayerId = playerId; // Capture for thread safety

                    // This is the key fix: use location-based scheduling to ensure we're on the right thread
                    Scheduler.runLocationTask(playerLocation, () -> {
                        // Final validation that player is still online and tracked
                        if (!player.isOnline() || !mainMenuViewers.containsKey(finalPlayerId)) return;

                        Inventory openInventory = player.getOpenInventory().getTopInventory();
                        if (openInventory == null) return;

                        InventoryHolder holder = openInventory.getHolder(false);

                        if (holder instanceof SpawnerMenuHolder) {
                            int spawnerInfoSlot = getSpawnerInfoSlot();
                            if (spawnerInfoSlot >= 0) {
                                updateSpawnerInfoItemTimerOptimized(openInventory, spawner, finalTimerValue, spawnerInfoSlot);
                                // Force inventory update to ensure changes are visible to the player
                                player.updateInventory();
                            }
                        } else {
                            // Player no longer has main menu open, remove from main menu tracking
                            untrackViewer(finalPlayerId);
                        }
                    });
                }
            }

            // Break early if we've processed enough players
            if (processedPlayers >= MAX_PLAYERS_PER_BATCH) {
                break;
            }
        }
    }

    private void processPendingUpdates() {
        if (pendingUpdates.isEmpty()) return;

        // Create a copy to avoid concurrent modification
        Set<UUID> currentUpdates = new HashSet<>(pendingUpdates);
        pendingUpdates.clear();

        for (UUID playerId : currentUpdates) {
            Player player = Bukkit.getPlayer(playerId);
            if (!isValidGuiSession(player)) {
                untrackViewer(playerId);
                updateFlags.remove(playerId);
                continue;
            }

            SpawnerViewerInfo info = playerToSpawnerMap.get(playerId);
            if (info == null) {
                updateFlags.remove(playerId);
                continue;
            }

            int flags = updateFlags.getOrDefault(playerId, UPDATE_ALL);
            updateFlags.remove(playerId);

            Location loc = player.getLocation();
            if (loc != null) {
                final int finalFlags = flags;
                final SpawnerData spawner = info.spawnerData;

                Scheduler.runLocationTask(loc, () -> {
                    if (!player.isOnline()) return;

                    Inventory openInv = player.getOpenInventory().getTopInventory();
                    if (openInv == null || !(openInv.getHolder(false) instanceof SpawnerMenuHolder)) return;

                    processInventoryUpdate(player, openInv, spawner, finalFlags);
                });
            }
        }
    }

    private void processInventoryUpdate(Player player, Inventory inventory, SpawnerData spawner, int flags) {
        boolean needsUpdate = false;

        if ((flags & UPDATE_CHEST) != 0) {
            int storageSlot = getStorageSlot();
            if (storageSlot >= 0) {
                updateChestItem(inventory, spawner, storageSlot);
                needsUpdate = true;
            }
        }

        if ((flags & UPDATE_INFO) != 0) {
            int spawnerInfoSlot = getSpawnerInfoSlot();
            if (spawnerInfoSlot >= 0) {
                updateSpawnerInfoItem(inventory, spawner, player, spawnerInfoSlot);
                needsUpdate = true;
            }
        }

        if ((flags & UPDATE_EXP) != 0) {
            int expSlot = getExpSlot();
            if (expSlot >= 0) {
                updateExpItem(inventory, spawner, expSlot);
                needsUpdate = true;
            }
        }

        if (needsUpdate) {
            player.updateInventory();
        }
    }

    // ===============================================================
    //                      Public Timer Update Method
    // ===============================================================

    /**
     * Forces an immediate timer update when spawner state changes.
     * This is called when spawner transitions from inactive to active or vice versa.
     * Only updates main menu viewers since they're the ones that need timer updates.
     *
     * @param spawner The spawner whose state has changed
     */
    public void forceStateChangeUpdate(SpawnerData spawner) {
        // Skip timer updates if GUI doesn't use timer placeholders
        if (!isTimerPlaceholdersEnabled()) {
            return;
        }

        Set<UUID> mainMenuViewerSet = spawnerToMainMenuViewers.get(spawner.getSpawnerId());
        if (mainMenuViewerSet == null || mainMenuViewerSet.isEmpty()) return;

        // Clear previous timer values to force refresh for main menu viewers only
        for (UUID viewerId : mainMenuViewerSet) {
            lastTimerUpdate.remove(viewerId);
            lastTimerValue.remove(viewerId);
        }

        // Update immediately - but only for main menu viewers
        updateMainMenuViewers(spawner);
    }

    /**
     * Updates only main menu viewers for immediate timer refresh.
     * This is a lightweight version that only processes viewers who need timer updates.
     */
    private void updateMainMenuViewers(SpawnerData spawner) {
        // Skip timer updates if GUI doesn't use timer placeholders
        if (!isTimerPlaceholdersEnabled()) {
            return;
        }

        Set<UUID> mainMenuViewerSet = spawnerToMainMenuViewers.get(spawner.getSpawnerId());
        if (mainMenuViewerSet == null || mainMenuViewerSet.isEmpty()) return;

        // Calculate timer value once for all main menu viewers
        long timeUntilNextSpawn = calculateTimeUntilNextSpawn(spawner);
        String timerValue;

        if (spawner.getIsAtCapacity()) {
            timerValue = cachedFullText;
        } else if (timeUntilNextSpawn == -1) {
            timerValue = cachedInactiveText;
        } else {
            timerValue = formatTime(timeUntilNextSpawn);
        }

        // Apply to all main menu viewers
        for (UUID viewerId : new HashSet<>(mainMenuViewerSet)) { // Copy to avoid concurrent modification
            Player viewer = Bukkit.getPlayer(viewerId);
            if (!isValidGuiSession(viewer)) {
                untrackViewer(viewerId);
                continue;
            }

            Location loc = viewer.getLocation();
            if (loc != null) {
                final String finalTimerValue = timerValue;
                final UUID finalViewerId = viewerId;

                Scheduler.runLocationTask(loc, () -> {
                    if (!viewer.isOnline() || !mainMenuViewers.containsKey(finalViewerId)) return;

                    Inventory openInv = viewer.getOpenInventory().getTopInventory();
                    if (openInv == null || !(openInv.getHolder(false) instanceof SpawnerMenuHolder)) {
                        untrackViewer(finalViewerId);
                        return;
                    }

                    updateSpawnerInfoItemTimerOptimized(openInv, spawner, finalTimerValue, getSpawnerInfoSlot());
                    viewer.updateInventory();
                });
            }
        }
    }

    /**
     * Forces an immediate timer update for a specific player's spawner GUI.
     * This is used when opening a new GUI to ensure the timer displays immediately.
     *
     * @param player The player whose GUI should be updated
     * @param spawner The spawner data for the timer calculation
     */
    public void forceTimerUpdate(Player player, SpawnerData spawner) {
        // Skip timer updates if GUI doesn't use timer placeholders
        if (hasTimerPlaceholders != null && !hasTimerPlaceholders) {
            return;
        }

        if (!isValidGuiSession(player)) return;

        Location playerLocation = player.getLocation();
        if (playerLocation == null) return;

        // Schedule the timer update on the appropriate thread
        Scheduler.runLocationTask(playerLocation, () -> {
            if (!player.isOnline()) return;

            Inventory openInventory = player.getOpenInventory().getTopInventory();
            if (openInventory == null) return;

            InventoryHolder holder = openInventory.getHolder(false);
            if (holder instanceof SpawnerMenuHolder) {
                int spawnerInfoSlot = getSpawnerInfoSlot();
                if (spawnerInfoSlot >= 0) {
                    updateSpawnerInfoItemTimer(openInventory, spawner, spawnerInfoSlot);
                    // Force inventory update to ensure changes are visible immediately
                    player.updateInventory();
                }
            }
        });
    }

    // ===============================================================
    //                      Main GUI Update
    // ===============================================================

    /**
     * Optimized version of updateSpawnerMenuViewers - The main entry point for other classes
     * to trigger updates to spawner menus.
     *
     * @param spawner The spawner data that has been updated
     */
    public void updateSpawnerMenuViewers(SpawnerData spawner) {
        Set<UUID> viewers = spawnerToPlayersMap.get(spawner.getSpawnerId());
        if (viewers == null || viewers.isEmpty()) return;

        // Invalidate cache for this spawner
        if (plugin.getSpawnerMenuUI() != null) {
            plugin.getSpawnerMenuUI().invalidateSpawnerCache(spawner.getSpawnerId());
        }

        if (plugin.getSpawnerMenuFormUI() != null) {
            plugin.getSpawnerMenuFormUI().invalidateSpawnerCache(spawner.getSpawnerId());
        }

        int viewerCount = viewers.size();
        if (viewerCount > 10) {
            plugin.debug(viewerCount + " spawner menu viewers to update for " + spawner.getSpawnerId() + " (batch update)");
        }

        // For storage viewers we need to calculate pages
        int oldTotalPages = -1;
        int newTotalPages = -1;

        // Schedule updates for all viewers - but batch them efficiently
        for (UUID viewerId : viewers) {
            Player viewer = Bukkit.getPlayer(viewerId);
            if (!isValidGuiSession(viewer)) {
                untrackViewer(viewerId);
                continue;
            }

            // Add to batch update with appropriate flags
            pendingUpdates.add(viewerId);
            updateFlags.put(viewerId, UPDATE_ALL);

            // For efficiency, we'll check holder types once in a separate thread for large batches
            if (viewerCount <= 5) {
                // For small numbers of viewers, check inventory type now to determine storage page updates
                Inventory openInv = viewer.getOpenInventory().getTopInventory();
                if (openInv != null && openInv.getHolder(false) instanceof StoragePageHolder) {
                    // Calculate pages only when needed
                    if (oldTotalPages == -1) {
                        StoragePageHolder holder = (StoragePageHolder) openInv.getHolder(false);
                        oldTotalPages = calculateTotalPages(holder.getOldUsedSlots());
                        newTotalPages = calculateTotalPages(spawner.getVirtualInventory().getUsedSlots());
                    }

                    // Schedule storage update on player's thread
                    processStorageUpdate(viewer, spawner, oldTotalPages, newTotalPages);
                }
            }
        }

        // For larger batches, we'll handle storage viewers in a separate pass
        if (viewerCount > 5) {
            for (UUID viewerId : viewers) {
                Player viewer = Bukkit.getPlayer(viewerId);
                if (!isValidGuiSession(viewer)) continue;

                // Run on player's thread to check inventory type
                Location loc = viewer.getLocation();
                if (loc != null) {
                    Scheduler.runLocationTask(loc, () -> {
                        if (!viewer.isOnline()) return;

                        Inventory openInv = viewer.getOpenInventory().getTopInventory();
                        if (openInv != null && openInv.getHolder(false) instanceof StoragePageHolder) {
                            // Calculate pages only when needed (but within player's thread)
                            StoragePageHolder holder = (StoragePageHolder) openInv.getHolder(false);
                            int oldPages = calculateTotalPages(holder.getOldUsedSlots());
                            int newPages = calculateTotalPages(spawner.getVirtualInventory().getUsedSlots());

                            // Process the storage update directly since we're already in player's thread
                            processStorageUpdateDirect(viewer, openInv, spawner, holder, oldPages, newPages);
                        }
                    });
                }
            }
        }
    }

    public void processStorageUpdate(Player viewer, SpawnerData spawner, int oldTotalPages, int newTotalPages) {
        Location loc = viewer.getLocation();
        if (loc != null) {
            Scheduler.runLocationTask(loc, () -> {
                if (!viewer.isOnline()) return;

                Inventory openInv = viewer.getOpenInventory().getTopInventory();
                if (openInv == null || !(openInv.getHolder(false) instanceof StoragePageHolder)) return;

                StoragePageHolder holder = (StoragePageHolder) openInv.getHolder(false);
                processStorageUpdateDirect(viewer, openInv, spawner, holder, oldTotalPages, newTotalPages);
            });
        }
    }

    private void processStorageUpdateDirect(Player viewer, Inventory inventory, SpawnerData spawner,
                                           StoragePageHolder holder, int oldTotalPages, int newTotalPages) {
        // Check if we need a new inventory
        boolean pagesChanged = oldTotalPages != newTotalPages;
        int currentPage = holder.getCurrentPage();
        boolean needsNewInventory = false;
        int targetPage = currentPage;

        // Determine if we need a new inventory
        if (currentPage > newTotalPages) {
            // if current page is out of bounds, set to last page
            targetPage = newTotalPages;
            holder.setCurrentPage(targetPage);
            needsNewInventory = true;
        } else if (pagesChanged) {
            // If total pages changed but current page is still valid, update current page
            needsNewInventory = true;
        }

        if (needsNewInventory) {
            try {
                // Update inventory title and contents
                String newTitle = languageManager.getGuiTitle("gui_title_storage") + " - [" + targetPage + "/" + newTotalPages + "]";
                viewer.getOpenInventory().setTitle(newTitle);
                spawnerStorageUI.updateDisplay(inventory, spawner, targetPage, newTotalPages);
            } catch (Exception e) {
                // Fall back to creating a new inventory
                Inventory newInv = spawnerStorageUI.createInventory(
                        spawner,
                        languageManager.getGuiTitle("gui_title_storage"),
                        targetPage,
                        newTotalPages
                );
                viewer.closeInventory();
                viewer.openInventory(newInv);
            }
        } else {
            // Just update contents of current inventory
            spawnerStorageUI.updateDisplay(inventory, spawner, targetPage, newTotalPages);
            viewer.updateInventory();
        }
    }

    private int calculateTotalPages(int totalItems) {
        return totalItems <= 0 ? 1 : (int) Math.ceil((double) totalItems / ITEMS_PER_PAGE);
    }

    public void updateSpawnerMenuGui(Player player, SpawnerData spawner, boolean forceUpdate) {
        // Add to batch update instead of immediate processing
        pendingUpdates.add(player.getUniqueId());
        updateFlags.put(player.getUniqueId(), UPDATE_ALL);
    }

    private void updateSpawnerInfoItem(Inventory inventory, SpawnerData spawner, Player player, int spawnerInfoSlot) {
        if (spawnerInfoSlot < 0) return;

        // Get the current spawner info item from the inventory
        ItemStack currentSpawnerItem = inventory.getItem(spawnerInfoSlot);
        if (currentSpawnerItem == null || !currentSpawnerItem.hasItemMeta()) return;

        // Create a freshly generated spawner info item using the method from SpawnerMenuUI
        ItemStack newSpawnerItem = spawnerMenuUI.createSpawnerInfoItem(player, spawner);

        // If the new item is different from current item, update it
        if (!ItemUpdater.areItemsEqual(currentSpawnerItem, newSpawnerItem)) {
            // Before replacing the item, check if we need to preserve timer info
            preserveTimerInfo(currentSpawnerItem, newSpawnerItem);

            // Update the item in the inventory
            inventory.setItem(spawnerInfoSlot, newSpawnerItem);
        }
    }

    private void preserveTimerInfo(ItemStack currentItem, ItemStack newItem) {
        ItemMeta currentMeta = currentItem.getItemMeta();
        ItemMeta newMeta = newItem.getItemMeta();

        if (currentMeta == null || !currentMeta.hasLore() || newMeta == null || !newMeta.hasLore()) {
            return;
        }

        List<String> currentLore = currentMeta.getLore();
        List<String> newLore = newMeta.getLore();

        if (currentLore == null || newLore == null) return;

        // Find the current timer value by looking for lines that don't have the %time% placeholder
        // but were previously processed (meaning they had %time% before)
        String currentTimerValue = null;
        int currentTimerLineIndex = -1;

        // First, find the line in new lore that has %time% placeholder
        int newTimerLineIndex = -1;
        for (int i = 0; i < newLore.size(); i++) {
            if (newLore.get(i).contains("%time%")) {
                newTimerLineIndex = i;
                break;
            }
        }

        // If there's no %time% placeholder in new lore, nothing to preserve
        if (newTimerLineIndex == -1) return;

        // Check if the corresponding line in current lore has been processed (no %time% but same structure)
        if (newTimerLineIndex < currentLore.size()) {
            String currentLine = currentLore.get(newTimerLineIndex);
            String newLine = newLore.get(newTimerLineIndex);

            // If current line doesn't have %time% but new line does, extract the timer value
            if (!currentLine.contains("%time%") && newLine.contains("%time%")) {
                // Find the timer value by comparing the structure
                String newLineTemplate = newLine.replace("%time%", "TIMER_PLACEHOLDER");
                String cleanNewTemplate = ChatColor.stripColor(newLineTemplate);
                String cleanCurrentLine = ChatColor.stripColor(currentLine);

                // Extract timer value by finding what replaced the placeholder
                int placeholderIndex = cleanNewTemplate.indexOf("TIMER_PLACEHOLDER");
                if (placeholderIndex >= 0 && cleanCurrentLine.length() >= placeholderIndex) {
                    String beforePlaceholder = cleanNewTemplate.substring(0, placeholderIndex);
                    String afterPlaceholder = cleanNewTemplate.substring(placeholderIndex + "TIMER_PLACEHOLDER".length());

                    if (cleanCurrentLine.startsWith(beforePlaceholder) && cleanCurrentLine.endsWith(afterPlaceholder)) {
                        int startIndex = beforePlaceholder.length();
                        int endIndex = cleanCurrentLine.length() - afterPlaceholder.length();
                        if (endIndex > startIndex) {
                            currentTimerValue = cleanCurrentLine.substring(startIndex, endIndex).trim();
                        }
                    }
                }
            }
        }

        // If we found a timer value, apply it to the new item
        if (currentTimerValue != null && !currentTimerValue.isEmpty()) {
            Map<String, String> timerPlaceholder = Collections.singletonMap("time", currentTimerValue);
            List<String> updatedLore = new ArrayList<>(newLore.size());

            for (String line : newLore) {
                updatedLore.add(languageManager.applyOnlyPlaceholders(line, timerPlaceholder));
            }

            newMeta.setLore(updatedLore);
            newItem.setItemMeta(newMeta);
        }
    }

    /**
     * Optimized version of updateSpawnerInfoItemTimer that accepts pre-calculated timer value
     * to avoid redundant calculations and improve performance.
     */
    private void updateSpawnerInfoItemTimerOptimized(Inventory inventory, SpawnerData spawner, String timeDisplay, int spawnerInfoSlot) {
        // Skip timer updates if GUI doesn't use timer placeholders
        if (!isTimerPlaceholdersEnabled()) {
            return;
        }

        if (spawnerInfoSlot < 0) return;

        ItemStack spawnerItem = inventory.getItem(spawnerInfoSlot);
        if (spawnerItem == null || !spawnerItem.hasItemMeta()) return;

        ItemMeta meta = spawnerItem.getItemMeta();
        if (meta == null || !meta.hasLore()) return;

        List<String> lore = meta.getLore();
        if (lore == null) return;

        // Find and update the timer line - handle both placeholder and already processed lines
        boolean needsUpdate = false;
        List<String> updatedLore = new ArrayList<>(lore.size());

        for (String line : lore) {
            if (line.contains("%time%")) {
                // This line has the placeholder - replace it with timer display
                String newLine = line.replace("%time%", timeDisplay);
                updatedLore.add(newLine);
                needsUpdate = true;
            } else {
                // Check if this line was previously processed and contains timer info
                String updatedLine = updateExistingTimerLine(line, timeDisplay);
                if (!updatedLine.equals(line)) {
                    updatedLore.add(updatedLine);
                    needsUpdate = true;
                } else {
                    updatedLore.add(line);
                }
            }
        }

        // Only update the inventory item if we actually changed the timer line
        if (needsUpdate) {
            meta.setLore(updatedLore);
            spawnerItem.setItemMeta(meta);
            // Update the inventory directly to ensure changes are applied
            inventory.setItem(spawnerInfoSlot, spawnerItem);
        }
    }

    private void updateSpawnerInfoItemTimer(Inventory inventory, SpawnerData spawner, int spawnerInfoSlot) {
        // Skip timer updates if GUI doesn't use timer placeholders
        if (!isTimerPlaceholdersEnabled()) {
            return;
        }

        if (spawnerInfoSlot < 0) return;

        ItemStack spawnerItem = inventory.getItem(spawnerInfoSlot);
        if (spawnerItem == null || !spawnerItem.hasItemMeta()) return;

        ItemMeta meta = spawnerItem.getItemMeta();
        if (meta == null || !meta.hasLore()) return;

        List<String> lore = meta.getLore();
        if (lore == null) return;

        // Calculate time until next spawn
        long timeUntilNextSpawn = calculateTimeUntilNextSpawn(spawner);
        String timeDisplay;

        // Check if spawner is at capacity and override display message
        if (spawner.getIsAtCapacity()) {
            timeDisplay = cachedFullText;
        } else if (timeUntilNextSpawn == -1) {
            timeDisplay = cachedInactiveText;
        } else {
            timeDisplay = formatTime(timeUntilNextSpawn);
        }

        // Find and update the timer line - handle both placeholder and already processed lines
        boolean needsUpdate = false;
        List<String> updatedLore = new ArrayList<>(lore.size());

        for (String line : lore) {
            if (line.contains("%time%")) {
                // This line has the placeholder - replace it with timer display
                String newLine = line.replace("%time%", timeDisplay);
                updatedLore.add(newLine);
                needsUpdate = true;
            } else {
                // Check if this line was previously processed and contains timer info
                // Look for lines that match the timer pattern (contains time format like "01:30", "00:45", etc.)
                String updatedLine = updateExistingTimerLine(line, timeDisplay);
                if (!updatedLine.equals(line)) {
                    updatedLore.add(updatedLine);
                    needsUpdate = true;
                } else {
                    updatedLore.add(line);
                }
            }
        }

        // Only update the inventory item if we actually changed the timer line
        if (needsUpdate) {
            meta.setLore(updatedLore);
            spawnerItem.setItemMeta(meta);
            // Update the inventory directly to ensure changes are applied
            inventory.setItem(spawnerInfoSlot, spawnerItem);
        }
    }

    /**
     * Updates an existing timer line by replacing the old timer value with the new one.
     * This handles lines that were previously processed and no longer contain %time% placeholder.
     * Enhanced to handle all state transitions including inactive/active changes.
     */
    private String updateExistingTimerLine(String line, String newTimeDisplay) {
        // Pattern to match timer formats: "01:30", "00:45", etc. or status messages
        String strippedLine = org.bukkit.ChatColor.stripColor(line);
        String strippedNewDisplay = org.bukkit.ChatColor.stripColor(newTimeDisplay);

        // Look for time patterns (mm:ss format) or our cached status messages
        if (strippedLine.matches(".*\\d{2}:\\d{2}.*") ||
            strippedLine.contains(org.bukkit.ChatColor.stripColor(cachedInactiveText)) ||
            strippedLine.contains(org.bukkit.ChatColor.stripColor(cachedFullText))) {

            // This looks like a timer line - we need to replace the timer portion

            // For time format (mm:ss), replace it
            String updatedLine = line.replaceAll("\\d{2}:\\d{2}", newTimeDisplay);
            if (!updatedLine.equals(line)) {
                return updatedLine;
            }

            // For status messages, replace the entire status portion
            String strippedCachedInactive = org.bukkit.ChatColor.stripColor(cachedInactiveText);
            String strippedCachedFull = org.bukkit.ChatColor.stripColor(cachedFullText);

            if (strippedLine.contains(strippedCachedInactive)) {
                // Replace the inactive status with new time display
                return line.replace(cachedInactiveText, newTimeDisplay);
            } else if (strippedLine.contains(strippedCachedFull)) {
                // Replace the full status with new time display  
                return line.replace(cachedFullText, newTimeDisplay);
            }
        }

        // Additional check: if the new display is a status message, see if we need to replace timer format
        if (strippedNewDisplay.equals(org.bukkit.ChatColor.stripColor(cachedInactiveText)) ||
            strippedNewDisplay.equals(org.bukkit.ChatColor.stripColor(cachedFullText))) {

            // If line contains timer format, replace it with status message
            if (strippedLine.matches(".*\\d{2}:\\d{2}.*")) {
                return line.replaceAll("\\d{2}:\\d{2}", newTimeDisplay);
            }
        }

        // No timer pattern found, return line unchanged
        return line;
    }

    private long calculateTimeUntilNextSpawn(SpawnerData spawner) {
        // Cache spawn delay calculation outside of lock
        long cachedDelay = spawner.getCachedSpawnDelay();
        if (cachedDelay == 0) {
            cachedDelay = spawner.getSpawnDelay() * 50L;
            spawner.setCachedSpawnDelay(cachedDelay);
        }

        long currentTime = System.currentTimeMillis();
        long lastSpawnTime = spawner.getLastSpawnTime();
        long timeElapsed = currentTime - lastSpawnTime;

        // Check if spawner is truly inactive (not just in initial stopped state)
        // This mirrors the logic from SpawnerMenuUI.calculateInitialTimerValue() to ensure consistency
        // between initial display and ongoing timer updates
        boolean isSpawnerInactive = !spawner.getSpawnerActive() ||
            (!spawner.isActivated() && timeElapsed > cachedDelay * 2); // Only inactive if stopped for more than 2 cycles

        if (isSpawnerInactive) {
            return -1;
        }

        long timeUntilNextSpawn = cachedDelay - timeElapsed;

        // Ensure we don't go below 0 or above the delay
        timeUntilNextSpawn = Math.max(0, Math.min(timeUntilNextSpawn, cachedDelay));

        // If the timer has expired, handle spawn timing
        if (timeUntilNextSpawn <= 0) {
            try {
                // Try to acquire lock with timeout to prevent deadlock
                if (spawner.getLock().tryLock(100, TimeUnit.MILLISECONDS)) {
                    try {
                        // Re-check timing after acquiring lock
                        currentTime = System.currentTimeMillis();
                        lastSpawnTime = spawner.getLastSpawnTime();
                        timeElapsed = currentTime - lastSpawnTime;

                        if (timeElapsed >= cachedDelay) {
                            // Update last spawn time to current time for next cycle
                            spawner.setLastSpawnTime(currentTime);

                            // Get the spawner location to schedule on the right region
                            Location spawnerLocation = spawner.getSpawnerLocation();
                            if (spawnerLocation != null) {
                                // Schedule activation on the appropriate region thread for Folia compatibility
                                Scheduler.runLocationTask(spawnerLocation, () -> {
                                    plugin.getRangeChecker().activateSpawner(spawner);
                                });
                            }
                            return cachedDelay; // Start new cycle with full delay
                        }

                        return cachedDelay - timeElapsed;
                    } finally {
                        spawner.getLock().unlock();
                    }
                } else {
                    // If can't acquire lock, return minimal time to try again soon
                    return 1000; // 1 second
                }
            } catch (InterruptedException e) {
                // Handle interruption
                Thread.currentThread().interrupt();
                return 1000; // 1 second
            }
        }

        return timeUntilNextSpawn;
    }

    private String formatTime(long milliseconds) {
        if (milliseconds <= 0) {
            return "00:00";
        }
        long seconds = milliseconds / 1000;
        long minutes = seconds / 60;
        seconds = seconds % 60;
        return String.format("%02d:%02d", minutes, seconds);
    }

    private void updateChestItem(Inventory inventory, SpawnerData spawner, int storageSlot) {
        if (storageSlot < 0) return;

        // Get the chest item from the inventory
        ItemStack currentChestItem = inventory.getItem(storageSlot);
        if (currentChestItem == null || !currentChestItem.hasItemMeta()) return;

        // Create a freshly generated chest item using the optimized method from SpawnerMenuUI
        ItemStack newChestItem = spawnerMenuUI.createLootStorageItem(spawner);

        // If the new item is different from current item, update it
        if (!ItemUpdater.areItemsEqual(currentChestItem, newChestItem)) {
            inventory.setItem(storageSlot, newChestItem);
        }
    }

    private void updateExpItem(Inventory inventory, SpawnerData spawner, int expSlot) {
        if (expSlot < 0) return;

        // Get the exp item from the inventory
        ItemStack currentExpItem = inventory.getItem(expSlot);
        if (currentExpItem == null || !currentExpItem.hasItemMeta()) return;

        // Create a freshly generated exp item using the method from SpawnerMenuUI
        ItemStack newExpItem = spawnerMenuUI.createExpItem(spawner);

        // If the new item is different from current item, update it
        if (!ItemUpdater.areItemsEqual(currentExpItem, newExpItem)) {
            inventory.setItem(expSlot, newExpItem);
        }
    }

    // ===============================================================
    //                      Utility Methods
    // ===============================================================

    public void closeAllViewersInventory(SpawnerData spawner) {
        String spawnerId = spawner.getSpawnerId();
        Set<Player> viewers = getViewers(spawnerId);
        if (!viewers.isEmpty()) {
            for (Player viewer : viewers) {
                if (viewer != null && viewer.isOnline()) {
                    viewer.closeInventory();
                }
            }
        }

        // Force close filter GUI viewers to prevent duplication exploits
        Set<UUID> filterViewers = spawnerToFilterViewersMap.get(spawnerId);
        if (filterViewers != null && !filterViewers.isEmpty()) {
            // Create a copy to avoid concurrent modification
            Set<UUID> filterViewersCopy = new HashSet<>(filterViewers);
            for (UUID viewerId : filterViewersCopy) {
                Player viewer = Bukkit.getPlayer(viewerId);
                if (viewer != null && viewer.isOnline()) {
                    // Check if they actually have a filter GUI open for this spawner
                    Inventory openInventory = viewer.getOpenInventory().getTopInventory();
                    if (openInventory != null && openInventory.getHolder(false) instanceof FilterConfigHolder filterHolder) {
                        if (filterHolder.getSpawnerData().getSpawnerId().equals(spawnerId)) {
                            viewer.closeInventory();
                        }
                    }
                }
            }
        }

        // Check for stacker viewers if that functionality exists
        if (plugin.getSpawnerStackerHandler() != null) {
            plugin.getSpawnerStackerHandler().closeAllViewersInventory(spawnerId);
        }
    }

    public void cleanup() {
        stopUpdateTask();
        clearAllTrackedGuis();
    }
}
