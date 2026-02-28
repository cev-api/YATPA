package dev.yatpa.paper.config;

import dev.yatpa.paper.data.TeleportKind;
import java.util.EnumMap;
import java.util.Map;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.configuration.file.FileConfiguration;

public class YatpaConfig {
    public enum LandingMode {
        EXACT,
        RANDOM_OFFSET
    }

    public enum CostMode {
        NONE,
        XP_LEVELS,
        ITEM
    }

    private final int maxHomesDefault;
    private final int requestTimeoutSeconds;
    private final int requestCooldownSeconds;
    private final int teleportDelaySeconds;
    private final boolean cancelOnMove;
    private final boolean cancelOnDamage;
    private final int spawnRadius;
    private final int rtpCooldownSeconds;
    private final int rtpMinDistance;
    private final int rtpMaxDistance;
    private final LandingMode landingMode;
    private final int landingRandomOffset;
    private final boolean appEnabled;
    private final boolean tpaEnabled;
    private final boolean tpaHereEnabled;
    private final boolean homesEnabled;
    private final boolean rtpEnabled;
    private final boolean costsEnabled;
    private final CostMode costMode;
    private final Material costItem;
    private final Map<TeleportKind, Integer> xpCosts;
    private final Map<TeleportKind, Integer> itemCosts;
    private final Map<String, Sound> sounds;
    private final Map<String, Particle> effects;

    private YatpaConfig(
        int maxHomesDefault,
        int requestTimeoutSeconds,
        int requestCooldownSeconds,
        int teleportDelaySeconds,
        boolean cancelOnMove,
        boolean cancelOnDamage,
        int spawnRadius,
        int rtpCooldownSeconds,
        int rtpMinDistance,
        int rtpMaxDistance,
        LandingMode landingMode,
        int landingRandomOffset,
        boolean appEnabled,
        boolean tpaEnabled,
        boolean tpaHereEnabled,
        boolean homesEnabled,
        boolean rtpEnabled,
        boolean costsEnabled,
        CostMode costMode,
        Material costItem,
        Map<TeleportKind, Integer> xpCosts,
        Map<TeleportKind, Integer> itemCosts,
        Map<String, Sound> sounds,
        Map<String, Particle> effects
    ) {
        this.maxHomesDefault = maxHomesDefault;
        this.requestTimeoutSeconds = requestTimeoutSeconds;
        this.requestCooldownSeconds = requestCooldownSeconds;
        this.teleportDelaySeconds = teleportDelaySeconds;
        this.cancelOnMove = cancelOnMove;
        this.cancelOnDamage = cancelOnDamage;
        this.spawnRadius = spawnRadius;
        this.rtpCooldownSeconds = rtpCooldownSeconds;
        this.rtpMinDistance = rtpMinDistance;
        this.rtpMaxDistance = rtpMaxDistance;
        this.landingMode = landingMode;
        this.landingRandomOffset = landingRandomOffset;
        this.appEnabled = appEnabled;
        this.tpaEnabled = tpaEnabled;
        this.tpaHereEnabled = tpaHereEnabled;
        this.homesEnabled = homesEnabled;
        this.rtpEnabled = rtpEnabled;
        this.costsEnabled = costsEnabled;
        this.costMode = costMode;
        this.costItem = costItem;
        this.xpCosts = xpCosts;
        this.itemCosts = itemCosts;
        this.sounds = sounds;
        this.effects = effects;
    }

