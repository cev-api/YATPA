package dev.yatpa.paper.command;

import dev.yatpa.paper.YatpaPaperPlugin;
import dev.yatpa.paper.config.XmlMessages;
import dev.yatpa.paper.config.YatpaConfig;
import dev.yatpa.paper.data.HomeLocation;
import dev.yatpa.paper.data.RequestType;
import dev.yatpa.paper.data.TeleportKind;
import dev.yatpa.paper.data.TeleportRequest;
import dev.yatpa.paper.service.DataStore;
import dev.yatpa.paper.service.RequestService;
import dev.yatpa.paper.service.TeleportService;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.GameMode;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;

public class YatpaCommandHandler implements CommandExecutor, TabCompleter {
    private static final List<String> FEATURE_PATHS = List.of(
        "settings.features.enabled",
        "settings.features.tpa",
        "settings.features.tpahere",
        "settings.features.homes",
        "settings.features.rtp"
    );

    private final YatpaPaperPlugin plugin;
    private final XmlMessages messages;
    private final YatpaConfig config;
    private final DataStore dataStore;
    private final RequestService requests;
    private final TeleportService teleports;
    private final Map<UUID, Long> rtpCooldowns = new ConcurrentHashMap<>();

    public YatpaCommandHandler(
        YatpaPaperPlugin plugin,
        XmlMessages messages,
        YatpaConfig config,
        DataStore dataStore,
        RequestService requests,
        TeleportService teleports
    ) {
        this.plugin = plugin;
        this.messages = messages;
        this.config = config;
        this.dataStore = dataStore;
        this.requests = requests;
        this.teleports = teleports;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String name = command.getName().toLowerCase(Locale.ROOT);
        if (!name.equals("yatpa") && !config.appEnabled()) {
            send(sender, "app_disabled");
            return true;
        }
        return switch (name) {
            case "tpa" -> handleTpa(sender, args);
            case "yatpa" -> handleYatpa(sender, args);
            case "tphelp", "tpahelp" -> handleHelp(sender);
            case "tpahere" -> handleTpaHere(sender, args);
            case "tpaccept" -> handleAccept(sender);
            case "tpdeny" -> handleDeny(sender);
            case "tpatoggle" -> handleToggle(sender);
            case "tpablock" -> handleBlock(sender, args);
            case "tpaunblock" -> handleUnblock(sender, args);
            case "tphome" -> handleHome(sender, args);
            case "rtp" -> handleRtp(sender);
            case "spawn" -> handleSpawn(sender);
            case "ytp" -> handleYtp(sender, args);
            case "tpoffline" -> handleTpOffline(sender, args);
            default -> false;
        };
    }

    private boolean handleTpa(CommandSender sender, String[] args) {
        if (!config.tpaEnabled()) {
            send(sender, "feature_tpa_disabled");
            return true;
        }
        if (!(sender instanceof Player player)) {
            send(sender, "player_only");
            return true;
        }
        if (args.length != 1) {
            send(sender, "usage_tpa");
            return true;
        }
        Player target = Bukkit.getPlayerExact(args[0]);
        if (target == null) {
            send(player, "player_not_online");
            return true;
        }
        if (target.getUniqueId().equals(player.getUniqueId())) {
            send(player, "self_target");
            return true;
        }
        return createRequest(player, target, RequestType.TPA);
    }

    private boolean handleYatpa(CommandSender sender, String[] args) {
        if (args.length == 1 && args[0].equalsIgnoreCase("help")) {
            return handleHelp(sender);
        }
        if (!sender.hasPermission("yatpa.op.reload")) {
            send(sender, "usage_yatpa_help");
            return true;
        }
        if (args.length == 1 && args[0].equalsIgnoreCase("reload")) {
            plugin.reloadAll();
            send(sender, "config_reloaded");
            return true;
        }
        if (args.length == 1 && args[0].equalsIgnoreCase("settings")) {
            showSettings(sender);
            return true;
        }
        if (args.length >= 3 && args[0].equalsIgnoreCase("set")) {
            String path = args[1];
            String value = String.join(" ", java.util.Arrays.copyOfRange(args, 2, args.length));
            return setSetting(sender, path, value);
        }
        send(sender, "usage_yatpa");
        return true;
    }

