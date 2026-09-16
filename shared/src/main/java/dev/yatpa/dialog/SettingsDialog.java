package dev.yatpa.dialog;

import java.util.ArrayList;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Vanilla dialog data shared by Paper and Fabric; no platform UI dependencies. */
public final class SettingsDialog {
    private static final List<String> CATEGORY_ORDER = List.of(
        "Features", "Core", "Spawn", "RTP", "Restrictions", "Landing", "Costs", "Sounds", "Effects", "Other");
    private static final int SETTINGS_PER_PAGE = 8;

    private SettingsDialog() {}

    public static String category(String path) {
        if (path.startsWith("sounds.")) return "Sounds";
        if (path.startsWith("effects.")) return "Effects";
        if (!path.startsWith("settings.")) return "Other";
        String section = path.substring(9).split("\\.")[0];
        return switch (section) {
            case "features" -> "Features";
            case "spawn" -> "Spawn";
            case "rtp" -> "RTP";
            case "dimension_restrictions" -> "Restrictions";
            case "landing" -> "Landing";
            case "costs" -> "Costs";
            default -> "Core";
        };
    }

    public static String label(String path) {
        String[] parts = path.replaceFirst("^settings\\.", "").split("\\.");
        int first = parts.length > 1 && parts[0].equalsIgnoreCase(category(path)) ? 1 : 0;
        List<String> names = new ArrayList<>();
        for (int i = first; i < parts.length; i++) names.add(human(parts[i]));
        if (names.isEmpty()) names.add(human(parts[parts.length - 1]));
        return String.join(" · ", names);
    }

    public static Map<String, Object> screen(Map<String, Object> values, String route, String status) {
        boolean exact = route.startsWith("exact:");
        String path = exact ? route.substring(6) : route;
        if (values.containsKey(path)) return editor(path, values.get(path), exact, status);
        CategoryPage page = categoryPage(route);
        if (page == null) return dashboard(values, status);
        return categoryScreen(values, page.category(), page.page(), status);
    }

    private static Map<String, Object> dashboard(Map<String, Object> values, String status) {
        List<Object> actions = new ArrayList<>();
        for (String category : CATEGORY_ORDER) {
            if (values.keySet().stream().noneMatch(path -> category(path).equals(category))) continue;
            actions.add(button(category, "/yatpa gui " + category,
                "Open " + category.toLowerCase(Locale.ROOT) + " settings", 210));
        }
        actions.add(button("Reload configuration", "/yatpa gui reload", "Read the saved configuration from disk", 210));
        Map<String, Object> dialog = base("YATPA  /  Admin settings",
            "Select a section to configure.\n\nChanges are saved individually and take effect immediately.", status, 2);
        dialog.put("actions", actions);
        dialog.put("exit_action", Map.of("label", "Close", "width", 210));
        return dialog;
    }

    private static Map<String, Object> categoryScreen(Map<String, Object> values, String category, int requestedPage, String status) {
        List<Map.Entry<String, Object>> entries = orderedEntries(values, category);
        int perPage = pageSize(category);
        int pages = Math.max(1, (entries.size() + perPage - 1) / perPage);
        int page = Math.max(1, Math.min(requestedPage, pages));
        int start = (page - 1) * perPage;
        List<Object> actions = new ArrayList<>();
        List<Object> inputs = new ArrayList<>();
        StringBuilder details = new StringBuilder(categoryDescription(category));
        if (category.equals("Costs")) details.append("\n\n").append(costPageDescription(page));
        StringBuilder bulkTemplate = new StringBuilder("/yatpa guisavebulk ").append(category).append('#').append(page);
        for (int i = start; i < Math.min(entries.size(), start + perPage); i++) {
            Map.Entry<String, Object> entry = entries.get(i);
            String key = "v" + (i - start);
            inputs.add(inputControl(entry.getKey(), entry.getValue(), key));
            bulkTemplate.append(" \"$(").append(key).append(")\"");
        }
        actions.add(Map.of("label", "Save changes", "tooltip", "Save every control shown on this page", "width", 220,
            "action", Map.of("type", "minecraft:dynamic/run_command", "template", bulkTemplate.toString())));
        if (page > 1) actions.add(button("← Previous", "/yatpa gui " + category + "#" + (page - 1), "Previous settings page", 150));
        if (page < pages) actions.add(button("Next →", "/yatpa gui " + category + "#" + (page + 1), "Next settings page", 150));
        Map<String, Object> dialog = base("YATPA  /  " + category,
            details + "\n\nPage " + page + " of " + pages + "\nEdit the values below, then save this page.", status, 2);
        dialog.put("inputs", inputs);
        dialog.put("actions", actions);
        dialog.put("exit_action", button("← All sections", "/yatpa gui", "Return to the dashboard", 210));
        return dialog;
    }

