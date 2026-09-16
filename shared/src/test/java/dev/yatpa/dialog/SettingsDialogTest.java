package dev.yatpa.dialog;

import java.util.List;
import java.util.Map;

/** Dependency-free regression checks, run by both platforms' check tasks. */
public final class SettingsDialogTest {
    public static void main(String[] args) {
        check(SettingsDialog.decodeSubmission("v").equals(""), "empty input");
        check(SettingsDialog.decodeBulkValues("\"hello world\" \"\" \"quote\\\"test\" \\path").equals(List.of("hello world", "", "quote\"test", "\\path")), "bulk value parsing");
        check(SettingsDialog.decodeSubmission("vworld \\" + "\"quoted\\\" \\\\ path\\'s").equals("world \"quoted\" \\ path's"), "SNBT escapes");
        try { SettingsDialog.decodeSubmission("vbad\ncommand"); throw new AssertionError("control characters accepted"); }
        catch (IllegalArgumentException expected) { }
        check(input("settings.cancel_on_move", true, false).get("type").equals("minecraft:boolean"), "checkbox");
        check(input("settings.cancel_on_move", "false", false).get("initial").equals(false), "Fabric boolean");
        check(input("settings.spawn.world", "true", false).get("type").equals("minecraft:text"), "boolean-looking world name");
        check(input("settings.spawn.world", "123", false).get("type").equals("minecraft:text"), "numeric world name");
        check(input("settings.teleport_delay_seconds", 5, false).get("type").equals("minecraft:text"), "text input default");
        check(input("settings.teleport_delay_seconds", 5, true).get("type").equals("minecraft:text"), "exact input");
        check(input("settings.spawn.x", 1.23456789, false).get("initial").equals("1.23456789"), "decimal precision");
        check(input("settings.spawn.x", -123, false).get("initial").equals("-123"), "negative coordinates");
        check(input("settings.spawn.x", Long.MAX_VALUE, false).get("initial").equals(Long.toString(Long.MAX_VALUE)), "large values");
        check(input("settings.costs.mode", "ITEM", false).get("type").equals("minecraft:single_option"), "mode selector");
        check(input("settings.rtp.blacklisted_worlds", List.of("world", "minecraft:the_end"), false).get("initial").equals("world,minecraft:the_end"), "list editing");
        Map<String, Object> screen = SettingsDialog.screen(Map.of("settings.features.tpa", true), "", "");
        check(((List<?>) screen.get("actions")).size() == 2, "category and reload buttons");
        check(screen.get("title").equals("YATPA  /  Admin settings"), "dashboard title");
        Map<String, Object> category = SettingsDialog.screen(Map.of("settings.features.tpa", true), "Features", "");
        check(category.get("title").equals("YATPA  /  Features"), "category title");
        check(((List<?>) category.get("inputs")).size() == 1, "inline setting control");
        String controlLabel = (String) ((Map<?, ?>) ((List<?>) category.get("inputs")).getFirst()).get("label");
        check(!controlLabel.contains("\n") && controlLabel.trim().equals("Allow TPA requests"), "concise aligned label");
        Map<String, Object> costs = SettingsDialog.screen(Map.of(
            "settings.costs.xp_levels.rtp.nether", 3,
            "settings.costs.item.spawn", 2), "Costs", "");
        check(labelIn(costs, "XP · RTP · Nether"), "distinct cost label");
        check(labelIn(SettingsDialog.screen(Map.of("settings.costs.item.spawn", 2), "Costs", ""), "Item · Spawn"), "action cost label");
        check(labelIn(SettingsDialog.screen(Map.of("settings.spawn_radius", 50), "Core", ""), "Spawn search radius"), "core setting label");
        check(SettingsDialog.screen(Map.of("settings.spawn.x", 1.25), "settings.spawn.x", "").get("columns").equals(1), "focused editor layout");
        check(input("settings.spawn.x", 1.25, false).get("type").equals("minecraft:text"), "coordinate text input");
        check(SettingsDialog.category("settings.dimension_restrictions.disable_rtp.nether").equals("Restrictions"), "category mapping");
        System.out.println("Dialog regression checks passed.");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> input(String path, Object value, boolean exact) {
        return (Map<String, Object>) ((List<?>) SettingsDialog.screen(Map.of(path, value), (exact ? "exact:" : "") + path, "").get("inputs")).getFirst();
    }

    private static boolean labelIn(Map<String, Object> dialog, String expected) {
        return ((List<?>) dialog.get("inputs")).stream()
            .map(value -> (String) ((Map<?, ?>) value).get("label"))
            .anyMatch(value -> value.trim().equals(expected));
    }

    private static void check(boolean condition, String name) {
        if (!condition) throw new AssertionError(name);
    }
}