    private boolean handleHelp(CommandSender sender) {
        sender.sendMessage("§6§m-----------------------");
        sender.sendMessage("§e§lYATPA Help");
        if (config.tpaEnabled()) {
            sender.sendMessage("§a/tpa <player> §7- Request teleport to a player");
        }
        if (config.tpaHereEnabled()) {
            sender.sendMessage("§a/tpahere <player> §7- Request a player teleport to you");
        }
        sender.sendMessage("§a/tpaccept §7- Accept latest request");
        sender.sendMessage("§a/tpdeny §7- Deny latest request");
        sender.sendMessage("§a/tpatoggle §7- Toggle incoming requests");
        sender.sendMessage("§a/tpablock <player> §7- Block a player from requesting");
        sender.sendMessage("§a/tpaunblock <player> §7- Unblock a player");
        if (config.homesEnabled()) {
            sender.sendMessage("§a/tphome ... §7- Home set/list/delete/default/teleport");
        }
        if (config.rtpEnabled()) {
            sender.sendMessage("§a/rtp §7- Random teleport");
        }
        sender.sendMessage("§a/spawn §7- Teleport near spawn");
        appendCostsHelp(sender);
        sender.sendMessage("§6§m-----------------------");
        return true;
    }

    private void appendCostsHelp(CommandSender sender) {
        if (!config.appEnabled()) {
            return;
        }
        FileConfiguration liveConfig = plugin.getConfig();
        boolean enabled = liveConfig.getBoolean("settings.costs.enabled", false);
        String mode = liveConfig.getString("settings.costs.mode", "NONE");
        if (!enabled || mode == null || mode.equalsIgnoreCase("NONE")) {
            return;
        }
        boolean xpMode = mode.equalsIgnoreCase("XP_LEVELS");
        boolean itemMode = mode.equalsIgnoreCase("ITEM");
        if (!xpMode && !itemMode) {
            return;
        }
        String itemMaterial = liveConfig.getString("settings.costs.item.material", "ENDER_PEARL");
        List<String> lines = new ArrayList<>();
        for (TeleportKind kind : TeleportKind.values()) {
            if (!isKindFeatureEnabled(kind)) {
                continue;
            }
            String key = kind.name().toLowerCase(Locale.ROOT);
            String cost;
            if (xpMode) {
                int amount = liveConfig.getInt("settings.costs.xp_levels." + key, 0);
                if (amount <= 0) {
                    continue;
                }
                cost = "§e" + amount + " XP level" + (amount == 1 ? "" : "s");
            } else {
                int amount = liveConfig.getInt("settings.costs.item." + key, 0);
                if (amount <= 0) {
                    continue;
                }
                cost = "§e" + amount + " " + displayMaterial(itemMaterial);
            }
            if (cost == null) {
                continue;
            }
            lines.add("§a" + commandFor(kind) + " §7- " + cost);
        }
        if (lines.isEmpty()) {
            return;
        }
        sender.sendMessage("§e§lCosts");
        for (String line : lines) {
            sender.sendMessage(line);
        }
    }

    private String commandFor(TeleportKind kind) {
        return switch (kind) {
            case TPA -> "/tpa";
            case TPAHERE -> "/tpahere";
            case HOME -> "/tphome";
            case RTP -> "/rtp";
            case SPAWN -> "/spawn";
        };
    }

    private String displayMaterial(String material) {
        String singular = Arrays.stream(material.toLowerCase(Locale.ROOT).split("_"))
            .map(part -> Character.toUpperCase(part.charAt(0)) + part.substring(1))
            .collect(Collectors.joining(" "));
        if (singular.endsWith("s")) {
            return singular;
        }
        return singular + "s";
    }

