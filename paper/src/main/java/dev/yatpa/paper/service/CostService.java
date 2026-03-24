package dev.yatpa.paper.service;

import dev.yatpa.paper.data.TeleportKind;
import dev.yatpa.paper.config.YatpaConfig;
import java.util.Arrays;
import java.util.Locale;
import java.util.stream.Collectors;
import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

public class CostService {
    public record ChargeResult(boolean success, String required, String paid) {
        public static ChargeResult ok() { return new ChargeResult(true, "", ""); }
        public static ChargeResult okPaid(String paid) { return new ChargeResult(true, "", paid); }
        public static ChargeResult fail(String required) { return new ChargeResult(false, required, ""); }
    }

    private final YatpaConfig config;
    private final Economy economy;

    public CostService(YatpaConfig config, Economy economy) {
        this.config = config;
        this.economy = economy;
    }

    public ChargeResult charge(Player player, TeleportKind kind) {
        return assess(player, kind, true);
    }

    public ChargeResult preview(Player player, TeleportKind kind) {
        return assess(player, kind, false);
    }

    private ChargeResult assess(Player player, TeleportKind kind, boolean deduct) {
        if (!config.costsEnabled() || config.costMode() == YatpaConfig.CostMode.NONE) {
            return ChargeResult.ok();
        }
        if (config.costMode() == YatpaConfig.CostMode.XP_LEVELS) {
            int cost = config.xpCost(kind, player.getWorld());
            if (cost <= 0) {
                return ChargeResult.ok();
            }
            if (player.getLevel() < cost) {
                return ChargeResult.fail(cost + " XP level" + (cost == 1 ? "" : "s"));
            }
            if (deduct) {
                player.setLevel(player.getLevel() - cost);
            }
            return ChargeResult.okPaid(cost + " XP level" + (cost == 1 ? "" : "s"));
        }
        if (config.costMode() == YatpaConfig.CostMode.ITEM) {
            int amount = config.itemCost(kind, player.getWorld());
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
            if (deduct) {
                player.getInventory().removeItem(new ItemStack(config.costItem(), amount));
            }
            return ChargeResult.okPaid(amount + " " + itemDisplayName(config.costItem().name()));
        }
        if (config.costMode() == YatpaConfig.CostMode.CURRENCY) {
            double amount = config.currencyCost(kind, player.getWorld());
            if (amount <= 0) {
                return ChargeResult.ok();
            }
            if (economy == null) {
                return ChargeResult.fail("server currency (Vault/EssentialsX) is unavailable");
            }
            if (!economy.has(player, amount)) {
                return ChargeResult.fail(formatCurrency(amount));
            }
            if (deduct) {
                EconomyResponse response = economy.withdrawPlayer(player, amount);
                if (!response.transactionSuccess()) {
                    return ChargeResult.fail(formatCurrency(amount));
                }
            }
            return ChargeResult.okPaid(formatCurrency(amount));
        }
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

    private String formatCurrency(double amount) {
        if (economy != null) {
            return economy.format(amount);
        }
        return String.format(Locale.US, "%.2f", amount);
    }
}
