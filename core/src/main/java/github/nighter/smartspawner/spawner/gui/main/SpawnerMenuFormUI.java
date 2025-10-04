package github.nighter.smartspawner.spawner.gui.main;

import github.nighter.smartspawner.Scheduler;
import github.nighter.smartspawner.SmartSpawner;
import github.nighter.smartspawner.language.MessageService;
import github.nighter.smartspawner.spawner.properties.SpawnerData;
import github.nighter.smartspawner.spawner.properties.VirtualInventory;
import github.nighter.smartspawner.language.LanguageManager;
import github.nighter.smartspawner.spawner.gui.layout.GuiLayout;
import github.nighter.smartspawner.spawner.gui.layout.GuiButton;
import org.bukkit.entity.Player;
import org.geysermc.cumulus.form.SimpleForm;
import org.geysermc.cumulus.util.FormImage;
import org.geysermc.floodgate.api.FloodgateApi;

import java.util.*;

public class SpawnerMenuFormUI {
    private static final int TICKS_PER_SECOND = 20;

    private final SmartSpawner plugin;
    private final LanguageManager languageManager;
    private final MessageService messageService;

    // Form cache to avoid rebuilding forms every time
    private final Map<String, CachedForm> formCache = new HashMap<>();
    private static final long CACHE_EXPIRY_TIME_MS = 30000; // 30 seconds

    // Action to button info mapping
    private static final Map<String, ActionButtonInfo> ACTION_BUTTON_CONFIG = new HashMap<>();

    static {
        ACTION_BUTTON_CONFIG.put("open_storage", new ActionButtonInfo(
                "bedrock.main_gui.button_names.storage",
                "https://i.pinimg.com/736x/7a/28/50/7a28504d8446ab0ad670757cfa32fe59.jpg"
        ));

        ACTION_BUTTON_CONFIG.put("open_stacker", new ActionButtonInfo(
                "bedrock.main_gui.button_names.stacker",
                "https://cdn.modrinth.com/data/9tQwxSFr/f0f1cc267f587a39acd2c820cfe6b29d0f2ccae3.png"
        ));

        ACTION_BUTTON_CONFIG.put("sell_and_exp", new ActionButtonInfo(
                "bedrock.main_gui.button_names.sell_and_exp",
                "https://static.wikia.nocookie.net/minecraft_gamepedia/images/8/8a/Gold_Ingot_JE4_BE2.png/revision/latest?cb=20200224211607"
        ));

        ACTION_BUTTON_CONFIG.put("sell_all", new ActionButtonInfo(
                "bedrock.main_gui.button_names.sell_all",
                "https://static.wikia.nocookie.net/minecraft_gamepedia/images/8/8a/Gold_Ingot_JE4_BE2.png/revision/latest?cb=20200224211607"
        ));

        ACTION_BUTTON_CONFIG.put("collect_exp", new ActionButtonInfo(
                "bedrock.main_gui.button_names.exp",
                "https://minecraft.wiki/images/Bottle_o%27_Enchanting.gif"
        ));

        ACTION_BUTTON_CONFIG.put("view_info", new ActionButtonInfo(
                "bedrock.main_gui.button_names.view_info",
                "https://static.wikia.nocookie.net/minecraft_gamepedia/images/9/9f/Information_sign.png/revision/latest/scale-to-width-down/268?cb=20200105100749"
        ));
    }

    public SpawnerMenuFormUI(SmartSpawner plugin) {
        this.plugin = plugin;
        this.languageManager = plugin.getLanguageManager();
        this.messageService = plugin.getMessageService();
    }

    public void clearCache() {
        formCache.clear();
    }

    public void invalidateSpawnerCache(String spawnerId) {
        formCache.entrySet().removeIf(entry -> entry.getKey().startsWith(spawnerId + "|"));
    }

