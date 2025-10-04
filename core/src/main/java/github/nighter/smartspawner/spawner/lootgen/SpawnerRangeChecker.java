package github.nighter.smartspawner.spawner.lootgen;

import github.nighter.smartspawner.SmartSpawner;
import github.nighter.smartspawner.spawner.properties.SpawnerManager;
import github.nighter.smartspawner.spawner.properties.SpawnerData;
import github.nighter.smartspawner.Scheduler;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * SpawnerRangeChecker manages spawner activation based on player proximity.
 * 
 * <h2>Purpose</h2>
 * <p>
 * This class monitors virtual spawners and activates/deactivates them based on whether
 * players are within the required range. This is essential for performance optimization
 * as it prevents inactive spawners from consuming resources.
 * </p>
 * 
 * <h2>How It Works</h2>
 * <ol>
 *   <li>Periodically checks all spawners (every 1 second)</li>
 *   <li>For each spawner, checks if any players are within {@link SpawnerData#getRequiredPlayerRange()}</li>
 *   <li>Updates spawner activation state via {@link SpawnerData#isActivated()}</li>
 *   <li>Starts/stops loot generation tasks based on activation state</li>
 * </ol>
 * 
 * <h2>Integration with Spawner Interface</h2>
 * <p>
 * Uses the standard Bukkit {@link org.bukkit.spawner.Spawner} interface methods:
 * </p>
 * <ul>
 *   <li>{@link SpawnerData#getRequiredPlayerRange()} - Distance check for activation</li>
 *   <li>{@link SpawnerData#isActivated()} - Current activation state</li>
 * </ul>
 * 
 * <h2>Thread Safety</h2>
 * <p>
 * Uses Folia-compatible region-specific scheduling to ensure thread safety when checking
 * for nearby players in the spawner's region.
 * </p>
 * 
 * @see SpawnerData
 * @see SpawnerLootGenerator
 */
public class SpawnerRangeChecker {
    private static final long CHECK_INTERVAL = 20L; // 1 second in ticks
    private final SmartSpawner plugin;
    private final SpawnerManager spawnerManager;
    private final SpawnerLootGenerator spawnerLootGenerator;
    private final Map<String, Scheduler.Task> spawnerTasks;
    private final Map<String, Set<UUID>> playersInRange;
    private boolean checkGhostSpawnersOnApproach;

    public SpawnerRangeChecker(SmartSpawner plugin) {
        this.plugin = plugin;
        this.spawnerManager = plugin.getSpawnerManager();
        this.spawnerLootGenerator = plugin.getSpawnerLootGenerator();
        this.spawnerTasks = new ConcurrentHashMap<>();
        this.playersInRange = new ConcurrentHashMap<>();
        this.checkGhostSpawnersOnApproach = plugin.getConfig().getBoolean("ghost_spawners.remove_on_approach", false);
        initializeRangeCheckTask();
    }

    public void reload() {
        this.checkGhostSpawnersOnApproach = plugin.getConfig().getBoolean("ghost_spawners.remove_on_approach", false);
    }

    private void initializeRangeCheckTask() {
        // Using the global scheduler, but only for coordinating region-specific checks
        Scheduler.runTaskTimer(() ->
                        spawnerManager.getAllSpawners().forEach(this::scheduleRegionSpecificCheck),
                CHECK_INTERVAL, CHECK_INTERVAL);
    }

    private void scheduleRegionSpecificCheck(SpawnerData spawner) {
        Location spawnerLoc = spawner.getSpawnerLocation();
        World world = spawnerLoc.getWorld();
        if (world == null) return;

        // Schedule the actual entity checking in the correct region
        Scheduler.runLocationTask(spawnerLoc, () -> {
            boolean playerFound = isPlayerInRange(spawner, spawnerLoc, world);
            boolean shouldBeActivated = playerFound;

            // Check if activation state needs to change
            if (spawner.isActivated() != shouldBeActivated) {
                spawner.setSpawnerStop(!shouldBeActivated);
                handleSpawnerStateChange(spawner, !shouldBeActivated);
            }
        });
    }

    private void updateSpawnerStatus(SpawnerData spawner) {
        Location spawnerLoc = spawner.getSpawnerLocation();
        World world = spawnerLoc.getWorld();
        if (world == null) return;

        boolean playerFound = isPlayerInRange(spawner, spawnerLoc, world);
        boolean shouldBeActivated = playerFound;

        // Check if activation state needs to change
        if (spawner.isActivated() != shouldBeActivated) {
            spawner.setSpawnerStop(!shouldBeActivated);
            handleSpawnerStateChange(spawner, !shouldBeActivated);
        }
    }

    private boolean isPlayerInRange(SpawnerData spawner, Location spawnerLoc, World world) {
        int range = spawner.getRequiredPlayerRange();
        double rangeSquared = range * range;

        // In Folia, we're now running this in the correct region thread,
        // so we can safely check for nearby entities
        Collection<Player> nearbyPlayers = world.getNearbyPlayers(spawnerLoc, range, range, range);

        for (Player player : nearbyPlayers) {
            if (player.getLocation().distanceSquared(spawnerLoc) <= rangeSquared) {
                return true;
            }
        }
        return false;
    }

    private void handleSpawnerStateChange(SpawnerData spawner, boolean shouldStop) {
        if (checkGhostSpawnersOnApproach) {
            boolean isGhost = spawnerManager.isGhostSpawner(spawner);
            if (isGhost) {
                plugin.debug("Ghost spawner detected during status update: " + spawner.getSpawnerId());
                spawnerManager.removeGhostSpawner(spawner.getSpawnerId());
                return; // Skip further processing
            }
        }
        if (!shouldStop) {
            activateSpawner(spawner);
        } else {
            deactivateSpawner(spawner);
        }
        
        // Force GUI update when spawner state changes
        if (plugin.getSpawnerGuiViewManager().hasViewers(spawner)) {
            plugin.getSpawnerGuiViewManager().forceStateChangeUpdate(spawner);
        }
    }

    public void activateSpawner(SpawnerData spawner) {
        startSpawnerTask(spawner);
        //plugin.debug("Spawner " + spawner.getSpawnerId() + " activated - Player in range");
    }

    private void deactivateSpawner(SpawnerData spawner) {
        stopSpawnerTask(spawner);
        //plugin.debug("Spawner " + spawner.getSpawnerId() + " deactivated - No players in range");
    }

    private void startSpawnerTask(SpawnerData spawner) {
        stopSpawnerTask(spawner);

        // Set lastSpawnTime to current time to start countdown immediately
        // This ensures timer shows full delay countdown when spawner activates
        long currentTime = System.currentTimeMillis();
        spawner.setLastSpawnTime(currentTime);
        
        Scheduler.Task task = Scheduler.runTaskTimer(() -> {
            if (spawner.isActivated()) {
                spawnerLootGenerator.spawnLootToSpawner(spawner);
            }
        }, spawner.getSpawnDelay(), spawner.getSpawnDelay()); // Start after one delay period

        spawnerTasks.put(spawner.getSpawnerId(), task);
        
        // Immediately update any open GUIs to show the countdown
        if (plugin.getSpawnerGuiViewManager().hasViewers(spawner)) {
            plugin.getSpawnerGuiViewManager().updateSpawnerMenuViewers(spawner);
        }
    }

    public void stopSpawnerTask(SpawnerData spawner) {
        Scheduler.Task task = spawnerTasks.remove(spawner.getSpawnerId());
        if (task != null) {
            task.cancel();
        }
    }

    public void cleanup() {
        spawnerTasks.values().forEach(Scheduler.Task::cancel);
        spawnerTasks.clear();
        playersInRange.clear();
    }
}