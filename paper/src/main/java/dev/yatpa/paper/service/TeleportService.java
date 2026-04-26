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
    private record PendingTeleport(BukkitTask task, Location origin, TeleportKind kind, UUID payerId,
            UUID notifyPlayerId) {
    }

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
        if (p.notifyPlayerId() != null) {
            Player notify = plugin.getServer().getPlayer(p.notifyPlayerId());
            if (notify != null && notify.isOnline()) {
                String reason = messages.get(messageKey);
                if (reason.equals(messageKey)) {
                    reason = messageKey;
                }
                notify.sendMessage(messages.get("prefix") + player.getName() + "'s teleport was cancelled (" + reason + ")");
            }
        }
        play(player, "cancelled");
    }

    public boolean queueTeleport(Player actor, TeleportKind kind, Supplier<Location> destinationSupplier) {
        return queueTeleport(actor, kind, destinationSupplier, () -> {
        }, actor);
    }

    public boolean queueTeleport(Player actor, TeleportKind kind, Supplier<Location> destinationSupplier,
            Runnable onSuccess) {
        return queueTeleport(actor, kind, destinationSupplier, onSuccess, actor, null);
    }

    public boolean queueTeleport(Player actor, TeleportKind kind, Supplier<Location> destinationSupplier,
            Runnable onSuccess, Player payer) {
        return queueTeleport(actor, kind, destinationSupplier, onSuccess, payer, null);
    }

    public boolean queueTeleport(Player actor, TeleportKind kind, Supplier<Location> destinationSupplier,
            Runnable onSuccess, Player payer, Player notifyPlayer) {
        if (config.teleportDisabledIn(actor.getWorld())) {
            tell(actor, "teleport_disabled_dimension", Map.of("dimension", actor.getWorld().getName()));
            return false;
        }
        CostService.ChargeResult preview = costs.preview(payer, kind);
        if (!preview.success()) {
            String template = messages.get("cost_failed");
            if ("cost_failed".equals(template) || !template.contains("%required%")) {
                payer.sendMessage(messages.get("prefix") + "§cYou require " + preview.required() + " to teleport.");
            } else {
                tell(payer, "cost_failed", Map.of("required", preview.required()));
            }
            return false;
        }
        cancel(actor.getUniqueId(), "");

        Location origin = actor.getLocation().clone();
        int delay = Math.max(0, config.teleportDelaySeconds());
        if (delay == 0) {
            execute(actor, kind, payer.getUniqueId(), destinationSupplier, onSuccess);
            return true;
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
                    execute(actor, kind, payer.getUniqueId(), destinationSupplier, onSuccess);
                    cancel(actor.getUniqueId(), "");
                    return;
                }
                String text = messages.format("countdown", Map.of("seconds", Integer.toString(remaining)));
                actor.sendActionBar(Component.text(text));
                play(actor, "countdown");
                remaining--;
            }
        }, 0L, 20L);

        pending.put(actor.getUniqueId(),
                new PendingTeleport(task, origin, kind, payer.getUniqueId(),
                        notifyPlayer == null ? null : notifyPlayer.getUniqueId()));
        return true;
    }

    public CostService.ChargeResult previewCharge(Player actor, TeleportKind kind) {
        return costs.preview(actor, kind);
    }

    private void execute(Player actor, TeleportKind kind, UUID payerId, Supplier<Location> destinationSupplier,
            Runnable onSuccess) {
        if (config.teleportDisabledIn(actor.getWorld())) {
            tell(actor, "teleport_disabled_dimension", Map.of("dimension", actor.getWorld().getName()));
            return;
        }
        Player payer = plugin.getServer().getPlayer(payerId);
        if (payer == null || !payer.isOnline()) {
            actor.sendMessage(messages.get("prefix") + "§cTeleport cancelled because payment could not be collected.");
            return;
        }
        CostService.ChargeResult charge = costs.charge(payer, kind);
        if (!charge.success()) {
            String template = messages.get("cost_failed");
            if ("cost_failed".equals(template) || !template.contains("%required%")) {
                payer.sendMessage(messages.get("prefix") + "§cYou require " + charge.required() + " to teleport.");
            } else {
                tell(payer, "cost_failed", Map.of("required", charge.required()));
            }
            return;
        }
        if (charge.paid() != null && !charge.paid().isBlank()) {
            payer.sendMessage(messages.get("prefix") + "Paid " + charge.paid() + ".");
        }
        Location destination = destinationSupplier.get();
        if (destination == null) {
            return;
        }
        if (config.teleportDisabledIn(destination.getWorld())) {
            String dimension = destination.getWorld() == null ? "unknown" : destination.getWorld().getName();
            tell(actor, "teleport_disabled_dimension", Map.of("dimension", dimension));
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
        int minY = world.getMinHeight() + 1;
        int maxY = maxSafeLandingY(world);
        for (int i = 0; i < 8; i++) {
            int dx = ThreadLocalRandom.current().nextInt(maxOffset * 2 + 1) - maxOffset;
            int dz = ThreadLocalRandom.current().nextInt(maxOffset * 2 + 1) - maxOffset;
            int x = base.getBlockX() + dx;
            int z = base.getBlockZ() + dz;
            int y = Math.max(minY, Math.min(maxY, world.getHighestBlockYAt(x, z) + 1));
            Location candidate = new Location(world, x + 0.5, y, z + 0.5, base.getYaw(), base.getPitch());
            if (isSafeStandLocation(candidate, minY, maxY)) {
                return candidate;
            }
        }
        return base;
    }

    private int maxSafeLandingY(World world) {
        int maxY = world.getMaxHeight() - 2;
        if (world.getEnvironment() == World.Environment.NETHER) {
            // Keep random landing offsets below the nether roof.
            maxY = Math.min(maxY, 127);
        }
        return maxY;
    }

    private boolean isSafeStandLocation(Location location, int minY, int maxY) {
        World world = location.getWorld();
        if (world == null) {
            return false;
        }
        int y = location.getBlockY();
        if (y < minY || y > maxY) {
            return false;
        }
        Location feet = location;
        Location head = location.clone().add(0, 1, 0);
        Location below = location.clone().subtract(0, 1, 0);
        boolean feetClear = feet.getBlock().isPassable() && !feet.getBlock().isLiquid();
        boolean headClear = head.getBlock().isPassable() && !head.getBlock().isLiquid();
        boolean belowSolid = below.getBlock().getType().isSolid() && !below.getBlock().isLiquid();
        return feetClear && headClear && belowSolid;
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
        if (now.getBlockX() != p.origin().getBlockX() || now.getBlockY() != p.origin().getBlockY()
                || now.getBlockZ() != p.origin().getBlockZ()) {
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

    public void teleportToSpawn(Player player) {
        Location spawn = new Location(
                plugin.getServer().getWorld(config.spawnWorld()),
                config.spawnX(),
                config.spawnY(),
                config.spawnZ(),
                config.spawnYaw().floatValue(),
                config.spawnPitch().floatValue());
        player.teleport(spawn);

    }
}
