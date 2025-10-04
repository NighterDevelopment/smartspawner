package github.nighter.smartspawner.spawner.interactions.place;

import github.nighter.smartspawner.SmartSpawner;
import github.nighter.smartspawner.api.events.SpawnerPlaceEvent;
import github.nighter.smartspawner.extras.HopperHandler;
import github.nighter.smartspawner.hooks.protections.CheckStackBlock;
import github.nighter.smartspawner.language.MessageService;
import github.nighter.smartspawner.nms.ParticleWrapper;
import github.nighter.smartspawner.spawner.limits.ChunkSpawnerLimiter;
import github.nighter.smartspawner.spawner.properties.SpawnerData;
import github.nighter.smartspawner.spawner.properties.SpawnerManager;
import github.nighter.smartspawner.Scheduler;
import github.nighter.smartspawner.utils.SpawnerTypeChecker;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.block.CreatureSpawner;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class SpawnerPlaceListener implements Listener {
    private static final double PARTICLE_OFFSET = 0.5;
    private static final long PLACEMENT_COOLDOWN_MS = 100;

    private final SmartSpawner plugin;
    private final MessageService messageService;
    private final SpawnerManager spawnerManager;
    private final HopperHandler hopperHandler;
    private ChunkSpawnerLimiter chunkSpawnerLimiter;

    private final Map<UUID, Long> lastPlacementTime = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> playerItemCounts = new ConcurrentHashMap<>();

    public SpawnerPlaceListener(SmartSpawner plugin) {
        this.plugin = plugin;
        this.messageService = plugin.getMessageService();
        this.spawnerManager = plugin.getSpawnerManager();
        this.hopperHandler = plugin.getHopperHandler();
        this.chunkSpawnerLimiter = plugin.getChunkSpawnerLimiter();
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        cleanupPlayer(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onBlockPlace(BlockPlaceEvent event) {
        Block block = event.getBlock();

        if (block.getType() != Material.SPAWNER) {
            return;
        }

        Player player = event.getPlayer();
        ItemStack item = event.getItemInHand();
        ItemMeta meta = item.getItemMeta();

        if (!checkPlacementCooldown(player)) {
            event.setCancelled(true);
            return;
        }

        if (!(meta instanceof BlockStateMeta blockMeta)) {
            event.setCancelled(true);
            return;
        }

        if (!CheckStackBlock.CanPlayerPlaceBlock(player, block.getLocation())) {
            event.setCancelled(true);
            return;
        }

        boolean isVanillaSpawner = SpawnerTypeChecker.isVanillaSpawner(item);

        if (!verifyPlayerInventory(player, item, isVanillaSpawner)) {
            event.setCancelled(true);
            return;
        }

        int stackSize = calculateStackSize(player, item, isVanillaSpawner);

        if (!isVanillaSpawner) {
            if (!chunkSpawnerLimiter.canPlaceSpawner(player, block.getLocation()) ||
                    !chunkSpawnerLimiter.canStackSpawner(player, block.getLocation(), stackSize - 1)) {
                Map<String, String> placeholders = new HashMap<>();
                placeholders.put("limit", String.valueOf(chunkSpawnerLimiter.getMaxSpawnersPerChunk()));
                messageService.sendMessage(player, "spawner_chunk_limit_reached", placeholders);
                event.setCancelled(true);
                return;
            }
        }

        EntityType storedEntityType = null;
        if (blockMeta.hasBlockState() && blockMeta.getBlockState() instanceof CreatureSpawner) {
            storedEntityType = ((CreatureSpawner) blockMeta.getBlockState()).getSpawnedType();
        }

        if(SpawnerPlaceEvent.getHandlerList().getRegisteredListeners().length != 0) {
            SpawnerPlaceEvent e = new SpawnerPlaceEvent(player, block.getLocation(), storedEntityType, stackSize);
            Bukkit.getPluginManager().callEvent(e);
            if (e.isCancelled()) {
                event.setCancelled(true);
                return;
            }
        }

        if (!immediatelyConsumeItems(player, item, stackSize)) {
            event.setCancelled(true);
            return;
        }

        handleSpawnerSetup(block, player, storedEntityType, isVanillaSpawner, item, stackSize);
    }

    private boolean checkPlacementCooldown(Player player) {
        long currentTime = System.currentTimeMillis();
        Long lastTime = lastPlacementTime.get(player.getUniqueId());

        if (lastTime != null && (currentTime - lastTime) < PLACEMENT_COOLDOWN_MS) {
            return false;
        }

        lastPlacementTime.put(player.getUniqueId(), currentTime);
        return true;
    }

    private boolean verifyPlayerInventory(Player player, ItemStack item, boolean isVanillaSpawner) {
        if (player.getGameMode() == GameMode.CREATIVE) {
            return true;
        }

        if (isVanillaSpawner) {
            return item.getAmount() >= 1;
        }

        if (player.isSneaking()) {
            int requiredAmount = item.getAmount();

            int totalItems = 0;
            for (ItemStack invItem : player.getInventory().getContents()) {
                if (invItem != null && invItem.isSimilar(item)) {
                    totalItems += invItem.getAmount();
                }
            }

            return totalItems >= requiredAmount;
        }

        return item.getAmount() >= 1;
    }

    private boolean immediatelyConsumeItems(Player player, ItemStack item, int stackSize) {
        if (player.getGameMode() == GameMode.CREATIVE) {
            return true;
        }

        // For stack size 1, Bukkit will automatically consume the item, so we don't need to do anything
        if (stackSize <= 1) {
            return true;
        }

        ItemStack[] contents = player.getInventory().getContents();
        int remainingToConsume = stackSize;

        // Find and consume the additional items needed
        for (int i = 0; i < contents.length && remainingToConsume > 0; i++) {
            ItemStack slot = contents[i];
            if (slot != null && slot.isSimilar(item)) {
                int amountInSlot = slot.getAmount();
                int toRemove = Math.min(remainingToConsume, amountInSlot);

                if (toRemove >= amountInSlot) {
                    contents[i] = null;
                } else {
                    slot.setAmount(amountInSlot - toRemove);
                }

                remainingToConsume -= toRemove;
            }
        }

        if (remainingToConsume > 0) {
            plugin.debug("Could not consume enough items for player " + player.getName() +
                    ". Remaining: " + remainingToConsume + ", Stack size requested: " + stackSize);
            return false;
        }

        player.getInventory().setContents(contents);
        player.updateInventory();

        return true;
    }

    private int calculateStackSize(Player player, ItemStack item, boolean isVanillaSpawner) {
        if (isVanillaSpawner) {
            return 1;
        }

        if (player.isSneaking()) {
            return Math.min(item.getAmount(), getMaxAllowedStackSize());
        } else {
            return 1;
        }
    }

    private int getMaxAllowedStackSize() {
        return plugin.getConfig().getInt("spawner.max_stack_size", 64);
    }

    private void handleSpawnerSetup(Block block, Player player, EntityType entityType,
                                    boolean isVanillaSpawner, ItemStack item, int stackSize) {
        if (entityType == null || entityType == EntityType.UNKNOWN) {
            return;
        }

        CreatureSpawner spawner = (CreatureSpawner) block.getState();

        if (isVanillaSpawner) {
            spawner.setSpawnedType(entityType);
            spawner.update(true, false);
            return;
        }

        Scheduler.runLocationTaskLater(block.getLocation(), () -> {
            if (block.getType() != Material.SPAWNER) {
                return;
            }

            CreatureSpawner delayedSpawner = (CreatureSpawner) block.getState();
            EntityType finalEntityType = getEntityType(entityType, delayedSpawner);

            delayedSpawner.setSpawnedType(finalEntityType);
            delayedSpawner.update(true, false);
            createSmartSpawner(block, player, finalEntityType, stackSize);

            setupHopperIntegration(block);
        }, 2L);
    }

    private EntityType getEntityType(EntityType storedEntityType, CreatureSpawner placedSpawner) {
        EntityType entityType = storedEntityType;

        if (entityType == null || entityType == EntityType.UNKNOWN) {
            entityType = placedSpawner.getSpawnedType();

            placedSpawner.setSpawnedType(entityType);
            placedSpawner.update(true, false);
        }

        return entityType;
    }

    private void createSmartSpawner(Block block, Player player, EntityType entityType, int stackSize) {
        String spawnerId = UUID.randomUUID().toString().substring(0, 8);

        BlockState state = block.getState();
        if (state instanceof CreatureSpawner spawner) {
            spawner.setSpawnedType(entityType);
            spawner.update(true, false);
        }

        SpawnerData spawner = new SpawnerData(spawnerId, block.getLocation(), entityType, plugin);
        spawner.setSpawnerActive(true);
        spawner.setStackSize(stackSize);
        
        // Sync data from the physical block spawner if available
        if (state instanceof CreatureSpawner creatureSpawner) {
            spawner.syncFromBlock(creatureSpawner);
        }
        
        // Track player interaction for last interaction field
        spawner.updateLastInteractedPlayer(player.getName());

        spawnerManager.addSpawner(spawnerId, spawner);
        chunkSpawnerLimiter.registerSpawnerPlacement(block.getLocation(), spawner.getStackSize());

        spawnerManager.queueSpawnerForSaving(spawnerId);

        if (plugin.getConfig().getBoolean("particle.spawner_generate_loot", true)) {
            showCreationParticles(block);
        }

        messageService.sendMessage(player, "spawner_activated");
    }

    private void showCreationParticles(Block block) {
        Scheduler.runLocationTask(block.getLocation(), () -> {
            Location particleLocation = block.getLocation().clone().add(
                    PARTICLE_OFFSET, PARTICLE_OFFSET, PARTICLE_OFFSET);
            block.getWorld().spawnParticle(
                    ParticleWrapper.SPELL_WITCH,
                    particleLocation,
                    50, PARTICLE_OFFSET, PARTICLE_OFFSET, PARTICLE_OFFSET, 0
            );
        });
    }

    private void setupHopperIntegration(Block block) {
        Scheduler.runLocationTask(block.getLocation(), () -> {
            Block blockBelow = block.getRelative(BlockFace.DOWN);
            if (blockBelow.getType() == Material.HOPPER && hopperHandler != null) {
                hopperHandler.startHopperTask(blockBelow.getLocation(), block.getLocation());
            }
        });
    }

    public void cleanupPlayer(UUID playerId) {
        lastPlacementTime.remove(playerId);
        playerItemCounts.remove(playerId);
    }
}