package dev.yatpa.paper.util;

import java.lang.reflect.Method;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/** Sends Adventure components when a server exposes Paper's audience methods. */
public final class TextCompat {
    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();

    private TextCompat() {
    }

    public static void send(CommandSender sender, String message) {
        sender.sendMessage(message);
    }

    public static void send(CommandSender sender, Component message) {
        try {
            Method method = sender.getClass().getMethod("sendMessage", Component.class);
            method.invoke(sender, message);
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            sender.sendMessage(PLAIN.serialize(message));
        }
    }

    public static void actionBar(Player player, Component message) {
        try {
            Method method = player.getClass().getMethod("sendActionBar", Component.class);
            method.invoke(player, message);
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            player.sendActionBar(PLAIN.serialize(message));
        }
    }
}