    private static List<Map.Entry<String, Object>> orderedEntries(Map<String, Object> values, String category) {
        List<Map.Entry<String, Object>> entries = values.entrySet().stream()
            .filter(e -> category(e.getKey()).equals(category)).toList();
        return entries.stream().sorted((a, b) -> {
            int group = Integer.compare(costGroup(a.getKey()), costGroup(b.getKey()));
            return group != 0 ? group : a.getKey().compareTo(b.getKey());
        }).toList();
    }

    /** Returns the setting order used by the inline controls on a category page. */
    public static List<String> visiblePaths(Map<String, Object> values, String category, int requestedPage) {
        List<Map.Entry<String, Object>> entries = orderedEntries(values, category);
        int perPage = pageSize(category);
        int pages = Math.max(1, (entries.size() + perPage - 1) / perPage);
        int page = Math.max(1, Math.min(requestedPage, pages));
        int start = (page - 1) * perPage;
        return entries.subList(start, Math.min(entries.size(), start + perPage)).stream().map(Map.Entry::getKey).toList();
    }

    private static int costGroup(String path) {
        if (!path.startsWith("settings.costs.")) return 0;
        if (path.equals("settings.costs.enabled")) return 0;
        if (path.equals("settings.costs.mode")) return 1;
        if (path.contains(".xp_levels.")) return 2;
        if (path.contains(".item.")) return 3;
        return 4;
    }

    private static int pageSize(String category) {
        // Cost settings naturally form complete pages: general + XP, items, currency.
        return category.equals("Costs") ? 11 : SETTINGS_PER_PAGE;
    }

    private static String costPageDescription(int page) {
        return switch (page) {
            case 1 -> "This page contains the cost switch, cost mode, and XP charges.";
            case 2 -> "This page contains item charges and the item material.";
            case 3 -> "This page contains currency charges.";
            default -> "Use the controls below to edit these charges.";
        };
    }

    private record CategoryPage(String category, int page) {}

    private static CategoryPage categoryPage(String route) {
        if (route == null || route.isBlank()) return null;
        String[] parts = route.split("#", 2);
        String category = CATEGORY_ORDER.stream().filter(value -> value.equalsIgnoreCase(parts[0])).findFirst().orElse(null);
        if (category == null) return null;
        int page = 1;
        if (parts.length == 2) {
            try { page = Integer.parseInt(parts[1]); }
            catch (NumberFormatException ignored) { page = 1; }
        }
        return new CategoryPage(category, page);
    }

    private static String categoryDescription(String category) {
        return switch (category) {
            case "Features" -> "Enable or disable YATPA commands and request behavior.";
            case "Core" -> "Timing, cancellation, and general plugin defaults.";
            case "Spawn" -> "Configure the server spawn destination and availability.";
            case "RTP" -> "Random teleport distances, worlds, and realm overrides.";
            case "Restrictions" -> "Block RTP or teleporting in selected dimensions.";
            case "Landing" -> "Choose how safe landing positions are selected.";
            case "Costs" -> "Configure cost mode and per-action charges.";
            case "Sounds" -> "Choose sounds played during teleport requests.";
            case "Effects" -> "Choose particles played during teleport requests.";
            default -> "Additional YATPA settings.";
        };
    }

