package dev.yatpa.paper.service;

import dev.yatpa.paper.config.XmlMessages;
import dev.yatpa.paper.config.YatpaConfig;
import dev.yatpa.paper.data.TeleportKind;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;
import net.kyori.adventure.text.Component;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

public class TeleportService {
    private record PendingTeleport(BukkitTask task, Location origin, String cancelKey) {}

    private final JavaPlugin plugin;
    private final YatpaConfig config;
    private final XmlMessages messages;
    private final CostService costs;
    private final Map<UUID, PendingTeleport> pending = new ConcurrentHashMap<>();

    public TeleportService(JavaPlugin plugin, YatpaConfig config, XmlMessages messages, CostService costs) {
        this.plugin = plugin;
        this.config = config;
        this.messages = messages;
        this.costs = costs;
    }

    public void cancel(UUID uuid, String messageKey) {
        PendingTeleport p = pending.remove(uuid);
        if (p != null) {
            p.task.cancel();
        }
    }

    public void cancelWithNotice(Player player, String messageKey) {
        PendingTeleport p = pending.remove(player.getUniqueId());
        if (p == null) {
            return;
        }
        p.task.cancel();
        tell(player, messageKey);
        play(player, "cancelled");
    }

    public void queueTeleport(Player actor, TeleportKind kind, Supplier<Location> destinationSupplier) {
        queueTeleport(actor, kind, destinationSupplier, () -> {});
    }

    public void queueTeleport(Player actor, TeleportKind kind, Supplier<Location> destinationSupplier, Runnable onSuccess) {
        CostService.ChargeResult charge = costs.charge(actor, kind);
        if (!charge.success()) {
            String template = messages.get("cost_failed");
            if ("cost_failed".equals(template) || !template.contains("%required%")) {
                actor.sendMessage(messages.get("prefix") + "§cYou require " + charge.required() + " to teleport.");
            } else {
                tell(actor, "cost_failed", Map.of("required", charge.required()));
            }
            return;
        }
        cancel(actor.getUniqueId(), "");

        Location origin = actor.getLocation().clone();
        int delay = Math.max(0, config.teleportDelaySeconds());
        if (delay == 0) {
            execute(actor, destinationSupplier, onSuccess);
            return;
        }

        BukkitTask task = plugin.getServer().getScheduler().runTaskTimer(plugin, new Runnable() {
            int remaining = delay;

            @Override
            public void run() {
                if (!actor.isOnline()) {
                    cancel(actor.getUniqueId(), "");
                    return;
                }
                if (remaining <= 0) {
                    execute(actor, destinationSupplier, onSuccess);
                    cancel(actor.getUniqueId(), "");
                    return;
                }
                String text = messages.format("countdown", Map.of("seconds", Integer.toString(remaining)));
                actor.sendActionBar(Component.text(text));
                play(actor, "countdown");
                remaining--;
            }
        }, 0L, 20L);

        pending.put(actor.getUniqueId(), new PendingTeleport(task, origin, ""));
    }

    private void execute(Player actor, Supplier<Location> destinationSupplier, Runnable onSuccess) {
        Location destination = destinationSupplier.get();
        if (destination == null) {
            return;
        }
        Location adjusted = adjustLanding(destination);
        if (actor.teleport(adjusted)) {
            onSuccess.run();
            tell(actor, "teleport_success");
            play(actor, "success");
        }
    }

    private Location adjustLanding(Location base) {
        if (config.landingMode() != YatpaConfig.LandingMode.RANDOM_OFFSET) {
            return base;
        }

        World world = base.getWorld();
        if (world == null) {
            return base;
        }
        int maxOffset = Math.max(1, config.landingRandomOffset());
        for (int i = 0; i < 8; i++) {
            int dx = ThreadLocalRandom.current().nextInt(maxOffset * 2 + 1) - maxOffset;
            int dz = ThreadLocalRandom.current().nextInt(maxOffset * 2 + 1) - maxOffset;
            int x = base.getBlockX() + dx;
            int z = base.getBlockZ() + dz;
            int y = world.getHighestBlockYAt(x, z) + 1;
            Location candidate = new Location(world, x + 0.5, y, z + 0.5, base.getYaw(), base.getPitch());
            if (candidate.getBlock().isPassable() && Objects.requireNonNull(candidate.clone().subtract(0, 1, 0).getBlock().getType()).isSolid()) {
                return candidate;
            }
        }
        return base;
    }

    public void onMove(Player player) {
        if (!config.cancelOnMove()) {
            return;
        }
        PendingTeleport p = pending.get(player.getUniqueId());
        if (p == null) {
            return;
        }
        Location now = player.getLocation();
        if (now.getBlockX() != p.origin().getBlockX() || now.getBlockY() != p.origin().getBlockY() || now.getBlockZ() != p.origin().getBlockZ()) {
            cancelWithNotice(player, "cancelled_move");
        }
    }

    public void onDamage(Player player) {
        if (!config.cancelOnDamage()) {
            return;
        }
        if (pending.containsKey(player.getUniqueId())) {
            cancelWithNotice(player, "cancelled_damage");
        }
    }

    private void tell(Player player, String key) {
        player.sendMessage(messages.get("prefix") + messages.get(key));
    }

    private void tell(Player player, String key, Map<String, String> replacements) {
        player.sendMessage(messages.get("prefix") + messages.format(key, replacements));
    }

    public void play(Player player, String key) {
        Sound sound = config.sound(key);
        if (sound != null) {
            player.playSound(player.getLocation(), sound, 1.0f, 1.0f);
        }
        Particle particle = config.effect(key);
        if (particle != null) {
            player.getWorld().spawnParticle(particle, player.getLocation().add(0, 1, 0), 20, 0.4, 0.6, 0.4, 0.02);
        }
    }
}