    public void openSpawnerForm(Player player, SpawnerData spawner) {
        Map<String, String> placeholders = createPlaceholders(spawner);

        String title;
        if (spawner.getStackSize() > 1) {
            title = languageManager.getGuiTitle("bedrock.main_gui.title_stacked_spawner", placeholders);
        } else {
            title = languageManager.getGuiTitle("bedrock.main_gui.title_single_spawner", placeholders);
        }

        GuiLayout layout = plugin.getGuiLayoutConfig().getCurrentMainLayout();
        List<ButtonInfo> availableButtons = collectAvailableButtons(layout, player, placeholders);

        if (availableButtons.isEmpty()) {
            messageService.sendMessage(player, "no_permission");
            return;
        }

        // Create cache key based on spawner state
        String cacheKey = spawner.getSpawnerId() + "|" + spawner.getStackSize() + "|" +
                spawner.getSpawnerExp() + "|" + spawner.getVirtualInventory().getUsedSlots();

        // Check cache first
        CachedForm cachedForm = formCache.get(cacheKey);
        if (cachedForm != null && !cachedForm.isExpired() && cachedForm.buttons.equals(availableButtons)) {
            FloodgateApi.getInstance().getPlayer(player.getUniqueId()).sendForm(cachedForm.form);
            return;
        }

        SimpleForm.Builder formBuilder = SimpleForm.builder()
                .title(title);

        for (ButtonInfo buttonInfo : availableButtons) {
            formBuilder.button(buttonInfo.text, FormImage.Type.URL, buttonInfo.imageUrl);
        }

        SimpleForm form = formBuilder
                .closedOrInvalidResultHandler(() -> {
                })
                .validResultHandler(response -> {
                    int buttonId = response.clickedButtonId();
                    if (buttonId < availableButtons.size()) {
                        ButtonInfo buttonInfo = availableButtons.get(buttonId);
                        Scheduler.runTask(() -> {
                            switch (buttonInfo.action) {
                                case "open_storage":
                                    plugin.getSpawnerMenuAction().handleStorageClickBedrock(player, spawner);
                                    break;
                                case "open_stacker":
                                    handleSpawnerInfo(player, spawner);
                                    break;
                                case "sell_and_exp":
                                    handleSellInventory(player, spawner);
                                    break;
                                case "sell_all":
                                    handleSellAll(player, spawner);
                                    break;
                                case "collect_exp":
                                    handleExpCollection(player, spawner);
                                    break;
                                case "view_info":
                                    openViewInfoForm(player, spawner);
                                    break;
                                default:
                                    plugin.getLogger().warning("Unknown action in FormUI: " + buttonInfo.action);
                                    break;
                            }
                        });
                    }
                })
                .build();

        // Cache the form
        formCache.put(cacheKey, new CachedForm(form, availableButtons));

        FloodgateApi.getInstance().getPlayer(player.getUniqueId()).sendForm(form);
    }

    private List<ButtonInfo> collectAvailableButtons(GuiLayout layout, Player player, Map<String, String> placeholders) {
        List<ButtonInfo> buttons = new ArrayList<>();
        Set<String> addedActions = new HashSet<>();

        // Get all buttons from layout sorted by slot
        List<GuiButton> sortedButtons = layout.getAllButtons().values().stream()
                .filter(GuiButton::isEnabled)
                .sorted(Comparator.comparing(GuiButton::getSlot))
                .toList();

        for (GuiButton button : sortedButtons) {
            // Check condition if present
            if (button.getCondition() != null && !evaluateCondition(button.getCondition())) {
                continue;
            }

            // Get all unique actions from this button
            Set<String> buttonActions = new HashSet<>(button.getActions().values());

            for (String action : buttonActions) {
                // Skip if action already added or if action is "none"
                if (addedActions.contains(action) || "none".equals(action)) {
                    continue;
                }

                // Check if player has permission for this action
                if (!hasPermissionForAction(player, action)) {
                    continue;
                }

                // Get button configuration for this action
                ActionButtonInfo actionConfig = ACTION_BUTTON_CONFIG.get(action);
                if (actionConfig != null) {
                    String text = languageManager.getGuiItemName(actionConfig.langKey, placeholders);
                    buttons.add(new ButtonInfo(action, text, actionConfig.imageUrl));
                    addedActions.add(action);
                }
            }
        }

        // Always add "View Info" button at the end, regardless of GUI layout
        if (!addedActions.contains("view_info")) {
            ActionButtonInfo viewInfoConfig = ACTION_BUTTON_CONFIG.get("view_info");
            if (viewInfoConfig != null) {
                String text = languageManager.getGuiItemName(viewInfoConfig.langKey, placeholders);
                buttons.add(new ButtonInfo("view_info", text, viewInfoConfig.imageUrl));
            }
        }

        return buttons;
    }

    private boolean evaluateCondition(String condition) {
        switch (condition) {
            case "shop_integration":
                return plugin.hasSellIntegration();
            case "no_shop_integration":
                return !plugin.hasSellIntegration();
            default:
                plugin.getLogger().warning("Unknown condition in FormUI: " + condition);
                return true;
        }
    }

    private boolean hasPermissionForAction(Player player, String action) {
        switch (action) {
            case "open_storage":
                return true; // Storage access doesn't require special permission
            case "open_stacker":
                return player.hasPermission("smartspawner.stack");
            case "sell_and_exp":
            case "sell_all":
                return plugin.hasSellIntegration() && player.hasPermission("smartspawner.sellall");
            case "collect_exp":
                return true; // EXP collection doesn't require special permission
            case "view_info":
                return true; // View Info doesn't require special permission
            default:
                return false;
        }
    }