    private static String human(String value) {
        return switch (value.toLowerCase(Locale.ROOT)) {
            case "rtp" -> "RTP";
            case "tpa" -> "TPA requests";
            case "tpahere" -> "TPA here";
            case "tpaback" -> "TPA back";
            case "xp_levels" -> "XP levels";
            case "disable_rtp" -> "Disable RTP";
            case "disable_teleport" -> "Disable teleport";
            default -> humanWords(value);
        };
    }

    private static String humanWords(String value) {
        String text = value.replace('_', ' ');
        StringBuilder out = new StringBuilder();
        for (String word : text.split(" ")) {
            if (word.isEmpty()) continue;
            if (out.length() > 0) out.append(' ');
            out.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        }
        return out.toString();
    }

    private static Map<String, Object> inputControl(String path, Object value, String key) {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("key", key);
        String title = settingDescription(path);
        // Dialog rows are centered by the client. A consistent label width keeps
        // checkbox, selector, and text rows on the same visual axis.
        input.put("label", padRight(title, 28));
        input.put("width", 300);
        String current = display(value);
        List<String> options = path.equals("settings.costs.mode") ? List.of("NONE", "XP_LEVELS", "ITEM", "CURRENCY")
            : path.equals("settings.landing.mode") ? List.of("EXACT", "RANDOM_OFFSET") : List.of();
        if (value instanceof Boolean || booleanPath(path)) {
            input.put("type", "minecraft:boolean");
            input.put("initial", Boolean.parseBoolean(current));
        } else if (!options.isEmpty()) {
            input.put("type", "minecraft:single_option");
            String selected = current.toUpperCase(Locale.ROOT);
            input.put("options", options.stream().map(o -> Map.of("id", o, "display", human(o), "initial", o.equals(selected))).toList());
        } else {
            input.put("type", "minecraft:text");
            input.put("initial", current);
            input.put("max_length", Math.max(2048, current.length()));
        }
        return input;
    }

    private static String settingDescription(String path) {
        return switch (path) {
            case "settings.features.enabled" -> "Enable YATPA";
            case "settings.features.tpa" -> "Allow TPA requests";
            case "settings.features.tpahere" -> "Allow TPA here";
            case "settings.features.homes" -> "Enable homes";
            case "settings.features.rtp" -> "Enable random teleport";
            case "settings.features.tpaback" -> "Enable TPA back";
            case "settings.cancel_on_move" -> "Cancel on movement";
            case "settings.cancel_on_damage" -> "Cancel on damage";
            case "settings.request_timeout_seconds" -> "Request timeout (seconds)";
            case "settings.request_cooldown_seconds" -> "Request cooldown (seconds)";
            case "settings.teleport_delay_seconds" -> "Teleport delay (seconds)";
            case "settings.rtp_cooldown_seconds" -> "RTP cooldown (seconds)";
            case "settings.max_homes_default" -> "Default home limit";
            case "settings.spawn_radius" -> "Spawn search radius";
            case "settings.rtp.default_min_distance" -> "Minimum RTP distance";
            case "settings.rtp.default_max_distance" -> "Maximum RTP distance";
            case "settings.rtp.rtp_to_overworld" -> "RTP to overworld";
            case "settings.rtp.overworld_name" -> "Overworld world name";
            case "settings.spawn.enabled" -> "Enable /spawn";
            case "settings.spawn.world" -> "Spawn world name";
            case "settings.spawn.x" -> "Spawn X coordinate";
            case "settings.spawn.y" -> "Spawn Y coordinate";
            case "settings.spawn.z" -> "Spawn Z coordinate";
            case "settings.spawn.yaw" -> "Spawn yaw";
            case "settings.spawn.pitch" -> "Spawn pitch";
            case "settings.landing.mode" -> "Landing mode";
            case "settings.landing.random_offset_max" -> "Maximum landing offset";
            case "settings.costs.enabled" -> "Enable teleport costs";
            case "settings.costs.mode" -> "Cost mode";
            case "settings.costs.item.material" -> "Cost item material";
            case "settings.dimension_restrictions.disable_rtp" -> "Blocked RTP dimensions";
            case "settings.dimension_restrictions.disable_teleport" -> "Blocked teleport dimensions";
            case "settings.rtp.blacklisted_worlds" -> "RTP blacklist";
            default -> {
                if (path.startsWith("settings.costs.xp_levels.")) yield costTitle("XP", path.substring("settings.costs.xp_levels.".length()));
                if (path.startsWith("settings.costs.item.")) yield costTitle("Item", path.substring("settings.costs.item.".length()));
                if (path.startsWith("settings.costs.currency.")) yield costTitle("Currency", path.substring("settings.costs.currency.".length()));
                if (path.startsWith("settings.rtp.default_min_distance.")) yield "Minimum RTP · " + dimension(path.substring("settings.rtp.default_min_distance.".length()));
                if (path.startsWith("settings.rtp.default_max_distance.")) yield "Maximum RTP · " + dimension(path.substring("settings.rtp.default_max_distance.".length()));
                if (path.startsWith("settings.rtp.realm_min_distance.")) yield "Minimum RTP · " + dimension(path.substring("settings.rtp.realm_min_distance.".length()));
                if (path.startsWith("settings.rtp.realm_max_distance.")) yield "Maximum RTP · " + dimension(path.substring("settings.rtp.realm_max_distance.".length()));
                if (path.startsWith("settings.dimension_restrictions.disable_rtp.")) yield "Disable RTP · " + dimension(path.substring("settings.dimension_restrictions.disable_rtp.".length()));
                if (path.startsWith("settings.dimension_restrictions.disable_teleport.")) yield "Disable teleport · " + dimension(path.substring("settings.dimension_restrictions.disable_teleport.".length()));
                if (path.startsWith("settings.rtp.blacklisted_worlds")) yield "RTP blacklist";
                if (path.startsWith("sounds.")) yield "Sound · " + human(path.substring("sounds.".length()));
                if (path.startsWith("effects.")) yield "Effect · " + human(path.substring("effects.".length()));
                // Keep unknown plugin extensions useful instead of exposing an opaque placeholder.
                yield label(path);
            }
        };
    }

