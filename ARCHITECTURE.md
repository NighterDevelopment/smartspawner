# SmartSpawner Architecture

## Overview

SmartSpawner is a Minecraft plugin that provides enhanced spawner functionality through a virtual spawner system. This document explains the core architecture and design decisions.

## Virtual Spawner System

### What is a Virtual Spawner?

A virtual spawner (`SpawnerData`) is different from Minecraft's standard spawner block:

**Standard Minecraft Spawner:**
- Data stored in the block itself
- Spawns actual entities in the world
- One spawner per block
- Limited to Minecraft's default behavior

**Virtual Spawner (SpawnerData):**
- Data stored independently from the block
- Generates loot items instead of spawning entities
- Supports stacking (multiple spawners in one block)
- Has virtual inventory for loot storage
- Can be enhanced with experience, upgrades, and custom features

### Why Virtual Spawners?

1. **Performance**: Generating items is more efficient than spawning entities
2. **Scalability**: Multiple spawners can share the same block location
3. **Flexibility**: Rich feature set beyond standard Minecraft spawners
4. **Automation**: Easy integration with hoppers and other collection systems

## Bukkit API Integration

### Implementing org.bukkit.spawner.Spawner

`SpawnerData` implements the standard Bukkit `Spawner` interface for API compatibility, but with special considerations:

#### Methods with Virtual Behavior

| Method | Virtual Spawner Behavior |
|--------|-------------------------|
| `getSpawnRange()` | Returns default value (4) - not used for loot generation |
| `setSpawnRange()` | No-op - spawn range doesn't apply to virtual spawners |
| `getRequiredPlayerRange()` | Returns activation distance (replaces `spawnerRange`) |
| `isActivated()` | Returns true when players are in range |
| `getDelay()` | Returns delay in ticks (converted from internal milliseconds) |

#### Migration from Custom Fields

The refactor replaced custom fields with standard Bukkit interface methods:

**Before:**
```java
spawner.getSpawnerRange()     // Custom field
spawner.getSpawnerStop()      // Custom field
```

**After:**
```java
spawner.getRequiredPlayerRange()  // Bukkit standard
spawner.isActivated()             // Bukkit standard
```

### Syncing Between Block and Virtual Spawner

When a spawner is placed, data can be extracted from the CreatureSpawner block:

```java
// Create virtual spawner
SpawnerData spawner = new SpawnerData(id, location, entityType, plugin);

// Sync data from the physical block
if (blockState instanceof CreatureSpawner creatureSpawner) {
    spawner.syncFromBlock(creatureSpawner);
}
```

This extracts information like:
- Entity type
- Spawn delay
- Required player range

The reverse is also possible with `applyToBlock()`:

```java
// Apply virtual spawner data to the physical block
spawner.applyToBlock(creatureSpawner);
```

### Backward Compatibility

Deprecated wrapper methods maintain compatibility with existing code:
- `getSpawnerRange()` → delegates to `getRequiredPlayerRange()`
- `getSpawnerStop()` → returns inverse of `isActivated()`

## Activation System

### How Spawner Activation Works

The `SpawnerRangeChecker` manages spawner activation:

1. **Periodic Checks** (every 1 second):
   - Checks all spawners for nearby players
   - Uses region-specific scheduling for thread safety (Folia compatible)

2. **Range Detection**:
   - Checks if players are within `getRequiredPlayerRange()`
   - Distance is measured in blocks (squared for efficiency)

3. **State Management**:
   - Updates `isActivated()` based on player presence
   - Triggers activation/deactivation handlers

4. **Task Management**:
   - **Activated**: Starts loot generation tasks
   - **Deactivated**: Stops tasks to save resources

### Why SpawnerRangeChecker Still Exists

Even though `isActivated()` is now part of the Spawner interface, `SpawnerRangeChecker` is still needed because:

1. **Active Monitoring**: Continuously checks for players and updates activation state
2. **Task Management**: Starts/stops loot generation tasks based on state
3. **Performance**: Centralizes range checking logic
4. **Region Safety**: Ensures thread-safe operations in Folia environments