    private boolean handleTpaHere(CommandSender sender, String[] args) {
        if (!config.tpaHereEnabled()) {
            send(sender, "feature_tpahere_disabled");
            return true;
        }
        if (!(sender instanceof Player player)) {
            send(sender, "player_only");
            return true;
        }
        if (args.length != 1) {
            send(sender, "usage_tpahere");
            return true;
        }
        Player target = Bukkit.getPlayerExact(args[0]);
        if (target == null) {
            send(player, "player_not_online");
            return true;
        }
        if (target.getUniqueId().equals(player.getUniqueId())) {
            send(player, "self_target");
            return true;
        }
        return createRequest(player, target, RequestType.TPAHERE);
    }

    private boolean createRequest(Player sender, Player target, RequestType type) {
        int remaining = requests.cooldownRemaining(sender.getUniqueId());
        if (remaining > 0) {
            sender.sendMessage(messages.get("prefix") + "Please wait " + remaining + "s before sending another request.");
            return true;
        }
        if (!dataStore.acceptingRequests(target.getUniqueId())) {
            send(sender, "target_not_accepting", Map.of("target", target.getName()));
            return true;
        }
        if (dataStore.isBlocked(target.getUniqueId(), sender.getUniqueId())) {
            send(sender, "you_are_blocked");
            return true;
        }
        if (!requests.create(sender.getUniqueId(), target.getUniqueId(), type)) {
            send(sender, "request_exists");
            return true;
        }

        send(sender, "request_sent", Map.of("target", target.getName()));
        send(target, "request_received", Map.of("player", sender.getName()));

        Component accept = Component.text(messages.get("accept_button"))
            .color(NamedTextColor.GREEN)
            .clickEvent(ClickEvent.runCommand("/tpaccept"))
            .hoverEvent(HoverEvent.showText(Component.text(messages.get("accept_hover"))));
        Component deny = Component.text(messages.get("deny_button"))
            .color(NamedTextColor.RED)
            .clickEvent(ClickEvent.runCommand("/tpdeny"))
            .hoverEvent(HoverEvent.showText(Component.text(messages.get("deny_hover"))));
        target.sendMessage(accept.append(Component.text(" ")).append(deny));

        teleports.play(sender, "request_sent");
        teleports.play(target, "request_received");
        return true;
    }

    private boolean handleAccept(CommandSender sender) {
        if (!(sender instanceof Player receiver)) {
            send(sender, "player_only");
            return true;
        }

        Optional<TeleportRequest> requestOpt = requests.pendingFor(receiver.getUniqueId());
        if (requestOpt.isEmpty()) {
            send(receiver, "request_none");
            return true;
        }

        TeleportRequest request = requests.removeFor(receiver.getUniqueId()).orElse(null);
        if (request == null) {
            send(receiver, "request_none");
            return true;
        }

        Player senderPlayer = Bukkit.getPlayer(request.sender());
        if (senderPlayer == null) {
            send(receiver, "player_not_online");
            return true;
        }

        if (request.type() == RequestType.TPA) {
            if (!config.tpaEnabled()) {
                send(receiver, "feature_tpa_disabled");
                send(senderPlayer, "request_denied");
                return true;
            }
            teleports.queueTeleport(senderPlayer, TeleportKind.TPA, receiver::getLocation);
        } else {
            if (!config.tpaHereEnabled()) {
                send(receiver, "feature_tpahere_disabled");
                send(senderPlayer, "request_denied");
                return true;
            }
            teleports.queueTeleport(receiver, TeleportKind.TPAHERE, senderPlayer::getLocation);
        }

        send(receiver, "request_accepted");
        senderPlayer.sendMessage(messages.get("prefix") + receiver.getName() + " accepted your request.");
        return true;
    }

