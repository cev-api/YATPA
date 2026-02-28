package dev.yatpa.paper.service;

import dev.yatpa.paper.data.TeleportKind;
import dev.yatpa.paper.config.YatpaConfig;
import java.util.Arrays;
import java.util.Locale;
import java.util.stream.Collectors;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

public class CostService {
    public record ChargeResult(boolean success, String required) {
        public static ChargeResult ok() {
            return new ChargeResult(true, "");
        }

        public static ChargeResult fail(String required) {
            return new ChargeResult(false, required);
        }
    }

    private final YatpaConfig config;

    public CostService(YatpaConfig config) {
        this.config = config;
    }

    public ChargeResult charge(Player player, TeleportKind kind) {
        if (!config.costsEnabled() || config.costMode() == YatpaConfig.CostMode.NONE) {
            return ChargeResult.ok();
        }
        if (config.costMode() == YatpaConfig.CostMode.XP_LEVELS) {
            int cost = config.xpCost(kind);
            if (cost <= 0) {
                return ChargeResult.ok();
            }
            if (player.getLevel() < cost) {
                return ChargeResult.fail(cost + " XP level" + (cost == 1 ? "" : "s"));
            }
            player.setLevel(player.getLevel() - cost);
            return ChargeResult.ok();
        }
        int amount = config.itemCost(kind);
        if (amount <= 0) {
            return ChargeResult.ok();
        }
        int remaining = amount;
        ItemStack[] contents = player.getInventory().getContents();
        for (ItemStack stack : contents) {
            if (stack == null || stack.getType() != config.costItem()) {
                continue;
            }
            remaining -= stack.getAmount();
            if (remaining <= 0) {
                break;
            }
        }
        if (remaining > 0) {
            return ChargeResult.fail(amount + " " + itemDisplayName(config.costItem().name()));
        }
        player.getInventory().removeItem(new ItemStack(config.costItem(), amount));
        return ChargeResult.ok();
    }

    private String itemDisplayName(String material) {
        String singular = Arrays.stream(material.toLowerCase(Locale.ROOT).split("_"))
            .map(part -> Character.toUpperCase(part.charAt(0)) + part.substring(1))
            .collect(Collectors.joining(" "));
        if (singular.endsWith("s")) {
            return singular;
        }
        return singular + "s";
    }
}