    private void handleSpawnerInfo(Player player, SpawnerData spawner) {
        if (!player.hasPermission("smartspawner.stack")) {
            messageService.sendMessage(player, "no_permission");
            return;
        }
        plugin.getSpawnerStackerUI().openStackerGui(player, spawner);
    }

    private void handleSellInventory(Player player, SpawnerData spawner) {
        if (!plugin.hasSellIntegration() || !player.hasPermission("smartspawner.sellall")) {
            messageService.sendMessage(player, "no_permission");
            return;
        }
        plugin.getSpawnerMenuAction().handleExpBottleClick(player, spawner, true);
        plugin.getSpawnerSellManager().sellAllItems(player, spawner);
    }

    private void handleSellAll(Player player, SpawnerData spawner) {
        if (!plugin.hasSellIntegration() || !player.hasPermission("smartspawner.sellall")) {
            messageService.sendMessage(player, "no_permission");
            return;
        }
        plugin.getSpawnerSellManager().sellAllItems(player, spawner);
    }

    private void handleExpCollection(Player player, SpawnerData spawner) {
        plugin.getSpawnerMenuAction().handleExpBottleClick(player, spawner, false);
    }

    private void openViewInfoForm(Player player, SpawnerData spawner) {
        Map<String, String> placeholders = createPlaceholders(spawner);
        
        String title = languageManager.getGuiTitle("bedrock.main_gui.view_info_title", placeholders);
        
        // Generate spawner info content
        String spawnerInfo = createSpawnerInfoContent(placeholders);
        
        // Get back button text
        String backButtonText = languageManager.getGuiItemName("bedrock.main_gui.button_names.back", placeholders);
        
        SimpleForm form = SimpleForm.builder()
                .title(title)
                .content(spawnerInfo)
                .button(backButtonText, FormImage.Type.URL, "https://i.pinimg.com/736x/ff/52/52/ff5252ff5252ff5252ff5252ff5252ff.jpg")
                .closedOrInvalidResultHandler(() -> {
                })
                .validResultHandler(response -> {
                    // Back button was clicked, reopen main spawner form
                    Scheduler.runTask(() -> {
                        openSpawnerForm(player, spawner);
                    });
                })
                .build();
        
        FloodgateApi.getInstance().getPlayer(player.getUniqueId()).sendForm(form);
    }

    private Map<String, String> createPlaceholders(SpawnerData spawner) {
        String entityName = languageManager.getFormattedMobName(spawner.getEntityType());
        String entityNameSmallCaps = languageManager.getSmallCaps(entityName);

        Map<String, String> placeholders = new HashMap<>();
        placeholders.put("ᴇɴᴛɪᴛʏ", entityNameSmallCaps);
        placeholders.put("entity", entityName);
        placeholders.put("amount", String.valueOf(spawner.getStackSize()));
        placeholders.put("entity_type", spawner.getEntityType().toString());

        // Stack information
        placeholders.put("stack_size", String.valueOf(spawner.getStackSize()));

        // Spawner settings
        placeholders.put("range", String.valueOf(spawner.getRequiredPlayerRange()));
        long delaySeconds = spawner.getSpawnDelay() / TICKS_PER_SECOND;
        placeholders.put("delay", String.valueOf(delaySeconds));
        placeholders.put("delay_raw", String.valueOf(spawner.getSpawnDelay()));
        placeholders.put("min_mobs", String.valueOf(spawner.getMinMobs()));
        placeholders.put("max_mobs", String.valueOf(spawner.getMaxMobs()));

        // Storage information
        VirtualInventory virtualInventory = spawner.getVirtualInventory();
        int currentItems = virtualInventory.getUsedSlots();
        int maxSlots = spawner.getMaxSpawnerLootSlots();
        double percentStorageDecimal = maxSlots > 0 ? ((double) currentItems / maxSlots) * 100 : 0;
        String formattedPercentStorage = String.format("%.1f", percentStorageDecimal);

        placeholders.put("current_items", String.valueOf(currentItems));
        placeholders.put("max_items", languageManager.formatNumber(maxSlots));
        placeholders.put("formatted_storage", formattedPercentStorage);

        // Experience information
        long currentExp = spawner.getSpawnerExp();
        long maxExp = spawner.getMaxStoredExp();
        double percentExpDecimal = maxExp > 0 ? ((double) currentExp / maxExp) * 100 : 0;
        String formattedPercentExp = String.format("%.1f", percentExpDecimal);

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

        return placeholders;
    }

