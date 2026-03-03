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
    private final Map<String, Integer> realmRtpMin;
    private final Map<String, Integer> realmRtpMax;
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
    private final Map<String, Integer> realmXpRtpCosts;
    private final Map<String, Integer> realmItemRtpCosts;
    private final Map<String, Map<TeleportKind, Integer>> xpCostsByWorld;
    private final Map<String, Map<TeleportKind, Integer>> itemCostsByWorld;
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
        Map<String, Particle> effects,
        Map<String, Map<TeleportKind, Integer>> xpCostsByWorld,
        Map<String, Map<TeleportKind, Integer>> itemCostsByWorld,
        Map<String, Integer> realmXpRtpCosts,
        Map<String, Integer> realmItemRtpCosts,
        Map<String, Integer> realmRtpMin,
        Map<String, Integer> realmRtpMax
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
        this.xpCostsByWorld = xpCostsByWorld != null ? xpCostsByWorld : new java.util.HashMap<>();
        this.itemCostsByWorld = itemCostsByWorld != null ? itemCostsByWorld : new java.util.HashMap<>();
        this.realmXpRtpCosts = realmXpRtpCosts != null ? realmXpRtpCosts : new java.util.HashMap<>();
        this.realmItemRtpCosts = realmItemRtpCosts != null ? realmItemRtpCosts : new java.util.HashMap<>();
        this.realmRtpMin = realmRtpMin != null ? realmRtpMin : new java.util.HashMap<>();
        this.realmRtpMax = realmRtpMax != null ? realmRtpMax : new java.util.HashMap<>();
        this.sounds = sounds;
        this.effects = effects;
    }

    public static YatpaConfig from(FileConfiguration config) {
        Map<TeleportKind, Integer> xp = new EnumMap<>(TeleportKind.class);
        Map<TeleportKind, Integer> items = new EnumMap<>(TeleportKind.class);
        Map<String, Map<TeleportKind, Integer>> xpByWorld = new java.util.HashMap<>();
        Map<String, Map<TeleportKind, Integer>> itemByWorld = new java.util.HashMap<>();
        Map<String, Integer> realmXpRtp = new java.util.HashMap<>();
        Map<String, Integer> realmItemRtp = new java.util.HashMap<>();
        Map<String, Integer> realmMin = new java.util.HashMap<>();
        Map<String, Integer> realmMax = new java.util.HashMap<>();
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

        // parse per-world overrides using keys like settings.costs.xp_levels.<world>.<teleport>
        for (String full : config.getKeys(true)) {
            if (full.startsWith("settings.costs.xp_levels.")) {
                String rest = full.substring("settings.costs.xp_levels.".length());
                int idx = rest.indexOf('.');
                if (idx > 0) {
                    String world = rest.substring(0, idx);
                    String kindKey = rest.substring(idx + 1);
                    try {
                        TeleportKind kind = TeleportKind.valueOf(kindKey.toUpperCase());
                        int val = config.getInt(full, 0);
                        xpByWorld.computeIfAbsent(world, w -> new EnumMap<>(TeleportKind.class)).put(kind, val);
                    } catch (IllegalArgumentException ignored) {}
                }
            }
            if (full.startsWith("settings.costs.item.")) {
                String rest = full.substring("settings.costs.item.".length());
                int idx = rest.indexOf('.');
                if (idx > 0) {
                    String world = rest.substring(0, idx);
                    String kindKey = rest.substring(idx + 1);
                    try {
                        TeleportKind kind = TeleportKind.valueOf(kindKey.toUpperCase());
                        int val = config.getInt(full, 0);
                        itemByWorld.computeIfAbsent(world, w -> new EnumMap<>(TeleportKind.class)).put(kind, val);
                    } catch (IllegalArgumentException ignored) {}
                }
            }
        }

        // Parse realm-friendly RTP cost overrides (overworld/nether/end)
        realmXpRtp.put("overworld", config.getInt("settings.costs.xp_levels.rtp.overworld", 0));
        realmXpRtp.put("nether", config.getInt("settings.costs.xp_levels.rtp.nether", 0));
        realmXpRtp.put("end", config.getInt("settings.costs.xp_levels.rtp.end", 0));
        realmItemRtp.put("overworld", config.getInt("settings.costs.item.rtp.overworld", 0));
        realmItemRtp.put("nether", config.getInt("settings.costs.item.rtp.nether", 0));
        realmItemRtp.put("end", config.getInt("settings.costs.item.rtp.end", 0));

        // Parse realm-friendly RTP distance overrides
        if (config.isInt("settings.rtp.realm_min_distance.overworld")) realmMin.put("overworld", config.getInt("settings.rtp.realm_min_distance.overworld"));
        if (config.isInt("settings.rtp.realm_min_distance.nether")) realmMin.put("nether", config.getInt("settings.rtp.realm_min_distance.nether"));
        if (config.isInt("settings.rtp.realm_min_distance.end")) realmMin.put("end", config.getInt("settings.rtp.realm_min_distance.end"));
        if (config.isInt("settings.rtp.realm_max_distance.overworld")) realmMax.put("overworld", config.getInt("settings.rtp.realm_max_distance.overworld"));
        if (config.isInt("settings.rtp.realm_max_distance.nether")) realmMax.put("nether", config.getInt("settings.rtp.realm_max_distance.nether"));
        if (config.isInt("settings.rtp.realm_max_distance.end")) realmMax.put("end", config.getInt("settings.rtp.realm_max_distance.end"));

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
            effects,
            xpByWorld,
            itemByWorld,
            realmXpRtp,
            realmItemRtp,
            realmMin,
            realmMax
        );
    }

    public int xpCost(TeleportKind kind, org.bukkit.World world) {
        if (world == null) return xpCost(kind);
        if (kind == dev.yatpa.paper.data.TeleportKind.RTP) {
            String env = switch (world.getEnvironment()) {
                case NORMAL -> "overworld";
                case NETHER -> "nether";
                case THE_END -> "end";
                default -> "";
            };
            Integer v = realmXpRtpCosts.get(env);
            if (v != null && v > 0) return v;
        }
        String w = world.getName();
        Map<TeleportKind, Integer> map = xpCostsByWorld.get(w);
        if (map != null && map.containsKey(kind)) return map.get(kind);
        return xpCost(kind);
    }

    public int itemCost(TeleportKind kind, org.bukkit.World world) {
        if (world == null) return itemCost(kind);
        if (kind == dev.yatpa.paper.data.TeleportKind.RTP) {
            String env = switch (world.getEnvironment()) {
                case NORMAL -> "overworld";
                case NETHER -> "nether";
                case THE_END -> "end";
                default -> "";
            };
            Integer v = realmItemRtpCosts.get(env);
            if (v != null && v > 0) return v;
        }
        String w = world.getName();
        Map<TeleportKind, Integer> map = itemCostsByWorld.get(w);
        if (map != null && map.containsKey(kind)) return map.get(kind);
        return itemCost(kind);
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
    public int rtpMin(org.bukkit.World world) {
        if (world == null) return rtpMinDistance;
        String env = switch (world.getEnvironment()) {
            case NORMAL -> "overworld";
            case NETHER -> "nether";
            case THE_END -> "end";
            default -> "";
        };
        return realmRtpMin.getOrDefault(env, rtpMinDistance);
    }

    public int rtpMax(org.bukkit.World world) {
        if (world == null) return rtpMaxDistance;
        String env = switch (world.getEnvironment()) {
            case NORMAL -> "overworld";
            case NETHER -> "nether";
            case THE_END -> "end";
            default -> "";
        };
        return realmRtpMax.getOrDefault(env, rtpMaxDistance);
    }
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