    private static String costTitle(String kind, String actionPath) {
        String[] parts = actionPath.split("\\.");
        String action = parts[0];
        String title = switch (action) {
            case "tpa" -> "TPA requests";
            case "tpahere" -> "TPA here";
            default -> human(action);
        };
        if (parts.length > 1) title += " · " + dimension(parts[1]);
        return kind + " · " + title;
    }

    private static String dimension(String value) {
        return switch (value.toLowerCase(Locale.ROOT)) {
            case "overworld" -> "Overworld";
            case "nether" -> "Nether";
            case "end" -> "End";
            default -> human(value);
        };
    }

    private static String padRight(String value, int width) {
        if (value.length() >= width) return value;
        return value + " ".repeat(width - value.length());
    }

    private static Map<String, Object> editor(String path, Object value, boolean exact, String status) {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("key", "value");
        input.put("label", "New value");
        String current = display(value);
        List<String> options = path.equals("settings.costs.mode") ? List.of("NONE", "XP_LEVELS", "ITEM", "CURRENCY")
            : path.equals("settings.landing.mode") ? List.of("EXACT", "RANDOM_OFFSET") : List.of();
        if (value instanceof Boolean || booleanPath(path)) {
            input.put("type", "minecraft:boolean");
            input.put("initial", Boolean.parseBoolean(current));
        } else if (!options.isEmpty()) {
            input.put("type", "minecraft:single_option");
            input.put("options", options.stream().map(o -> Map.of("id", o, "display", label(o.toLowerCase(Locale.ROOT)), "initial", o.equals(current))).toList());
        } else {
            input.put("type", "minecraft:text");
            input.put("initial", current);
            input.put("max_length", Math.max(2048, current.length()));
            input.put("width", 310);
        }
        Map<String, Object> dialog = base("YATPA  /  Edit setting", label(path) + "\n" + path + "\n\nCurrent value: " + current
            + (value instanceof List<?> ? "\nSeparate entries with commas; leave empty to clear." : "")
            + "\n\nSave applies this setting immediately. Back discards changes.", status, 1);
        dialog.put("inputs", List.of(input));
        List<Object> actions = new ArrayList<>();
        actions.add(Map.of("label", "Save", "action", Map.of("type", "minecraft:dynamic/run_command",
            "template", "/yatpa guisave " + encodePath(path) + " v$(value)")));
        dialog.put("actions", actions);
        dialog.put("exit_action", button("Cancel  ·  " + category(path), "/yatpa gui " + category(path), "Discard unsaved changes", 220));
        return dialog;
    }