    public static YatpaConfig from(FileConfiguration config) {
        Map<TeleportKind, Integer> xp = new EnumMap<>(TeleportKind.class);
        Map<TeleportKind, Integer> items = new EnumMap<>(TeleportKind.class);
        for (TeleportKind kind : TeleportKind.values()) {
            String key = kind.name().toLowerCase();
            xp.put(kind, config.getInt("settings.costs.xp_levels." + key, 0));
            items.put(kind, config.getInt("settings.costs.item." + key, 0));
        }

        Map<String, Sound> sounds = new java.util.HashMap<>();
        for (String key : new String[]{"request_sent", "request_received", "countdown", "success", "cancelled"}) {
            sounds.put(key, parseSound(config.getString("sounds." + key, "")));
        }

        Map<String, Particle> effects = new java.util.HashMap<>();
        for (String key : new String[]{"request_sent", "request_received", "countdown", "success", "cancelled"}) {
            effects.put(key, parseParticle(config.getString("effects." + key, "")));
        }

        return new YatpaConfig(
            config.getInt("settings.max_homes_default", 3),
            config.getInt("settings.request_timeout_seconds", 60),
            config.getInt("settings.request_cooldown_seconds", 30),
            config.getInt("settings.teleport_delay_seconds", 5),
            config.getBoolean("settings.cancel_on_move", true),
            config.getBoolean("settings.cancel_on_damage", true),
            config.getInt("settings.spawn_radius", 50),
            config.getInt("settings.rtp_cooldown_seconds", 300),
            config.getInt("settings.rtp.default_min_distance", 64),
            config.getInt("settings.rtp.default_max_distance", 2500),
            parseLanding(config.getString("settings.landing.mode", "EXACT")),
            config.getInt("settings.landing.random_offset_max", 4),
            config.getBoolean("settings.features.enabled", true),
            config.getBoolean("settings.features.tpa", true),
            config.getBoolean("settings.features.tpahere", true),
            config.getBoolean("settings.features.homes", true),
            config.getBoolean("settings.features.rtp", true),
            config.getBoolean("settings.costs.enabled", false),
            parseCostMode(config.getString("settings.costs.mode", "NONE")),
            parseMaterial(config.getString("settings.costs.item.material", "ENDER_PEARL")),
            xp,
            items,
            sounds,
            effects
        );
    }

    private static Sound parseSound(String name) {
        try {
            return name == null || name.isBlank() ? null : Sound.valueOf(name);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private static Particle parseParticle(String name) {
        try {
            return name == null || name.isBlank() ? null : Particle.valueOf(name);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private static LandingMode parseLanding(String mode) {
        try {
            return LandingMode.valueOf(mode.toUpperCase());
        } catch (IllegalArgumentException ex) {
            return LandingMode.EXACT;
        }
    }

    private static CostMode parseCostMode(String mode) {
        try {
            return CostMode.valueOf(mode.toUpperCase());
        } catch (IllegalArgumentException ex) {
            return CostMode.NONE;
        }
    }

    private static Material parseMaterial(String material) {
        try {
            return Material.valueOf(material.toUpperCase());
        } catch (IllegalArgumentException ex) {
            return Material.ENDER_PEARL;
        }
    }

    public int maxHomesDefault() { return maxHomesDefault; }
    public int requestTimeoutSeconds() { return requestTimeoutSeconds; }
    public int requestCooldownSeconds() { return requestCooldownSeconds; }
    public int teleportDelaySeconds() { return teleportDelaySeconds; }
    public boolean cancelOnMove() { return cancelOnMove; }
    public boolean cancelOnDamage() { return cancelOnDamage; }
    public int spawnRadius() { return spawnRadius; }
    public int rtpCooldownSeconds() { return rtpCooldownSeconds; }
    public int rtpMinDistance() { return rtpMinDistance; }
    public int rtpMaxDistance() { return rtpMaxDistance; }
    public LandingMode landingMode() { return landingMode; }
    public int landingRandomOffset() { return landingRandomOffset; }
    public boolean appEnabled() { return appEnabled; }
    public boolean tpaEnabled() { return tpaEnabled; }
    public boolean tpaHereEnabled() { return tpaHereEnabled; }
    public boolean homesEnabled() { return homesEnabled; }
    public boolean rtpEnabled() { return rtpEnabled; }
    public boolean costsEnabled() { return costsEnabled; }
    public CostMode costMode() { return costMode; }
    public Material costItem() { return costItem; }
    public int xpCost(TeleportKind kind) { return xpCosts.getOrDefault(kind, 0); }
    public int itemCost(TeleportKind kind) { return itemCosts.getOrDefault(kind, 0); }
    public Sound sound(String key) { return sounds.get(key); }
    public Particle effect(String key) { return effects.get(key); }
}
