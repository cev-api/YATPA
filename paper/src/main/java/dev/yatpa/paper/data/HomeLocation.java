package dev.yatpa.paper.data;

import org.bukkit.Location;

public record HomeLocation(String world, double x, double y, double z, float yaw, float pitch) {
    public static HomeLocation fromLocation(Location location) {
        return new HomeLocation(
            location.getWorld().getName(),
            location.getX(),
            location.getY(),
            location.getZ(),
            location.getYaw(),
            location.getPitch()
        );
    }
}