The `isActivated()` method **reports** the state, while `SpawnerRangeChecker` **maintains** it.

## Loot Generation System

### SpawnerLootGenerator

Generates loot items based on:
- Entity type and loot tables
- Stack size multiplier
- Configured loot pools and drop rates

### Virtual Inventory

Each spawner has a `VirtualInventory`:
- Stores loot items
- Configurable slot capacity (scales with stack size)
- Integration with hoppers for automatic collection
- Filtering system for selective item storage

## Key Components

### Core Classes

1. **SpawnerData**: Virtual spawner implementation with Bukkit Spawner interface
2. **SpawnerManager**: Manages all spawner instances
3. **SpawnerRangeChecker**: Handles activation based on player proximity
4. **SpawnerLootGenerator**: Generates loot items for spawners
5. **VirtualInventory**: Stores generated loot

### File Handlers

- **SpawnerFileHandler**: Persists spawner data to YAML
- **SpawnerDataConverter**: Migrates data between versions

### GUI System

- **SpawnerMenuUI**: Java-based inventory GUI
- **SpawnerMenuFormUI**: Bedrock-compatible form UI
- **SpawnerGuiViewManager**: Manages real-time GUI updates

## Design Patterns

### Separation of Concerns

- **Data**: `SpawnerData` - state and properties
- **Logic**: `SpawnerRangeChecker`, `SpawnerLootGenerator` - behavior
- **Persistence**: `SpawnerFileHandler` - data storage
- **Presentation**: GUI classes - user interface

### Thread Safety

- Uses `ReentrantLock` for concurrent access to spawner data
- Region-specific scheduling for Folia compatibility
- Concurrent collections for shared state

### Extensibility

- Implements standard Bukkit interfaces
- Event system for custom integrations
- Configurable loot tables and behaviors

## Migration Guide

### For Plugin Developers

If you're updating code to use the refactored API:

1. **Replace spawner range checks:**
   ```java
   // Old
   int range = spawner.getSpawnerRange();
   
   // New
   int range = spawner.getRequiredPlayerRange();
   ```

2. **Replace activation checks:**
   ```java
   // Old
   if (!spawner.getSpawnerStop()) {
       // spawner is active
   }
   
   // New
   if (spawner.isActivated()) {
       // spawner is active
   }
   ```

3. **Use standard delay methods:**
   ```java
   // Old (internal)
   long delayMs = spawner.getSpawnDelay();
   
   // New (Bukkit standard)
   int delayTicks = spawner.getDelay();
   
   // Or keep using getSpawnDelay() for milliseconds if needed
   long delayMs = spawner.getSpawnDelay();
   ```

4. **Sync data from spawner blocks:**
   ```java
   // Extract data from CreatureSpawner block
   spawner.syncFromBlock(creatureSpawner);
   
   // Apply data to CreatureSpawner block
   spawner.applyToBlock(creatureSpawner);
   ```

### For Server Administrators

No changes needed! The refactor maintains full backward compatibility with existing configurations and saved data.

## Benefits of This Architecture

1. **API Compatibility**: Implements standard Bukkit interfaces
2. **Performance**: Efficient loot generation without entity spawning
3. **Scalability**: Supports spawner stacking and large-scale farms
4. **Maintainability**: Clean separation of concerns
5. **Thread Safety**: Folia-compatible with proper scheduling
6. **Extensibility**: Easy to add new features and integrations
7. **Block Integration**: Can sync data between virtual and physical spawners

## Future Considerations

### Potential Enhancements

1. **Further Interface Integration**: Consider implementing more Bukkit interfaces
2. **Event System**: Expand custom events for better third-party integration
3. **Performance Monitoring**: Add metrics for spawner performance tracking
4. **API Documentation**: Generate comprehensive Javadocs

### Breaking Changes to Avoid

- Keep deprecated methods until major version bump
- Maintain file format compatibility
- Preserve public API contracts
