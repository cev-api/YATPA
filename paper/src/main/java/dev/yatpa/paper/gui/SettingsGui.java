package dev.yatpa.paper.gui;

import dev.yatpa.paper.YatpaPaperPlugin;
import dev.yatpa.paper.config.XmlMessages;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

public class SettingsGui implements Listener {
    private static final String TITLE_SELECTOR = ChatColor.DARK_AQUA + "YATPA Categories";
    private static final String TITLE_CATEGORY_PREFIX = ChatColor.DARK_AQUA + "YATPA ";
    private static final int INVENTORY_SIZE = 54;
    private static final int SETTINGS_PER_PAGE = 45;
    private static final int SLOT_PREV = 45;
    private static final int SLOT_BACK = 48;
    private static final int SLOT_RELOAD = 49;
    private static final int SLOT_CLOSE = 50;
    private static final int SLOT_NEXT = 53;

    private enum ValueKind {
        BOOLEAN, INTEGER, LONG, DOUBLE, FLOAT, LIST, STRING
    }

    private enum ViewMode {
        SELECTOR, CATEGORY
    }

    private record Category(String name, Material icon, List<String> paths) {
    }

    private final YatpaPaperPlugin plugin;
    private final XmlMessages messages;
    private final Map<UUID, String> pendingPathByPlayer = new java.util.HashMap<>();
    private final Map<UUID, String> lastCategoryByPlayer = new java.util.HashMap<>();
    private final Map<UUID, Integer> lastCategoryPageByPlayer = new java.util.HashMap<>();

    public SettingsGui(YatpaPaperPlugin plugin, XmlMessages messages) {
        this.plugin = plugin;
        this.messages = messages;
    }

    public boolean openFor(Player player) {
        if (!player.hasPermission("yatpa.op.reload")) {
            player.sendMessage(messages.get("prefix") + messages.get("no_permission"));
            return true;
        }
        openSelector(player);
        return true;
    }

    private void openSelector(Player player) {
        List<Category> categories = buildCategories();
        Inventory inv = Bukkit.createInventory(new SettingsInventoryHolder(ViewMode.SELECTOR, "", 0), INVENTORY_SIZE,
                TITLE_SELECTOR);
        int slot = 0;
        for (Category category : categories) {
            if (slot >= SETTINGS_PER_PAGE) {
                break;
            }
            inv.setItem(slot++, categoryItem(category));
        }
        inv.setItem(SLOT_RELOAD, navItem(Material.CLOCK, ChatColor.AQUA + "Reload Plugin"));
        inv.setItem(SLOT_CLOSE, navItem(Material.BARRIER, ChatColor.RED + "Close"));
        player.openInventory(inv);
    }

    private void openCategory(Player player, String categoryName, int requestedPage) {
        List<Category> categories = buildCategories();
        Category category = categories.stream().filter(c -> c.name().equals(categoryName)).findFirst().orElse(null);
        if (category == null) {
            openSelector(player);
            return;
        }

        int totalPages = Math.max(1, (int) Math.ceil(category.paths().size() / (double) SETTINGS_PER_PAGE));
        int page = Math.max(0, Math.min(requestedPage, totalPages - 1));
        lastCategoryByPlayer.put(player.getUniqueId(), categoryName);
        lastCategoryPageByPlayer.put(player.getUniqueId(), page);

        String title = TITLE_CATEGORY_PREFIX + ChatColor.GRAY + categoryName + " " + (page + 1) + "/" + totalPages;
        Inventory inv = Bukkit.createInventory(new SettingsInventoryHolder(ViewMode.CATEGORY, categoryName, page),
                INVENTORY_SIZE, title);

        int start = page * SETTINGS_PER_PAGE;
        int end = Math.min(category.paths().size(), start + SETTINGS_PER_PAGE);
        for (int i = start; i < end; i++) {
            int slot = i - start;
            inv.setItem(slot, toSettingItem(category.paths().get(i)));
        }

        inv.setItem(SLOT_PREV, navItem(Material.ARROW, ChatColor.YELLOW + "Previous Page"));
        inv.setItem(SLOT_BACK, navItem(Material.COMPASS, ChatColor.GREEN + "Back To Categories"));
        inv.setItem(SLOT_RELOAD, navItem(Material.CLOCK, ChatColor.AQUA + "Reload Plugin"));
        inv.setItem(SLOT_CLOSE, navItem(Material.BARRIER, ChatColor.RED + "Close"));
        inv.setItem(SLOT_NEXT, navItem(Material.ARROW, ChatColor.YELLOW + "Next Page"));
        player.openInventory(inv);
    }