    private String createSpawnerInfoContent(Map<String, String> placeholders) {
        // Get the info lines from config
        List<String> infoLines = languageManager.getGuiItemLoreAsList("bedrock.main_gui.spawner_info", placeholders);

        if (infoLines == null || infoLines.isEmpty()) {
            return ""; // Return empty string if no config
        }

        // Convert to Bedrock-compatible color codes and join with newlines
        StringBuilder content = new StringBuilder();
        for (String line : infoLines) {
            String bedrockLine = convertToBedrockColors(line);
            content.append(bedrockLine).append("\n");
        }

        // Remove trailing newline
        if (content.length() > 0) {
            content.setLength(content.length() - 1);
        }

        return content.toString();
    }

    /**
     * Converts hex and standard color codes to Bedrock-compatible color codes (§0-§9, §a-§f, §g)
     */
    private String convertToBedrockColors(String text) {
        if (text == null) return "";

        // First apply placeholders and convert hex colors to standard Bukkit colors
        String result = text;

        // Convert hex patterns like &#RRGGBB to approximate Bedrock colors
        result = result.replaceAll("&#([A-Fa-f0-9]{6})", "");  // Remove hex colors for now, will use closest match

        // Map common hex colors to Bedrock equivalents
        result = mapHexToBedrockColors(result, text);

        // Convert & color codes to § for Bedrock
        result = result.replace("&0", "§0");
        result = result.replace("&1", "§1");
        result = result.replace("&2", "§2");
        result = result.replace("&3", "§3");
        result = result.replace("&4", "§4");
        result = result.replace("&5", "§5");
        result = result.replace("&6", "§6");
        result = result.replace("&7", "§7");
        result = result.replace("&8", "§8");
        result = result.replace("&9", "§9");
        result = result.replace("&a", "§a");
        result = result.replace("&b", "§b");
        result = result.replace("&c", "§c");
        result = result.replace("&d", "§d");
        result = result.replace("&e", "§e");
        result = result.replace("&f", "§f");
        result = result.replace("&g", "§g");

        return result;
    }

    /**
     * Maps hex color codes to the closest Bedrock color code
     */
    private String mapHexToBedrockColors(String result, String original) {
        // Common color mappings from hex to Bedrock
        Map<String, String> colorMap = new HashMap<>();

        // Grays and blacks
        colorMap.put("545454", "§8");  // Dark Gray
        colorMap.put("bdc3c7", "§7");  // Gray
        colorMap.put("ecf0f1", "§f");  // White
        colorMap.put("f8f8ff", "§f");  // White

        // Blues
        colorMap.put("3498db", "§9");  // Blue

        // Greens
        colorMap.put("2ecc71", "§a");  // Green
        colorMap.put("37eb9a", "§a");  // Green
        colorMap.put("2cc483", "§a");  // Green
        colorMap.put("48e89b", "§a");  // Green
        colorMap.put("00F986", "§a");  // Green

        // Reds
        colorMap.put("e67e22", "§6");  // Gold
        colorMap.put("ff5252", "§c");  // Red
        colorMap.put("e63939", "§4");  // Dark Red
        colorMap.put("ff7070", "§c");  // Red

        // Purples
        colorMap.put("d8c5ff", "§d");  // Light Purple
        colorMap.put("7b68ee", "§5");  // Dark Purple
        colorMap.put("a885fc", "§d");  // Light Purple
        colorMap.put("c2a8fc", "§d");  // Light Purple
        colorMap.put("ab7afd", "§d");  // Light Purple

        // Oranges and golds
        colorMap.put("EF6C00", "§6");  // Gold
        colorMap.put("607D8B", "§8");  // Dark Gray

        for (Map.Entry<String, String> entry : colorMap.entrySet()) {
            result = result.replace("&#" + entry.getKey(), entry.getValue());
            result = result.replace("&#" + entry.getKey().toLowerCase(), entry.getValue());
        }

        return result;
    }

    private static class ButtonInfo {
        final String action;
        final String text;
        final String imageUrl;

        ButtonInfo(String action, String text, String imageUrl) {
            this.action = action;
            this.text = text;
            this.imageUrl = imageUrl;
        }
    }

    private static class ActionButtonInfo {
        final String langKey;
        final String imageUrl;

        ActionButtonInfo(String langKey, String imageUrl) {
            this.langKey = langKey;
            this.imageUrl = imageUrl;
        }
    }

    private static class CachedForm {
        final SimpleForm form;
        final List<ButtonInfo> buttons;
        final long timestamp;

        CachedForm(SimpleForm form, List<ButtonInfo> buttons) {
            this.form = form;
            this.buttons = buttons;
            this.timestamp = System.currentTimeMillis();
        }

        boolean isExpired() {
            return System.currentTimeMillis() - timestamp > CACHE_EXPIRY_TIME_MS;
        }
    }
}