    private boolean handleDeny(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            send(sender, "player_only");
            return true;
        }
        Optional<TeleportRequest> request = requests.removeFor(player.getUniqueId());
        if (request.isEmpty()) {
            send(player, "request_none");
            return true;
        }
        Player requestSender = Bukkit.getPlayer(request.get().sender());
        if (requestSender != null) {
            send(requestSender, "request_denied");
        }
        send(player, "request_denied");
        return true;
    }

    private boolean handleToggle(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            send(sender, "player_only");
            return true;
        }
        boolean newValue = !dataStore.acceptingRequests(player.getUniqueId());
        dataStore.setAcceptingRequests(player.getUniqueId(), newValue);
        send(player, newValue ? "toggle_on" : "toggle_off");
        return true;
    }

    private boolean handleBlock(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            send(sender, "player_only");
            return true;
        }
        if (args.length != 1) {
            send(player, "usage_block");
            return true;
        }
        UUID id = Bukkit.getOfflinePlayer(args[0]).getUniqueId();
        dataStore.block(player.getUniqueId(), id);
        send(player, "blocked_target", Map.of("target", args[0]));
        return true;
    }

    private boolean handleUnblock(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            send(sender, "player_only");
            return true;
        }
        if (args.length != 1) {
            send(player, "usage_unblock");
            return true;
        }
        UUID id = Bukkit.getOfflinePlayer(args[0]).getUniqueId();
        dataStore.unblock(player.getUniqueId(), id);
        send(player, "unblocked_target", Map.of("target", args[0]));
        return true;
    }

    private boolean handleHome(CommandSender sender, String[] args) {
        if (!config.homesEnabled()) {
            send(sender, "feature_homes_disabled");
            return true;
        }
        if (!(sender instanceof Player player)) {
            send(sender, "player_only");
            return true;
        }

        if (args.length == 0) {
            String defaultHome = dataStore.defaultHome(player.getUniqueId());
            if (defaultHome == null) {
                send(player, "home_missing", Map.of("name", "default"));
                return true;
            }
            return teleportHome(player, defaultHome);
        }

        String sub = args[0].toLowerCase(Locale.ROOT);
        if (sub.equals("list")) {
            List<String> homes = dataStore.homeNames(player.getUniqueId());
            send(player, "home_list", Map.of("homes", homes.isEmpty() ? "none" : String.join(", ", homes)));
            return true;
        }
        if (sub.equals("set")) {
            if (args.length >= 3 && args[1].equalsIgnoreCase("default")) {
                dataStore.setDefaultHome(player.getUniqueId(), args[2]);
                send(player, "home_default_set", Map.of("name", args[2]));
                return true;
            }
            String name = args.length > 1 ? args[1] : "default";
            int current = dataStore.homeNames(player.getUniqueId()).size();
            int max = dataStore.homeLimit(player.getUniqueId(), config.maxHomesDefault());
            if (current >= max && dataStore.home(player.getUniqueId(), name) == null) {
                send(player, "home_limit", Map.of("limit", Integer.toString(max)));
                return true;
            }
            dataStore.setHome(player.getUniqueId(), name, player.getLocation());
            if (!dataStore.hasDefaultHome(player.getUniqueId()) && current == 0) {
                dataStore.setDefaultHome(player.getUniqueId(), name);
            }
            send(player, "home_set", Map.of("name", name));
            return true;
        }
        if (sub.equals("delete")) {
            if (args.length < 2) {
                send(player, "usage_tphome");
                return true;
            }
            if (dataStore.deleteHome(player.getUniqueId(), args[1])) {
                send(player, "home_deleted", Map.of("name", args[1]));
            } else {
                send(player, "home_missing", Map.of("name", args[1]));
            }
            return true;
        }
        if (sub.equals("default")) {
            if (args.length < 2) {
                send(player, "usage_tphome");
                return true;
            }
            dataStore.setDefaultHome(player.getUniqueId(), args[1]);
            send(player, "home_default_set", Map.of("name", args[1]));
            return true;
        }

        return teleportHome(player, args[0]);
    }

    private boolean teleportHome(Player player, String homeName) {
        HomeLocation home = dataStore.home(player.getUniqueId(), homeName.toLowerCase(Locale.ROOT));
        if (home == null) {
            send(player, "home_missing", Map.of("name", homeName));
            return true;
        }
        World world = Bukkit.getWorld(home.world());
        if (world == null) {
            send(player, "home_missing", Map.of("name", homeName));
            return true;
        }
        Location destination = new Location(world, home.x(), home.y(), home.z(), home.yaw(), home.pitch());
        teleports.queueTeleport(player, TeleportKind.HOME, () -> destination);
        return true;
    }

    private boolean handleRtp(CommandSender sender) {
        if (!config.rtpEnabled()) {
            send(sender, "feature_rtp_disabled");
            return true;
        }
        if (!(sender instanceof Player player)) {
            send(sender, "player_only");
            return true;
        }
        int remaining = rtpCooldownRemaining(player.getUniqueId());
        if (remaining > 0) {
            sendRtpCooldown(player, remaining);
            return true;
        }
        teleports.queueTeleport(
            player,
            TeleportKind.RTP,
            () -> findRandomSafe(player.getWorld(), player.getLocation(), config.rtpMinDistance(), config.rtpMaxDistance()),
            () -> rtpCooldowns.put(player.getUniqueId(), System.currentTimeMillis())
        );
        return true;
    }

    private boolean handleSpawn(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            send(sender, "player_only");
            return true;
        }
        World world = player.getWorld();
        Location spawn = world.getSpawnLocation();
        int radius = Math.max(0, config.spawnRadius());
        teleports.queueTeleport(player, TeleportKind.SPAWN, () -> findRandomSafe(world, spawn, 0, radius));
        return true;
    }

    private boolean handleYtp(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            send(sender, "player_only");
            return true;
        }
        if (!player.hasPermission("yatpa.op.tp")) {
            send(player, "no_permission");
            return true;
        }
        if (args.length == 1) {
            Player target = Bukkit.getPlayerExact(args[0]);
            if (target == null) {
                send(player, "player_not_online");
                return true;
            }
            player.teleport(resolveYtpDestination(player, target.getLocation()));
            send(player, "teleport_success");
            return true;
        }
        if (args.length != 3 && args.length != 4) {
            send(player, "usage_tp");
            return true;
        }

        Double x = parseCoordinate(args[0]);
        Double y = parseCoordinate(args[1]);
        Double z = parseCoordinate(args[2]);
        if (x == null || y == null || z == null) {
            send(player, "usage_tp");
            return true;
        }

        World world = args.length == 4 ? resolveRealm(args[3], player.getWorld()) : player.getWorld();
        if (world == null) {
            player.sendMessage(messages.get("prefix") + "§cUnknown realm/world: §e" + args[3]);
            return true;
        }

        Location destination = new Location(world, x, y, z, player.getLocation().getYaw(), player.getLocation().getPitch());
        player.teleport(resolveYtpDestination(player, destination));
        send(player, "teleport_success");
        return true;
    }

    private boolean handleTpOffline(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            send(sender, "player_only");
            return true;
        }
        if (!player.hasPermission("yatpa.op.tpoffline")) {
            send(player, "no_permission");
            return true;
        }
        if (args.length != 1) {
            send(player, "usage_tpoffline");
            return true;
        }
        Location location = dataStore.offlineLocation(args[0]);
        if (location == null) {
            send(player, "offline_missing");
            return true;
        }
        player.teleport(location);
        send(player, "teleport_success");
        return true;
    }

    private Double parseCoordinate(String value) {
        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private Location resolveYtpDestination(Player player, Location desired) {
        if (bypassSafeYtp(player) || isSafeStandLocation(desired)) {
            return desired;
        }
        Location safe = findNearestSafeLocation(desired, 48, 64);
        if (safe == null) {
            return desired;
        }
        return safe;
    }

    private boolean bypassSafeYtp(Player player) {
        return player.getGameMode() == GameMode.CREATIVE || player.getGameMode() == GameMode.SPECTATOR;
    }

    private Location findNearestSafeLocation(Location desired, int maxHorizontalRadius, int verticalRange) {
        World world = desired.getWorld();
        if (world == null) {
            return null;
        }

        int originX = desired.getBlockX();
        int originY = desired.getBlockY();
        int originZ = desired.getBlockZ();
        int minY = world.getMinHeight() + 1;
        int maxY = world.getMaxHeight() - 2;
        int startY = clamp(originY, minY, maxY);
        double bestDistanceSq = Double.MAX_VALUE;
        Location best = null;

        for (int radius = 0; radius <= maxHorizontalRadius; radius++) {
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if (Math.abs(dx) != radius && Math.abs(dz) != radius) {
                        continue;
                    }
                    int x = originX + dx;
                    int z = originZ + dz;
                    Location candidate = findSafeInColumn(world, x, z, startY, verticalRange);
                    if (candidate == null) {
                        continue;
                    }
                    double distanceSq = candidate.distanceSquared(desired);
                    if (distanceSq < bestDistanceSq) {
                        bestDistanceSq = distanceSq;
                        best = candidate;
                    }
                }
            }
            if (best != null && bestDistanceSq <= radius * radius) {
                break;
            }
        }

        if (best == null) {
            Location spawn = world.getSpawnLocation();
            best = findSafeInColumn(world, spawn.getBlockX(), spawn.getBlockZ(), spawn.getBlockY(), verticalRange);
            if (best == null) {
                best = spawn.clone();
            }
        }

        best.setYaw(desired.getYaw());
        best.setPitch(desired.getPitch());
        return best;
    }

    private Location findSafeInColumn(World world, int x, int z, int targetY, int verticalRange) {
        int minY = world.getMinHeight() + 1;
        int maxY = world.getMaxHeight() - 2;
        int startY = clamp(targetY, minY, maxY);
        Location borderCheck = new Location(world, x, startY, z);
        if (!world.getWorldBorder().isInside(borderCheck)) {
            return null;
        }

        for (int dy = 0; dy <= verticalRange; dy++) {
            int up = startY + dy;
            if (up <= maxY && isSafeStandLocation(world, x, up, z)) {
                return centeredLocation(world, x, up, z);
            }
            if (dy > 0) {
                int down = startY - dy;
                if (down >= minY && isSafeStandLocation(world, x, down, z)) {
                    return centeredLocation(world, x, down, z);
                }
            }
        }

        int surfaceY = clamp(world.getHighestBlockYAt(x, z) + 1, minY, maxY);
        if (isSafeStandLocation(world, x, surfaceY, z)) {
            return centeredLocation(world, x, surfaceY, z);
        }
        return null;
    }

    private boolean isSafeStandLocation(Location location) {
        World world = location.getWorld();
        if (world == null) {
            return false;
        }
        return isSafeStandLocation(world, location.getBlockX(), location.getBlockY(), location.getBlockZ());
    }

    private boolean isSafeStandLocation(World world, int x, int y, int z) {
        int minY = world.getMinHeight() + 1;
        int maxY = world.getMaxHeight() - 2;
        if (y < minY || y > maxY) {
            return false;
        }

        Location feet = new Location(world, x, y, z);
        Location head = feet.clone().add(0, 1, 0);
        Location below = feet.clone().subtract(0, 1, 0);

        boolean feetClear = feet.getBlock().isPassable() && !feet.getBlock().isLiquid();
        boolean headClear = head.getBlock().isPassable() && !head.getBlock().isLiquid();
        boolean belowSolid = below.getBlock().getType().isSolid() && !below.getBlock().isLiquid();
        return feetClear && headClear && belowSolid;
    }

    private Location centeredLocation(World world, int x, int y, int z) {
        return new Location(world, x + 0.5, y, z + 0.5);
    }

    private int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private World resolveRealm(String value, World currentWorld) {
        String input = value.toLowerCase(Locale.ROOT);
        World exact = Bukkit.getWorld(value);
        if (exact != null) {
            return exact;
        }
        for (World world : Bukkit.getWorlds()) {
            if (world.getName().equalsIgnoreCase(value)) {
                return world;
            }
        }
        return switch (input) {
            case "overworld", "world", "normal" -> firstWorldByEnvironment(World.Environment.NORMAL, currentWorld);
            case "nether" -> firstWorldByEnvironment(World.Environment.NETHER, currentWorld);
            case "end", "the_end" -> firstWorldByEnvironment(World.Environment.THE_END, currentWorld);
            default -> null;
        };
    }

    private World firstWorldByEnvironment(World.Environment environment, World currentWorld) {
        if (currentWorld.getEnvironment() == environment) {
            return currentWorld;
        }
        for (World world : Bukkit.getWorlds()) {
            if (world.getEnvironment() == environment) {
                return world;
            }
        }
        return null;
    }

    private Location findRandomSafe(World world, Location center, int minRadius, int maxRadius) {
        int min = Math.max(0, minRadius);
        int max = Math.max(min + 1, maxRadius);
        var border = world.getWorldBorder();

        for (int i = 0; i < 80; i++) {
            double angle = ThreadLocalRandom.current().nextDouble() * Math.PI * 2;
            int distance = min + ThreadLocalRandom.current().nextInt(max - min + 1);
            int x = (int) Math.floor(center.getX() + Math.cos(angle) * distance);
            int z = (int) Math.floor(center.getZ() + Math.sin(angle) * distance);

            if (!border.isInside(new Location(world, x, center.getY(), z))) {
                continue;
            }

            int y = world.getHighestBlockYAt(x, z) + 1;
            Location candidate = new Location(world, x + 0.5, y, z + 0.5, center.getYaw(), center.getPitch());
            if (candidate.getBlock().isPassable() && candidate.clone().subtract(0, 1, 0).getBlock().getType().isSolid()) {
                return candidate;
            }
        }
        return world.getSpawnLocation();
    }

    private void send(CommandSender sender, String key) {
        sender.sendMessage(messages.get("prefix") + messages.get(key));
    }

    private void send(CommandSender sender, String key, Map<String, String> replacements) {
        sender.sendMessage(messages.get("prefix") + messages.format(key, replacements));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        String cmd = command.getName().toLowerCase(Locale.ROOT);
        if (cmd.equals("tphome") && sender instanceof Player player) {
            if (args.length == 1) {
                List<String> base = new ArrayList<>(List.of("set", "delete", "list", "default"));
                base.addAll(dataStore.homeNames(player.getUniqueId()));
                return partial(base, args[0]);
            }
            if (args.length == 2 && (args[0].equalsIgnoreCase("delete") || args[0].equalsIgnoreCase("default"))) {
                return partial(dataStore.homeNames(player.getUniqueId()), args[1]);
            }
            if (args.length == 2 && args[0].equalsIgnoreCase("set")) {
                return partial(List.of("default"), args[1]);
            }
            return Collections.emptyList();
        }

        if (cmd.equals("tpa") && args.length == 1) {
            return partial(onlineNames(), args[0]);
        }
        if (cmd.equals("yatpa") && sender.hasPermission("yatpa.op.reload")) {
            if (args.length == 1) {
                return partial(List.of("help", "reload", "settings", "set"), args[0]);
            }
            if (args.length == 2 && args[0].equalsIgnoreCase("set")) {
                return partial(editableConfigPaths(), args[1]);
            }
            if (args.length == 3 && args[0].equalsIgnoreCase("set")) {
                Object current = plugin.getConfig().get(args[1]);
                if (current instanceof Boolean) {
                    return partial(List.of("true", "false"), args[2]);
                }
                if (current != null && args[1].equalsIgnoreCase("settings.landing.mode")) {
                    return partial(List.of("EXACT", "RANDOM_OFFSET"), args[2]);
                }
                if (current != null && args[1].equalsIgnoreCase("settings.costs.mode")) {
                    return partial(List.of("NONE", "XP_LEVELS", "ITEM"), args[2]);
                }
            }
        }

        if (cmd.equals("yatpa") && args.length == 1) {
            return partial(List.of("help"), args[0]);
        }

        if (cmd.equals("ytp")) {
            if (args.length == 1) {
                return partial(onlineNames(), args[0]);
            }
            if (args.length == 4 && areCoordinates(args[0], args[1], args[2])) {
                return partial(realmOptions(), args[3]);
            }
            return Collections.emptyList();
        }

        if (List.of("tpahere", "tpablock", "tpaunblock", "tpoffline").contains(cmd) && args.length == 1) {
            return partial(onlineNames(), args[0]);
        }
        return Collections.emptyList();
    }

    private List<String> onlineNames() {
        return Bukkit.getOnlinePlayers().stream().map(Player::getName).collect(Collectors.toList());
    }

    private List<String> realmOptions() {
        List<String> options = new ArrayList<>(List.of("overworld", "nether", "end"));
        options.addAll(Bukkit.getWorlds().stream().map(World::getName).collect(Collectors.toList()));
        return options.stream().distinct().sorted().collect(Collectors.toList());
    }

    private boolean areCoordinates(String x, String y, String z) {
        return parseCoordinate(x) != null && parseCoordinate(y) != null && parseCoordinate(z) != null;
    }

    private List<String> partial(List<String> options, String input) {
        String needle = input.toLowerCase(Locale.ROOT);
        return options.stream().filter(v -> v.toLowerCase(Locale.ROOT).startsWith(needle)).distinct().sorted().collect(Collectors.toList());
    }

    private boolean isKindFeatureEnabled(TeleportKind kind) {
        return switch (kind) {
            case TPA -> config.tpaEnabled();
            case TPAHERE -> config.tpaHereEnabled();
            case HOME -> config.homesEnabled();
            case RTP -> config.rtpEnabled();
            case SPAWN -> true;
        };
    }

    private int rtpCooldownRemaining(UUID playerId) {
        long last = rtpCooldowns.getOrDefault(playerId, 0L);
        long elapsed = (System.currentTimeMillis() - last) / 1000L;
        long remaining = config.rtpCooldownSeconds() - elapsed;
        return (int) Math.max(0, remaining);
    }

    private void sendRtpCooldown(Player player, int seconds) {
        String template = messages.get("rtp_cooldown");
        if ("rtp_cooldown".equals(template)) {
            player.sendMessage(messages.get("prefix") + "§cYou must wait " + seconds + "s before using /rtp again.");
            return;
        }
        send(player, "rtp_cooldown", Map.of("seconds", Integer.toString(seconds)));
    }

    private boolean setSetting(CommandSender sender, String path, String value) {
        if (!plugin.getConfig().contains(path) && !FEATURE_PATHS.contains(path)) {
            sender.sendMessage(messages.get("prefix") + "§cUnknown setting path: §e" + path);
            sender.sendMessage(messages.get("prefix") + "§7Try: §f/yatpa settings");
            return true;
        }
        if (plugin.getConfig().isConfigurationSection(path)) {
            sender.sendMessage(messages.get("prefix") + "§cThat path is a section, not a value: §e" + path);
            return true;
        }

        Object current = plugin.getConfig().get(path);
        if (current == null && FEATURE_PATHS.contains(path)) {
            current = Boolean.TRUE;
        }
        Object parsed = parseValue(current, value);
        if (parsed == null && current != null) {
            sender.sendMessage(messages.get("prefix") + "§cInvalid value type. Current type: §e" + current.getClass().getSimpleName());
            return true;
        }

        plugin.getConfig().set(path, parsed);
        plugin.saveConfig();
        plugin.reloadAll();

        sender.sendMessage(messages.get("prefix") + "§aUpdated §e" + path + " §ato §b" + value);
        return true;
    }

    private Object parseValue(Object current, String value) {
        if (current == null || current instanceof String) {
            return value;
        }
        if (current instanceof Boolean) {
            if (!value.equalsIgnoreCase("true") && !value.equalsIgnoreCase("false")) {
                return null;
            }
            return Boolean.parseBoolean(value);
        }
        if (current instanceof Integer) {
            try {
                return Integer.parseInt(value);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        if (current instanceof Long) {
            try {
                return Long.parseLong(value);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        if (current instanceof Double) {
            try {
                return Double.parseDouble(value);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        if (current instanceof Float) {
            try {
                return Float.parseFloat(value);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return value;
    }

    private void showSettings(CommandSender sender) {
        sender.sendMessage("§6§m-----------------------");
        sender.sendMessage("§e§lYATPA Settings");
        for (String key : editableConfigPaths()) {
            Object value = plugin.getConfig().get(key);
            sender.sendMessage("§b" + key + " §8= §a" + String.valueOf(value));
        }
        sender.sendMessage("§7Use §f/yatpa set <path> <value> §7to change any setting.");
        sender.sendMessage("§6§m-----------------------");
    }

    private List<String> editableConfigPaths() {
        return java.util.stream.Stream.concat(
                plugin.getConfig().getKeys(true).stream(),
                FEATURE_PATHS.stream()
            )
            .filter(key -> !plugin.getConfig().isConfigurationSection(key))
            .sorted()
            .distinct()
            .collect(Collectors.toList());
    }
}