    private ItemStack categoryItem(Category category) {
        ItemStack item = new ItemStack(category.icon());
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(ChatColor.GOLD + category.name());
            meta.setLore(List.of(
                    ChatColor.GRAY + "Settings: " + ChatColor.WHITE + category.paths().size(),
                    ChatColor.YELLOW + "Click to open"));
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
            item.setItemMeta(meta);
        }
        return item;
    }

    private ItemStack navItem(Material material, String name) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(name);
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
            item.setItemMeta(meta);
        }
        return item;
    }

    private List<Category> buildCategories() {
        List<String> all = editableConfigPaths();
        Map<String, List<String>> buckets = new LinkedHashMap<>();
        buckets.put("Features", new ArrayList<>());
        buckets.put("Core", new ArrayList<>());
        buckets.put("Spawn", new ArrayList<>());
        buckets.put("RTP", new ArrayList<>());
        buckets.put("Restrictions", new ArrayList<>());
        buckets.put("Landing", new ArrayList<>());
        buckets.put("Costs", new ArrayList<>());
        buckets.put("Sounds", new ArrayList<>());
        buckets.put("Effects", new ArrayList<>());
        buckets.put("Other", new ArrayList<>());

        for (String path : all) {
            if (path.startsWith("settings.features.")) {
                buckets.get("Features").add(path);
            } else if (path.startsWith("settings.rtp.")) {
                buckets.get("RTP").add(path);
            } else if (path.startsWith("settings.spawn.")) {
                buckets.get("Spawn").add(path);
            } else if (path.startsWith("settings.dimension_restrictions.")) {
                buckets.get("Restrictions").add(path);
            } else if (path.startsWith("settings.landing.")) {
                buckets.get("Landing").add(path);
            } else if (path.startsWith("settings.costs.")) {
                buckets.get("Costs").add(path);
            } else if (path.startsWith("sounds.")) {
                buckets.get("Sounds").add(path);
            } else if (path.startsWith("effects.")) {
                buckets.get("Effects").add(path);
            } else if (path.startsWith("settings.")) {
                buckets.get("Core").add(path);
            } else {
                buckets.get("Other").add(path);
            }
        }

        List<Category> categories = new ArrayList<>();
        categories.add(new Category("Features", Material.LEVER, sorted(buckets.get("Features"))));
        categories.add(new Category("Core", Material.COMPARATOR, sorted(buckets.get("Core"))));
        categories.add(new Category("Spawn", Material.GRASS_BLOCK, sorted(buckets.get("Spawn"))));
        categories.add(new Category("RTP", Material.ENDER_PEARL, sorted(buckets.get("RTP"))));
        categories.add(new Category("Restrictions", Material.IRON_BARS, sorted(buckets.get("Restrictions"))));
        categories.add(new Category("Landing", Material.FEATHER, sorted(buckets.get("Landing"))));
        categories.add(new Category("Costs", Material.GOLD_INGOT, sorted(buckets.get("Costs"))));
        categories.add(new Category("Sounds", Material.NOTE_BLOCK, sorted(buckets.get("Sounds"))));
        categories.add(new Category("Effects", Material.BLAZE_POWDER, sorted(buckets.get("Effects"))));
        categories.add(new Category("Other", Material.PAPER, sorted(buckets.get("Other"))));
        categories.removeIf(c -> c.paths().isEmpty());
        return categories;
    }

    private List<String> sorted(List<String> values) {
        values.sort(Comparator.naturalOrder());
        return values;
    }

    private List<String> editableConfigPaths() {
        LinkedHashSet<String> keys = new LinkedHashSet<>(plugin.getConfig().getKeys(true));
        // Always expose dynamic/optional keys so admins can manage them even before
        // they exist in config.
        keys.add("settings.rtp.realm_min_distance.overworld");
        keys.add("settings.rtp.realm_min_distance.nether");
        keys.add("settings.rtp.realm_min_distance.end");
        keys.add("settings.rtp.realm_max_distance.overworld");
        keys.add("settings.rtp.realm_max_distance.nether");
        keys.add("settings.rtp.realm_max_distance.end");
        keys.add("settings.rtp.blacklisted_worlds");
        keys.add("settings.rtp.overworld_name");
        keys.add("settings.costs.xp_levels.rtp.overworld");
        keys.add("settings.costs.xp_levels.rtp.nether");
        keys.add("settings.costs.xp_levels.rtp.end");
        keys.add("settings.costs.xp_levels.back");
        keys.add("settings.costs.item.rtp.overworld");
        keys.add("settings.costs.item.rtp.nether");
        keys.add("settings.costs.item.rtp.end");
        keys.add("settings.costs.item.back");
        keys.add("settings.costs.currency.rtp.overworld");
        keys.add("settings.costs.currency.rtp.nether");
        keys.add("settings.costs.currency.rtp.end");
        keys.add("settings.costs.currency.tpa");
        keys.add("settings.costs.currency.tpahere");
        keys.add("settings.costs.currency.home");
        keys.add("settings.costs.currency.back");
        keys.add("settings.costs.currency.rtp");
        keys.add("settings.costs.currency.spawn");
        keys.add("settings.dimension_restrictions.disable_rtp.overworld");
        keys.add("settings.dimension_restrictions.disable_rtp.nether");
        keys.add("settings.dimension_restrictions.disable_rtp.end");
        keys.add("settings.dimension_restrictions.disable_teleport.overworld");
        keys.add("settings.dimension_restrictions.disable_teleport.nether");
        keys.add("settings.dimension_restrictions.disable_teleport.end");
        keys.add("settings.spawn.world");
        keys.add("settings.spawn.x");
        keys.add("settings.spawn.y");
        keys.add("settings.spawn.z");
        keys.add("settings.spawn.yaw");
        keys.add("settings.spawn.pitch");

        List<String> result = new ArrayList<>();
        for (String key : keys) {
            if (!plugin.getConfig().isConfigurationSection(key)) {
                result.add(key);
            }
        }
        result.sort(Comparator.naturalOrder());
        return result;
    }

    private boolean isBooleanPath(String path) {
        return path.startsWith("settings.features.")
                || path.startsWith("settings.dimension_restrictions.disable_rtp.")
                || path.startsWith("settings.dimension_restrictions.disable_teleport.")
                || path.equals("settings.rtp.rtp_to_overworld")
                || path.equals("settings.cancel_on_move")
                || path.equals("settings.cancel_on_damage")
                || path.equals("settings.costs.enabled")
                || path.equals("settings.spawn.enabled");
    }

    private boolean isIntegerPath(String path) {
        return path.endsWith("_seconds")
                || path.endsWith("_radius")
                || path.endsWith("_distance")
                || path.endsWith("_offset")
                || path.contains(".costs.xp_levels.")
                || (path.contains(".costs.item.") && !path.equals("settings.costs.item.material"))
                || path.contains(".realm_min_distance.")
                || path.contains(".realm_max_distance.");
    }

    private boolean isDoublePath(String path) {
        return path.contains(".costs.currency.");
    }

    private boolean isCostModePath(String path) {
        return path.equals("settings.costs.mode");
    }

    private boolean isLandingModePath(String path) {
        return path.equals("settings.landing.mode");
    }

    private boolean isMaterialPath(String path) {
        return path.equals("settings.costs.item.material");
    }

    private boolean isSoundPath(String path) {
        return path.startsWith("sounds.");
    }

    private boolean isEffectPath(String path) {
        return path.startsWith("effects.");
    }

    private ValueKind valueKind(String path, Object current) {
        if (isBooleanPath(path))
            return ValueKind.BOOLEAN;
        if (isCostModePath(path) || isLandingModePath(path) || isMaterialPath(path) || isSoundPath(path)
                || isEffectPath(path))
            return ValueKind.STRING;
        if (isIntegerPath(path))
            return ValueKind.INTEGER;
        if (isDoublePath(path))
            return ValueKind.DOUBLE;
        if (current instanceof Long)
            return ValueKind.LONG;
        if (current instanceof Double)
            return ValueKind.DOUBLE;
        if (current instanceof Float)
            return ValueKind.FLOAT;
        if (current instanceof List)
            return ValueKind.LIST;
        return ValueKind.STRING;
    }

    private Object normalizedValue(String path, Object current) {
        ValueKind kind = valueKind(path, current);
        if (kind == ValueKind.BOOLEAN) {
            if (current instanceof Boolean b)
                return b;
            if (current instanceof String s)
                return Boolean.parseBoolean(s.trim());
            if (current instanceof Number n)
                return n.intValue() != 0;
            return Boolean.FALSE;
        }
        if (kind == ValueKind.INTEGER) {
            if (current instanceof Integer i)
                return i;
            if (current instanceof String s) {
                try {
                    return Integer.parseInt(s.trim());
                } catch (NumberFormatException ignored) {
                    return 0;
                }
            }
            if (current instanceof Number n)
                return n.intValue();
            return 0;
        }
        if (current == null) {
            return switch (kind) {
                case LONG -> 0L;
                case DOUBLE -> 0d;
                case FLOAT -> 0f;
                case LIST -> Collections.emptyList();
                case STRING -> defaultStringValue(path);
                default -> "";
            };
        }
        return current;
    }

    private String defaultStringValue(String path) {
        if (isCostModePath(path))
            return "NONE";
        if (isLandingModePath(path))
            return "EXACT";
        if (isMaterialPath(path))
            return "ENDER_PEARL";
        return "";
    }

    private Material materialFor(ValueKind kind, Object normalizedValue) {
        if (kind == ValueKind.BOOLEAN) {
            boolean enabled = normalizedValue instanceof Boolean b && b;
            return enabled ? Material.LIME_DYE : Material.GRAY_DYE;
        }
        if (kind == ValueKind.INTEGER || kind == ValueKind.LONG || kind == ValueKind.DOUBLE
                || kind == ValueKind.FLOAT) {
            return Material.REPEATER;
        }
        if (kind == ValueKind.LIST) {
            return Material.BOOK;
        }
        return Material.PAPER;
    }

    private String stringify(Object value) {
        if (value == null)
            return "null";
        if (value instanceof List<?> list) {
            return list.isEmpty() ? "[]" : list.stream().map(String::valueOf).toList().toString();
        }
        return String.valueOf(value);
    }

    private ItemStack toSettingItem(String path) {
        Object normalized = normalizedValue(path, plugin.getConfig().get(path));
        ValueKind kind = valueKind(path, normalized);
        Material base = materialFor(kind, normalized);
        if (isMaterialPath(path) && normalized instanceof String s) {
            try {
                base = Material.valueOf(s.toUpperCase(java.util.Locale.ROOT));
            } catch (IllegalArgumentException ignored) {
                base = Material.BARRIER;
            }
        }
        ItemStack item = new ItemStack(base);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(ChatColor.GOLD + path);
            List<String> lore = new ArrayList<>();
            lore.add(ChatColor.GRAY + "Value: " + ChatColor.WHITE + stringify(normalized));
            lore.add(ChatColor.DARK_GRAY + "Type: " + kind.name());
            if (kind == ValueKind.BOOLEAN) {
                lore.add(ChatColor.YELLOW + "Left-click: toggle");
                lore.add(ChatColor.YELLOW + "Right-click: edit in chat");
            } else if (isCostModePath(path)) {
                lore.add(ChatColor.YELLOW + "Left-click: next mode");
                lore.add(ChatColor.YELLOW + "Right-click: previous mode");
            } else if (isLandingModePath(path)) {
                lore.add(ChatColor.YELLOW + "Left-click: next mode");
                lore.add(ChatColor.YELLOW + "Right-click: previous mode");
            } else if (isMaterialPath(path)) {
                lore.add(ChatColor.YELLOW + "Put item on cursor + click to set material");
                lore.add(ChatColor.YELLOW + "Or click to edit in chat");
            } else if (isSoundPath(path) || isEffectPath(path)) {
                lore.add(ChatColor.YELLOW + "Left-click: next option");
                lore.add(ChatColor.YELLOW + "Right-click: previous option");
            } else {
                lore.add(ChatColor.YELLOW + "Click: edit in chat");
            }
            lore.add(ChatColor.DARK_AQUA + "Type 'cancel' to abort in chat.");
            meta.setLore(lore);
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
            item.setItemMeta(meta);
        }
        return item;
    }

    private void toggleBoolean(String path) {
        boolean current = plugin.getConfig().getBoolean(path, false);
        plugin.getConfig().set(path, !current);
        plugin.saveConfig();
        plugin.reloadAll();
    }

    private void cycleStringOption(String path, List<String> options, boolean forward) {
        String current = String.valueOf(normalizedValue(path, plugin.getConfig().get(path)))
                .toUpperCase(java.util.Locale.ROOT);
        if (options.isEmpty()) {
            return;
        }
        int idx = options.indexOf(current);
        if (idx < 0)
            idx = 0;
        int next = forward ? (idx + 1) % options.size() : (idx - 1 + options.size()) % options.size();
        plugin.getConfig().set(path, options.get(next));
        plugin.saveConfig();
        plugin.reloadAll();
    }

    private List<String> optionsForPath(String path) {
        if (isCostModePath(path)) {
            return List.of("NONE", "XP_LEVELS", "ITEM", "CURRENCY");
        }
        if (isLandingModePath(path)) {
            return List.of("EXACT", "RANDOM_OFFSET");
        }
        if (isSoundPath(path)) {
            return Arrays.stream(Sound.values()).map(Sound::name).sorted().toList();
        }
        if (isEffectPath(path)) {
            return Arrays.stream(Particle.values()).map(Particle::name).sorted().toList();
        }
        return Collections.emptyList();
    }

    private void promptEdit(Player player, String path) {
        pendingPathByPlayer.put(player.getUniqueId(), path);
        player.closeInventory();
        Object normalized = normalizedValue(path, plugin.getConfig().get(path));
        player.sendMessage(messages.get("prefix") + ChatColor.YELLOW + "Editing " + ChatColor.GOLD + path);
        player.sendMessage(
                messages.get("prefix") + ChatColor.GRAY + "Current value: " + ChatColor.WHITE + stringify(normalized));
        player.sendMessage(messages.get("prefix") + ChatColor.GRAY + "Type a new value in chat, or 'cancel'.");
    }

    private Object parseValue(ValueKind kind, String value) {
        return switch (kind) {
            case BOOLEAN -> {
                if (!value.equalsIgnoreCase("true") && !value.equalsIgnoreCase("false")) {
                    yield null;
                }
                yield Boolean.parseBoolean(value);
            }
            case INTEGER -> {
                try {
                    yield Integer.parseInt(value);
                } catch (NumberFormatException ignored) {
                    yield null;
                }
            }
            case LONG -> {
                try {
                    yield Long.parseLong(value);
                } catch (NumberFormatException ignored) {
                    yield null;
                }
            }
            case DOUBLE -> {
                try {
                    yield Double.parseDouble(value);
                } catch (NumberFormatException ignored) {
                    yield null;
                }
            }
            case FLOAT -> {
                try {
                    yield Float.parseFloat(value);
                } catch (NumberFormatException ignored) {
                    yield null;
                }
            }
            case LIST -> {
                if (value.isBlank()) {
                    yield Collections.emptyList();
                }
                String[] parts = value.split(",");
                List<String> out = new ArrayList<>();
                for (String part : parts) {
                    String trimmed = part.trim();
                    if (!trimmed.isEmpty())
                        out.add(trimmed);
                }
                yield out;
            }
            case STRING -> value;
        };
    }

    private Object parsePathAwareValue(String path, ValueKind kind, String value) {
        String raw = value.trim();
        if (isCostModePath(path)) {
            if (!List.of("NONE", "XP_LEVELS", "ITEM", "CURRENCY").contains(raw.toUpperCase(java.util.Locale.ROOT)))
                return null;
            return raw.toUpperCase(java.util.Locale.ROOT);
        }
        if (isLandingModePath(path)) {
            if (!List.of("EXACT", "RANDOM_OFFSET").contains(raw.toUpperCase(java.util.Locale.ROOT)))
                return null;
            return raw.toUpperCase(java.util.Locale.ROOT);
        }
        if (isMaterialPath(path)) {
            try {
                return Material.valueOf(raw.toUpperCase(java.util.Locale.ROOT)).name();
            } catch (IllegalArgumentException ignored) {
                return null;
            }
        }
        if (isSoundPath(path)) {
            try {
                return Sound.valueOf(raw.toUpperCase(java.util.Locale.ROOT)).name();
            } catch (IllegalArgumentException ignored) {
                return null;
            }
        }
        if (isEffectPath(path)) {
            try {
                return Particle.valueOf(raw.toUpperCase(java.util.Locale.ROOT)).name();
            } catch (IllegalArgumentException ignored) {
                return null;
            }
        }
        return parseValue(kind, raw);
    }

    private void reopenAfterChat(Player player) {
        String category = lastCategoryByPlayer.get(player.getUniqueId());
        Integer page = lastCategoryPageByPlayer.get(player.getUniqueId());
        if (category == null || page == null) {
            Bukkit.getScheduler().runTask(plugin, () -> openSelector(player));
            return;
        }
        Bukkit.getScheduler().runTask(plugin, () -> openCategory(player, category, page));
    }

    private void applyChatValue(Player player, String path, String rawValue) {
        String input = rawValue.trim();
        if (input.equalsIgnoreCase("cancel")) {
            player.sendMessage(messages.get("prefix") + ChatColor.GRAY + "Edit cancelled.");
            reopenAfterChat(player);
            return;
        }
        ValueKind kind = valueKind(path, plugin.getConfig().get(path));
        Object parsed = parsePathAwareValue(path, kind, input);
        if (parsed == null) {
            player.sendMessage(messages.get("prefix") + ChatColor.RED + "Invalid value for type " + ChatColor.YELLOW
                    + kind.name() + ChatColor.RED + ".");
            reopenAfterChat(player);
            return;
        }
        maybeMigrateGlobalRtpCostToRealm(path);
        plugin.getConfig().set(path, parsed);
        plugin.saveConfig();
        plugin.reloadAll();
        player.sendMessage(messages.get("prefix") + ChatColor.GREEN + "Updated " + ChatColor.GOLD + path
                + ChatColor.GREEN + " to " + ChatColor.AQUA + input);
        reopenAfterChat(player);
    }

    private void maybeMigrateGlobalRtpCostToRealm(String path) {
        if (path.startsWith("settings.costs.item.rtp.")) {
            migrateGlobalRtpCostToRealm("settings.costs.item.rtp");
        } else if (path.startsWith("settings.costs.xp_levels.rtp.")) {
            migrateGlobalRtpCostToRealm("settings.costs.xp_levels.rtp");
        } else if (path.startsWith("settings.costs.currency.rtp.")) {
            migrateGlobalRtpCostToRealm("settings.costs.currency.rtp");
        }
    }

    private void migrateGlobalRtpCostToRealm(String basePath) {
        if (!plugin.getConfig().isInt(basePath) && !plugin.getConfig().isDouble(basePath)) {
            return;
        }
        double global = plugin.getConfig().getDouble(basePath);
        plugin.getConfig().set(basePath + ".overworld", global);
        plugin.getConfig().set(basePath + ".nether", global);
        plugin.getConfig().set(basePath + ".end", global);
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player))
            return;
        if (!(event.getView().getTopInventory().getHolder() instanceof SettingsInventoryHolder holder))
            return;
        event.setCancelled(true);
        if (event.getClickedInventory() == null || event.getClickedInventory() != event.getView().getTopInventory())
            return;

        int slot = event.getRawSlot();
        if (holder.mode == ViewMode.SELECTOR) {
            if (slot == SLOT_RELOAD) {
                plugin.reloadAll();
                Bukkit.getScheduler().runTask(plugin, () -> openSelector(player));
                return;
            }
            if (slot == SLOT_CLOSE) {
                player.closeInventory();
                return;
            }
            List<Category> categories = buildCategories();
            if (slot < 0 || slot >= categories.size())
                return;
            openCategory(player, categories.get(slot).name(), 0);
            return;
        }

        if (slot == SLOT_PREV) {
            openCategory(player, holder.categoryName, holder.page - 1);
            return;
        }
        if (slot == SLOT_NEXT) {
            openCategory(player, holder.categoryName, holder.page + 1);
            return;
        }
        if (slot == SLOT_BACK) {
            openSelector(player);
            return;
        }
        if (slot == SLOT_RELOAD) {
            plugin.reloadAll();
            Bukkit.getScheduler().runTask(plugin, () -> openCategory(player, holder.categoryName, holder.page));
            return;
        }
        if (slot == SLOT_CLOSE) {
            player.closeInventory();
            return;
        }

        List<Category> categories = buildCategories();
        Category category = categories.stream().filter(c -> c.name().equals(holder.categoryName)).findFirst()
                .orElse(null);
        if (category == null) {
            openSelector(player);
            return;
        }
        int idx = holder.page * SETTINGS_PER_PAGE + slot;
        if (slot < 0 || slot >= SETTINGS_PER_PAGE || idx >= category.paths().size())
            return;
        String path = category.paths().get(idx);

        ValueKind kind = valueKind(path, plugin.getConfig().get(path));
        if (isMaterialPath(path) && event.getCursor() != null && event.getCursor().getType() != Material.AIR) {
            plugin.getConfig().set(path, event.getCursor().getType().name());
            plugin.saveConfig();
            plugin.reloadAll();
            Bukkit.getScheduler().runTask(plugin, () -> openCategory(player, holder.categoryName, holder.page));
            return;
        }
        if (isCostModePath(path) || isLandingModePath(path) || isSoundPath(path) || isEffectPath(path)) {
            cycleStringOption(path, optionsForPath(path), !event.isRightClick());
            Bukkit.getScheduler().runTask(plugin, () -> openCategory(player, holder.categoryName, holder.page));
            return;
        }
        if (kind == ValueKind.BOOLEAN && event.isLeftClick() && !event.isShiftClick()) {
            toggleBoolean(path);
            Bukkit.getScheduler().runTask(plugin, () -> openCategory(player, holder.categoryName, holder.page));
            return;
        }
        promptEdit(player, path);
    }

    @EventHandler
    public void onChatEdit(AsyncPlayerChatEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        String path = pendingPathByPlayer.remove(id);
        if (path == null)
            return;
        event.setCancelled(true);
        String value = event.getMessage();
        Bukkit.getScheduler().runTask(plugin, () -> {
            Player player = Bukkit.getPlayer(id);
            if (player == null || !player.isOnline())
                return;
            applyChatValue(player, path, value);
        });
    }

    private static final class SettingsInventoryHolder implements InventoryHolder {
        private final ViewMode mode;
        private final String categoryName;
        private final int page;

        private SettingsInventoryHolder(ViewMode mode, String categoryName, int page) {
            this.mode = mode;
            this.categoryName = categoryName;
            this.page = page;
        }

        @Override
        public Inventory getInventory() {
            return null;
        }
    }
}