    private static Map<String, Object> base(String title, String body, String status, int columns) {
        Map<String, Object> dialog = new LinkedHashMap<>();
        dialog.put("type", "minecraft:multi_action");
        dialog.put("title", title);
        dialog.put("pause", false);
        dialog.put("columns", columns);
        dialog.put("body", List.of(Map.of("type", "minecraft:plain_message", "width", 360,
            "contents", status.isEmpty() ? body : status + "\n\n" + body)));
        return dialog;
    }

    private static Map<String, Object> button(String label, String command, String tooltip) {
        return button(label, command, tooltip, 200);
    }

    private static Map<String, Object> button(String label, String command, String tooltip, int width) {
        return Map.of("label", label, "tooltip", tooltip, "width", width,
            "action", Map.of("type", "minecraft:run_command", "command", command));
    }

    public static String display(Object value) {
        return value instanceof List<?> list ? String.join(",", list.stream().map(String::valueOf).toList()) : String.valueOf(value);
    }

    /** Parses the quoted values produced by the page-level dynamic save action. */
    public static List<String> decodeBulkValues(String raw) {
        List<String> values = new ArrayList<>();
        int i = 0;
        while (i < raw.length()) {
            while (i < raw.length() && Character.isWhitespace(raw.charAt(i))) i++;
            if (i >= raw.length()) break;
            boolean quoted = raw.charAt(i) == '"';
            if (quoted) i++;
            StringBuilder value = new StringBuilder();
            boolean closed = !quoted;
            while (i < raw.length()) {
                char c = raw.charAt(i++);
                if (c == '\\' && i < raw.length() && "\\\"'".indexOf(raw.charAt(i)) >= 0) {
                    value.append(raw.charAt(i++));
                } else if (quoted && c == '"') {
                    closed = true;
                    break;
                } else if (!quoted && Character.isWhitespace(c)) {
                    break;
                } else if (Character.isISOControl(c)) {
                    throw new IllegalArgumentException("Invalid control character");
                } else {
                    value.append(c);
                }
            }
            if (!closed) throw new IllegalArgumentException("Unclosed value");
            values.add(value.toString());
        }
        return values;
    }

    private static boolean booleanPath(String path) {
        return path.startsWith("settings.features.") || path.startsWith("settings.dimension_restrictions.disable_rtp.")
            || path.startsWith("settings.dimension_restrictions.disable_teleport.") || path.startsWith("settings.rtp.blacklisted_worlds.")
            || List.of("settings.cancel_on_move", "settings.cancel_on_damage", "settings.costs.enabled",
                "settings.spawn.enabled", "settings.rtp.rtp_to_overworld").contains(path);
    }

    /** Text macros escape SNBT quotes/backslashes; the leading v also preserves empty input. */
    public static String decodeSubmission(String raw) {
        if (!raw.startsWith("v")) throw new IllegalArgumentException("Missing value marker");
        StringBuilder result = new StringBuilder();
        for (int i = 1; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c == '\\' && i + 1 < raw.length() && "\\\"'".indexOf(raw.charAt(i + 1)) >= 0) c = raw.charAt(++i);
            if (Character.isISOControl(c)) throw new IllegalArgumentException("Use a single line");
            result.append(c);
        }
        return result.toString();
    }

    // A single command token on both Bukkit and Brigadier, including namespaced dimension keys.
    public static String encodePath(String path) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(path.getBytes(StandardCharsets.UTF_8));
    }

    public static String decodePath(String token) {
        return new String(Base64.getUrlDecoder().decode(token), StandardCharsets.UTF_8);
    }
}
