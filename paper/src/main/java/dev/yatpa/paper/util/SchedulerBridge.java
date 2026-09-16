package dev.yatpa.paper.util;

import java.lang.reflect.Method;
import java.util.function.Consumer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

/**
 * Keeps scheduling on the correct thread on Folia while retaining the normal
 * Bukkit scheduler on Spigot, Paper and Bukkit derivatives.
 *
 * Folia-only scheduler types are accessed reflectively so the same jar remains
 * loadable on servers that do not ship them.
 */
public final class SchedulerBridge {
    private SchedulerBridge() {
    }

    public interface Task {
        void cancel();
    }

    public static void cancelTasks(JavaPlugin plugin) {
        Object global = globalScheduler(plugin);
        if (global != null) {
            try {
                Method method = find(global.getClass(), "cancelTasks", 1);
                method.invoke(global, plugin);
                return;
            } catch (ReflectiveOperationException ignored) {
                // Fall through to Bukkit's scheduler on non-Folia-compatible forks.
            }
        }
        try {
            Bukkit.getScheduler().cancelTasks(plugin);
        } catch (UnsupportedOperationException ignored) {
            // Folia has no global Bukkit scheduler; entity/global tasks are retired by the bridge.
        }
    }

    public static Task runRepeating(JavaPlugin plugin, Runnable action, long initialDelay, long period) {
        Object global = globalScheduler(plugin);
        if (global != null) {
            try {
                Method method = find(global.getClass(), "runAtFixedRate", 4);
                Object scheduled = method.invoke(global, plugin, (Consumer<Object>) ignored -> action.run(), initialDelay, period);
                return reflectiveTask(scheduled);
            } catch (ReflectiveOperationException ignored) {
                // Fall through to the Bukkit scheduler on unusual compatible forks.
            }
        }
        BukkitTask task = Bukkit.getScheduler().runTaskTimer(plugin, action, initialDelay, period);
        return task::cancel;
    }

    public static Task runForPlayer(JavaPlugin plugin, Player player, Runnable action) {
        Object scheduler = entityScheduler(player);
        if (scheduler != null) {
            try {
                Method method = find(scheduler.getClass(), "run", 3);
                Object scheduled = method.invoke(scheduler, plugin, (Consumer<Object>) ignored -> action.run(), (Runnable) () -> {
                });
                return reflectiveTask(scheduled);
            } catch (ReflectiveOperationException ignored) {
                // Fall through to the Bukkit scheduler on unusual compatible forks.
            }
        }
        BukkitTask task = Bukkit.getScheduler().runTask(plugin, action);
        return task::cancel;
    }

    public static Task runRepeatingForPlayer(JavaPlugin plugin, Player player, Runnable action,
            long initialDelay, long period) {
        Object scheduler = entityScheduler(player);
        if (scheduler != null) {
            try {
                Method method = find(scheduler.getClass(), "runAtFixedRate", 5);
                Object scheduled = method.invoke(scheduler, plugin, (Consumer<Object>) ignored -> action.run(),
                        (Runnable) () -> {
                        }, initialDelay, period);
                return reflectiveTask(scheduled);
            } catch (ReflectiveOperationException ignored) {
                // Fall through to the Bukkit scheduler on unusual compatible forks.
            }
        }
        BukkitTask task = Bukkit.getScheduler().runTaskTimer(plugin, action, initialDelay, period);
        return task::cancel;
    }

    private static Object globalScheduler(JavaPlugin plugin) {
        try {
            return plugin.getServer().getClass().getMethod("getGlobalRegionScheduler").invoke(plugin.getServer());
        } catch (ReflectiveOperationException ignored) {
            return null;
        }
    }

    private static Object entityScheduler(Player player) {
        try {
            return player.getClass().getMethod("getScheduler").invoke(player);
        } catch (ReflectiveOperationException ignored) {
            return null;
        }
    }

    private static Method find(Class<?> type, String name, int parameterCount) throws NoSuchMethodException {
        for (Method method : type.getMethods()) {
            if (method.getName().equals(name) && method.getParameterCount() == parameterCount) {
                return method;
            }
        }
        throw new NoSuchMethodException(name);
    }

    private static Task reflectiveTask(Object scheduled) {
        return () -> {
            if (scheduled == null) {
                return;
            }
            try {
                scheduled.getClass().getMethod("cancel").invoke(scheduled);
            } catch (ReflectiveOperationException ignored) {
                // A task that has already completed or been retired needs no action.
            }
        };
    }
}
