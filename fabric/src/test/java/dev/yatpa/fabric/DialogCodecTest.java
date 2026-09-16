package dev.yatpa.fabric;

import com.google.gson.Gson;
import dev.yatpa.dialog.SettingsDialog;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.SharedConstants;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.TagParser;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.dialog.Dialog;
import net.minecraft.server.dialog.MultiActionDialog;
import net.minecraft.server.dialog.action.Action;
import net.minecraft.network.chat.ClickEvent;

/** Parses the exact payload sent by both adapters using Minecraft's own SNBT and dialog codecs. */
public final class DialogCodecTest {
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("settings.features.tpa", true);
        values.put("settings.teleport_delay_seconds", 5);
        values.put("settings.costs.mode", "ITEM");
        values.put("settings.landing.mode", "RANDOM_OFFSET");
        values.put("settings.spawn.x", -123.456789);
        values.put("settings.spawn.world", "a 'quoted' \\\"world\\\"");
        values.put("settings.rtp.blacklisted_worlds", List.of("world", "minecraft:the_end"));
        values.put("sounds.success", "ENTITY_ENDERMAN_TELEPORT");
        values.put("effects.success", "END_ROD");
        parse(values, "");
        parse(values, "unknown");
        for (String path : values.keySet()) {
            parse(values, SettingsDialog.category(path));
            parse(values, path);
            parse(values, "exact:" + path);
        }
        MultiActionDialog inline = (MultiActionDialog) parse(Map.of(
            "settings.features.tpa", true,
            "settings.features.tpahere", false), "Features");
        ClickEvent.RunCommand bulk = (ClickEvent.RunCommand) inline.actions().getFirst().action().orElseThrow()
            .createAction(Map.of("v0", Action.ValueGetter.of("true"), "v1", Action.ValueGetter.of("false"))).orElseThrow();
        String bulkCommand = bulk.command();
        String bulkValues = bulkCommand.substring(bulkCommand.indexOf("Features#1 ") + "Features#1 ".length());
        if (!SettingsDialog.decodeBulkValues(bulkValues).equals(List.of("true", "false"))) {
            throw new AssertionError("Inline controls did not round-trip");
        }
        String path = "settings.dimension_restrictions.disable_rtp.minecraft:the_end";
        for (String value : List.of("", "true", "12", "world with spaces", "quote's \"test\" \\path", "$(value)")) {
            MultiActionDialog dialog = (MultiActionDialog) parse(Map.of(path, value), "exact:" + path);
            ClickEvent.RunCommand click = (ClickEvent.RunCommand) dialog.actions().getFirst().action().orElseThrow()
                .createAction(Map.of("value", Action.ValueGetter.of(value))).orElseThrow();
            String[] parts = click.command().split(" ", 4);
            if (!parts[0].equals("/yatpa") || !parts[1].equals("guisave")
                || !SettingsDialog.decodePath(parts[2]).equals(path)
                || !SettingsDialog.decodeSubmission(parts[3]).equals(value)) {
                throw new AssertionError("Submission did not round-trip: " + value);
            }
        }
        System.out.println("Minecraft dialog codec checks passed.");
    }

    private static Dialog parse(Map<String, Object> values, String route) throws Exception {
        String json = new Gson().toJson(SettingsDialog.screen(values, route, "Saved successfully."));
        return Dialog.DIRECT_CODEC.parse(NbtOps.INSTANCE, TagParser.parseCompoundFully(json)).getOrThrow();
    }
}
