package dev.yatpa.fabric;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import javax.xml.parsers.DocumentBuilderFactory;
import net.fabricmc.api.DedicatedServerModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.border.WorldBorder;
import net.minecraft.world.level.levelgen.Heightmap;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

public class YatpaFabricMod implements DedicatedServerModInitializer {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Type STORE_TYPE = new TypeToken<Store>() {}.getType();
    private static final List<String> FEATURE_PATHS = List.of(
        "settings.features.enabled",
        "settings.features.tpa",
        "settings.features.tpahere",
        "settings.features.homes",
        "settings.features.rtp",
        "settings.features.tpaback"
    );

    private final Map<String, String> messages = new HashMap<>();
    private final Map<UUID, Request> pendingByReceiver = new HashMap<>();
    private final Map<UUID, Long> cooldownBySender = new HashMap<>();
    private final Map<UUID, PendingTeleport> pendingTeleports = new HashMap<>();
    private final Map<UUID, Long> rtpCooldownByPlayer = new HashMap<>();

    private Store store = new Store();
    private Config config = new Config();
    private Properties rawConfig = new Properties();
    private Path configDir;
    private Path storePath;
    private Path runtimeMessagesPath;
    private Path runtimeConfigPath;
    private MinecraftServer server;

    @Override
    public void onInitializeServer() {
        this.configDir = FabricLoader.getInstance().getConfigDir().resolve("yatpa");
        this.storePath = configDir.resolve("store.json");
        loadFiles();

        CommandRegistrationCallback.EVENT.register((dispatcher, access, environment) -> {
            @SuppressWarnings("unchecked")
            CommandDispatcher<CommandSourceStack> d = (CommandDispatcher<CommandSourceStack>) dispatcher;
            d.register(Commands.literal("tpa")
                .then(Commands.argument("player", EntityArgument.player()).executes(ctx -> sendRequest(ctx, RequestType.TPA))));
            d.register(Commands.literal("tpahere")
                .then(Commands.argument("player", EntityArgument.player()).executes(ctx -> sendRequest(ctx, RequestType.TPAHERE))));
            d.register(Commands.literal("tpaccept").executes(this::accept));
            d.register(Commands.literal("tpdeny").executes(this::deny));
            d.register(Commands.literal("tpatoggle").executes(this::toggle));
            d.register(Commands.literal("tpablock").then(Commands.argument("name", StringArgumentType.word()).executes(this::block)));
            d.register(Commands.literal("tpaunblock").then(Commands.argument("name", StringArgumentType.word()).executes(this::unblock)));
            d.register(Commands.literal("tphelp").executes(this::help));
            d.register(Commands.literal("tpahelp").executes(this::help));
            d.register(Commands.literal("yatpa")
                .executes(this::yatpaRoot)
                .then(Commands.literal("help").executes(this::help))
                .then(Commands.literal("reload").requires(src -> src.hasPermission(2)).executes(this::reload))
                .then(Commands.literal("settings").requires(src -> src.hasPermission(2)).executes(this::showSettings))
                .then(Commands.literal("set")
                    .requires(src -> src.hasPermission(2))
                    .then(Commands.argument("path", StringArgumentType.word())
                        .executes(this::showSetting)
                        .then(Commands.argument("value", StringArgumentType.greedyString()).executes(this::setSetting)))));
            d.register(Commands.literal("ytp")
                .requires(src -> src.hasPermission(2))
                .then(Commands.literal("help").executes(this::opYtpHelp))
                .then(Commands.argument("player", EntityArgument.player())
                    .executes(this::opYtpPlayer)
                    .then(Commands.argument("target", EntityArgument.player()).executes(this::opYtpPlayerToPlayer))
                    .then(Commands.argument("x", DoubleArgumentType.doubleArg())
                        .then(Commands.argument("y", DoubleArgumentType.doubleArg())
                            .then(Commands.argument("z", DoubleArgumentType.doubleArg())
                                .executes(this::opYtpPlayerCoordinatesCurrentRealm)
                                .then(Commands.argument("realm", StringArgumentType.word())
                                    .suggests(this::suggestRealms)
                                    .executes(this::opYtpPlayerCoordinatesRealm))))))
                .then(Commands.argument("x", DoubleArgumentType.doubleArg())
                    .then(Commands.argument("y", DoubleArgumentType.doubleArg())
                        .then(Commands.argument("z", DoubleArgumentType.doubleArg())
                            .executes(this::opYtpCoordinatesCurrentRealm)
                            .then(Commands.argument("realm", StringArgumentType.word())
                                .suggests(this::suggestRealms)
                                .executes(this::opYtpCoordinatesRealm))))));
            d.register(Commands.literal("tpoffline")
                .requires(src -> src.hasPermission(2))
                .then(Commands.argument("name", StringArgumentType.word())
                    .suggests(this::suggestOfflineNames)
                    .executes(this::tpOffline)));
            d.register(Commands.literal("tpaback").executes(this::tpaback));
            d.register(Commands.literal("rtp").executes(this::rtp));
            d.register(Commands.literal("spawn").executes(this::spawn));
            d.register(Commands.literal("setspawn").requires(src -> src.hasPermission(2)).executes(this::setSpawn));
            d.register(Commands.literal("tphome")
                .executes(this::homeDefault)
                .then(Commands.argument("name", StringArgumentType.word()).executes(this::homeNamed))
                .then(Commands.literal("list").executes(this::homeList))
                .then(Commands.literal("default").then(Commands.argument("name", StringArgumentType.word()).executes(this::homeDefaultSet)))
                .then(Commands.literal("set")
                    .executes(this::homeSetDefaultLiteral)
                    .then(Commands.argument("name", StringArgumentType.word()).executes(this::homeSetNamed))
                    .then(Commands.literal("default").then(Commands.argument("name", StringArgumentType.word()).executes(this::homeDefaultSet))))
                .then(Commands.literal("delete").then(Commands.argument("name", StringArgumentType.word()).executes(this::homeDelete))));
        });

        ServerTickEvents.START_SERVER_TICK.register(server -> this.tick((MinecraftServer) server));
        ServerPlayConnectionEvents.DISCONNECT.register((handler, minecraftServer) -> {
            ServerPlayer player = ((ServerGamePacketListenerImpl) handler).getPlayer();
            String name = player.getGameProfile().getName().toLowerCase(Locale.ROOT);
            store.offlineLocations.put(name, Position.fromPlayer(player));
            pendingTeleports.remove(player.getUUID());
            saveStore();
        });
        ServerPlayerEvents.AFTER_RESPAWN.register((oldPlayer, newPlayer, alive) -> {
            ServerPlayer oldP = (ServerPlayer) oldPlayer;
            ServerPlayer newP = (ServerPlayer) newPlayer;
            pendingTeleports.remove(newP.getUUID());
            store.deathLocations.put(newP.getUUID().toString(), Position.fromPlayer(oldP));
            saveStore();
        });
        ServerLivingEntityEvents.ALLOW_DAMAGE.register((entity, source, amount) -> {
            if (entity instanceof ServerPlayer player && config.cancelOnDamage) {
                cancelPending(player, "cancelled_damage");
            }
            return true;
        });
    }

    private void tick(MinecraftServer minecraftServer) {
        this.server = minecraftServer;
        purgeExpiredRequests();

        List<UUID> finished = new ArrayList<>();
        for (Map.Entry<UUID, PendingTeleport> entry : pendingTeleports.entrySet()) {
            ServerPlayer player = minecraftServer.getPlayerList().getPlayer(entry.getKey());
            if (player == null) {
                finished.add(entry.getKey());
                continue;
            }
            PendingTeleport pending = entry.getValue();
            if (config.cancelOnMove && hasMovedBlock(player, pending.startPos)) {
                cancelPending(player, "cancelled_move");
                finished.add(entry.getKey());
                continue;
            }
            if (pending.ticksLeft % 20 == 0) {
                String countdown = format("countdown", Map.of("seconds", Integer.toString(pending.ticksLeft / 20)));
                player.displayClientMessage(Component.literal(countdown), true);
                play(player, "countdown");
            }
            pending.ticksLeft--;
            if (pending.ticksLeft <= 0) {
                ServerPlayer payer = minecraftServer.getPlayerList().getPlayer(pending.payerId);
                if (payer == null) {
                    sendRaw(player, "Teleport cancelled because payment could not be collected.");
                    finished.add(entry.getKey());
                    continue;
                }
                ChargeResult charge = charge(payer, pending.kind);
                if (!charge.success) {
                    String template = messages.getOrDefault("cost_failed", "cost_failed");
                    if (!template.contains("%required%")) {
                        sendRaw(payer, "You require " + charge.required + " to teleport.");
                    } else {
                        send(payer, "cost_failed", Map.of("required", charge.required));
                    }
                    finished.add(entry.getKey());
                    continue;
                }
                if (charge.paid != null && !charge.paid.isBlank()) {
                    sendRaw(payer, "Paid " + charge.paid + ".");
                }
                if (teleport(player, pending.targetSupplier.get())) {
                    pending.onSuccess.run();
                    send(player, "teleport_success");
                    play(player, "success");
                }
                finished.add(entry.getKey());
            }
        }
        finished.forEach(pendingTeleports::remove);
    }

    private void purgeExpiredRequests() {
        long now = System.currentTimeMillis();
        List<ExpiredRequest> expired = new ArrayList<>();
        for (Map.Entry<UUID, Request> entry : pendingByReceiver.entrySet()) {
            if ((now - entry.getValue().createdAtMillis) / 1000L > config.requestTimeoutSeconds) {
                expired.add(new ExpiredRequest(entry.getKey(), entry.getValue()));
            }
        }
        for (ExpiredRequest entry : expired) {
            pendingByReceiver.remove(entry.receiver);
            String receiverName = playerName(entry.receiver);
            String senderName = playerName(entry.request.sender);
            if (server != null) {
                ServerPlayer sender = server.getPlayerList().getPlayer(entry.request.sender);
                if (sender != null) {
                    send(sender, "request_sender_expired", Map.of("target", receiverName));
                }
                ServerPlayer receiver = server.getPlayerList().getPlayer(entry.receiver);
                if (receiver != null) {
                    send(receiver, "request_receiver_expired", Map.of("player", senderName));
                }
            }
        }
    }

    private String playerName(UUID uuid) {
        if (server == null) {
            return uuid.toString();
        }
        ServerPlayer online = server.getPlayerList().getPlayer(uuid);
        if (online != null) {
            return online.getGameProfile().getName();
        }
        return server.getProfileCache().get(uuid).map(profile -> profile.getName()).orElse(uuid.toString());
    }

    private int yatpaRoot(CommandContext<CommandSourceStack> ctx) {
        send(ctx.getSource(), "usage_yatpa_help");
        return Command.SINGLE_SUCCESS;
    }

    private int help(CommandContext<CommandSourceStack> ctx) {
        String root = ctx.getInput().split(" ")[0].toLowerCase(Locale.ROOT);
        if (!root.equals("yatpa") && !ensureAppEnabled(ctx.getSource())) {
            return 0;
        }
        sendRaw(ctx.getSource(), "-----------------------");
        sendRaw(ctx.getSource(), "YATPA Help");
        if (config.tpaEnabled) {
            sendRaw(ctx.getSource(), "/tpa <player> - Request teleport to a player");
        }
        if (config.tpaHereEnabled) {
            sendRaw(ctx.getSource(), "/tpahere <player> - Request a player teleport to you");
        }
        sendRaw(ctx.getSource(), "/tpaccept - Accept latest request");
        sendRaw(ctx.getSource(), "/tpdeny - Deny latest request");
        sendRaw(ctx.getSource(), "/tpatoggle - Toggle incoming requests");
        sendRaw(ctx.getSource(), "/tpablock <player> - Block a player from requesting");
        sendRaw(ctx.getSource(), "/tpaunblock <player> - Unblock a player");
        if (config.homesEnabled) {
            sendRaw(ctx.getSource(), "/tphome ... - Home set/list/delete/default/teleport");
        }
        if (config.rtpEnabled) {
            sendRaw(ctx.getSource(), "/rtp - Random teleport");
        }
        sendRaw(ctx.getSource(), "/spawn - Teleport near spawn");
        if (config.tpabackEnabled) {
            sendRaw(ctx.getSource(), "/tpaback - Teleport to your last death location");
        }
        appendCostsHelp(ctx.getSource());
        sendRaw(ctx.getSource(), "-----------------------");
        return Command.SINGLE_SUCCESS;
    }

    private void appendCostsHelp(CommandSourceStack source) {
        if (!config.costsEnabled || config.costMode == CostMode.NONE) {
            return;
        }
        List<String> lines = new ArrayList<>();
        for (TeleportKind kind : TeleportKind.values()) {
            if (!isKindFeatureEnabled(kind)) {
                continue;
            }
            if (kind == TeleportKind.RTP) {
                String[] realms = new String[]{"overworld", "nether", "end"};
                String[] labels = new String[]{"Overworld", "Nether", "End"};
                for (int i = 0; i < realms.length; i++) {
                    String realm = realms[i];
                    String label = labels[i];
                    String cost;
                    if (config.costMode == CostMode.XP_LEVELS) {
                        int realmVal = propInt(rawConfig, -1, "settings.costs.xp_levels.rtp." + realm);
                        int globalVal = propInt(rawConfig, 0, "settings.costs.xp_levels.rtp");
                        int amount = realmVal >= 0 ? realmVal : globalVal;
                        if (amount <= 0) continue;
                        cost = amount + " XP level" + (amount == 1 ? "" : "s");
                    } else if (config.costMode == CostMode.ITEM) {
                        int realmVal = propInt(rawConfig, -1, "settings.costs.item.rtp." + realm);
                        int globalVal = propInt(rawConfig, 0, "settings.costs.item.rtp");
                        int amount = realmVal >= 0 ? realmVal : globalVal;
                        if (amount <= 0) continue;
                        cost = amount + " " + displayMaterial(config.costItemName);
                    } else {
                        double realmVal = propDouble(rawConfig, -1, "settings.costs.currency.rtp." + realm);
                        double globalVal = propDouble(rawConfig, 0, "settings.costs.currency.rtp");
                        double amount = realmVal >= 0 ? realmVal : globalVal;
                        if (amount <= 0) continue;
                        cost = String.format(Locale.US, "%.2f", amount);
                    }
                    lines.add(commandFor(kind) + " (" + label + ") - " + cost);
                }
            } else {
                String cost;
                if (config.costMode == CostMode.XP_LEVELS) {
                    int amount = config.xpCost(kind);
                    if (amount <= 0) {
                        continue;
                    }
                    cost = amount + " XP level" + (amount == 1 ? "" : "s");
                } else if (config.costMode == CostMode.ITEM) {
                    int amount = config.itemCost(kind);
                    if (amount <= 0) {
                        continue;
                    }
                    cost = amount + " " + displayMaterial(config.costItemName);
                } else {
                    double amount = config.currencyCost(kind);
                    if (amount <= 0) {
                        continue;
                    }
                    cost = String.format(Locale.US, "%.2f", amount);
                }
                lines.add(commandFor(kind) + " - " + cost);
            }
        }
        if (lines.isEmpty()) {
            return;
        }
        sendRaw(source, "Costs");
        for (String line : lines) {
            sendRaw(source, line);
        }
    }

    private static int propInt(Properties p, int def, String key) {
        String v = p.getProperty(key);
        if (v == null) return def;
        try {
            return Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static double propDouble(Properties p, double def, String key) {
        String v = p.getProperty(key);
        if (v == null) return def;
        try {
            return Double.parseDouble(v.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private int reload(CommandContext<CommandSourceStack> ctx) {
        loadFiles();
        send(ctx.getSource(), "config_reloaded");
        return Command.SINGLE_SUCCESS;
    }

    private int showSettings(CommandContext<CommandSourceStack> ctx) {
        sendRaw(ctx.getSource(), "-----------------------");
        sendRaw(ctx.getSource(), "YATPA Settings");
        for (String key : editableConfigPaths()) {
            sendRaw(ctx.getSource(), key + " = " + rawConfig.getProperty(key));
        }
        sendRaw(ctx.getSource(), "Use /yatpa set <path> <value> to change any setting.");
        sendRaw(ctx.getSource(), "-----------------------");
        return Command.SINGLE_SUCCESS;
    }

    private int setSetting(CommandContext<CommandSourceStack> ctx) {
        String path = StringArgumentType.getString(ctx, "path");
        String value = StringArgumentType.getString(ctx, "value");
        boolean dynamicAllowed =
            path.startsWith("settings.rtp.default_min_distance.") ||
            path.startsWith("settings.rtp.default_max_distance.") ||
            path.startsWith("settings.costs.xp_levels.rtp.") ||
            path.startsWith("settings.costs.item.rtp.") ||
            path.startsWith("settings.costs.currency.rtp.") ||
            path.startsWith("settings.rtp.blacklisted_worlds.") ||
            path.startsWith("settings.dimension_restrictions.disable_rtp.") ||
            path.startsWith("settings.dimension_restrictions.disable_teleport.");
        if (!rawConfig.containsKey(path) && !FEATURE_PATHS.contains(path) && !defaultConfigValues().containsKey(path) && !dynamicAllowed) {
            sendRaw(ctx.getSource(), "Unknown setting path: " + path);
            sendRaw(ctx.getSource(), "Try: /yatpa settings");
            return 0;
        }
        String normalized = normalizeSettingValue(path, value);
        if (normalized == null) {
            sendRaw(ctx.getSource(), "Invalid value for " + path + ". Expected " + expectedTypeName(path) + ".");
            return 0;
        }
        rawConfig.setProperty(path, normalized);
        try {
            saveConfigProperties();
            loadConfig(runtimeConfigPath);
        } catch (IOException e) {
            sendRaw(ctx.getSource(), "Failed to save config: " + e.getMessage());
            return 0;
        }
        sendRaw(ctx.getSource(), "Updated " + path + " to " + normalized);
        return Command.SINGLE_SUCCESS;
    }

    private int showSetting(CommandContext<CommandSourceStack> ctx) {
        String path = StringArgumentType.getString(ctx, "path");
        boolean dynamicAllowed =
            path.startsWith("settings.rtp.default_min_distance.") ||
            path.startsWith("settings.rtp.default_max_distance.") ||
            path.startsWith("settings.costs.xp_levels.rtp.") ||
            path.startsWith("settings.costs.item.rtp.") ||
            path.startsWith("settings.costs.currency.rtp.") ||
            path.startsWith("settings.rtp.blacklisted_worlds.") ||
            path.startsWith("settings.dimension_restrictions.disable_rtp.") ||
            path.startsWith("settings.dimension_restrictions.disable_teleport.");
        if (!rawConfig.containsKey(path) && !FEATURE_PATHS.contains(path) && !defaultConfigValues().containsKey(path) && !dynamicAllowed) {
            sendRaw(ctx.getSource(), "Unknown setting path: " + path);
            sendRaw(ctx.getSource(), "Try: /yatpa settings");
            return 0;
        }
        String current = rawConfig.getProperty(path);
        if (current == null) {
            if (isBooleanPath(path)) {
                current = "false";
            } else if (isIntegerPath(path)) {
                current = "0";
            } else if (path.contains(".costs.currency.")) {
                current = "0.0";
            } else if (isCostModePath(path)) {
                current = "NONE";
            } else if (isLandingModePath(path)) {
                current = "EXACT";
            } else if (isMaterialPath(path)) {
                current = "ENDER_PEARL";
            } else {
                current = "<unset>";
            }
        }
        sendRaw(ctx.getSource(), path + " = " + current);
        return Command.SINGLE_SUCCESS;
    }

    private String expectedTypeName(String path) {
        if (isBooleanPath(path)) {
            return "boolean (true/false)";
        }
        if (isIntegerPath(path)) {
            return "integer";
        }
        if (isDoublePath(path)) {
            return "number";
        }
        return "string";
    }

    private boolean isBooleanPath(String path) {
        return path.startsWith("settings.features.")
            || path.startsWith("settings.dimension_restrictions.disable_rtp.")
            || path.startsWith("settings.dimension_restrictions.disable_teleport.")
            || path.startsWith("settings.rtp.blacklisted_worlds.")
            || path.equals("settings.cancel_on_move")
            || path.equals("settings.cancel_on_damage")
            || path.equals("settings.costs.enabled")
            || path.equals("settings.rtp.rtp_to_overworld")
            || path.equals("settings.spawn.enabled");
    }

    private boolean isIntegerPath(String path) {
        return path.endsWith("_seconds")
            || path.endsWith("_radius")
            || path.endsWith("_distance")
            || path.endsWith("_offset")
            || path.contains(".costs.xp_levels.")
            || (path.contains(".costs.item.") && !path.equals("settings.costs.item.material"))
            || path.contains(".realm_min_distance.")
            || path.contains(".realm_max_distance.");
    }

    private boolean isDoublePath(String path) {
        return path.equals("settings.spawn.x")
            || path.equals("settings.spawn.y")
            || path.equals("settings.spawn.z")
            || path.equals("settings.spawn.yaw")
            || path.equals("settings.spawn.pitch")
            || path.contains(".costs.currency.");
    }

    private boolean isCostModePath(String path) { return path.equals("settings.costs.mode"); }
    private boolean isLandingModePath(String path) { return path.equals("settings.landing.mode"); }
    private boolean isMaterialPath(String path) { return path.equals("settings.costs.item.material"); }
    private boolean isSoundPath(String path) { return path.startsWith("sounds."); }
    private boolean isEffectPath(String path) { return path.startsWith("effects."); }

    private String normalizeSettingValue(String path, String rawValue) {
        String value = rawValue.trim();
        if (isBooleanPath(path)) {
            if (!value.equalsIgnoreCase("true") && !value.equalsIgnoreCase("false")) {
                return null;
            }
            return String.valueOf(Boolean.parseBoolean(value));
        }
        if (isIntegerPath(path)) {
            try {
                return String.valueOf(Integer.parseInt(value));
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        if (isDoublePath(path)) {
            try {
                return String.valueOf(Double.parseDouble(value));
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        if (isCostModePath(path)) {
            String upper = value.toUpperCase(Locale.ROOT);
            if (!upper.equals("NONE") && !upper.equals("XP_LEVELS") && !upper.equals("ITEM") && !upper.equals("CURRENCY")) {
                return null;
            }
            return upper;
        }
        if (isLandingModePath(path)) {
            String upper = value.toUpperCase(Locale.ROOT);
            if (!upper.equals("EXACT") && !upper.equals("RANDOM_OFFSET")) {
                return null;
            }
            return upper;
        }
        if (isMaterialPath(path)) {
            String upper = value.toUpperCase(Locale.ROOT);
            String idText = upper.toLowerCase(Locale.ROOT);
            if (!idText.contains(":")) {
                idText = "minecraft:" + idText;
            }
            try {
                ResourceLocation id = ResourceLocation.tryParse(idText);
                if (id == null || BuiltInRegistries.ITEM.getOptional(id).isEmpty()) return null;
            } catch (Exception ignored) {
                return null;
            }
            return upper;
        }
        if (isSoundPath(path)) {
            String upper = value.toUpperCase(Locale.ROOT);
            try {
                SoundEvent s = Config.parseSound(upper);
                if (s == null) return null;
            } catch (Exception ignored) {
                return null;
            }
            return upper;
        }
        if (isEffectPath(path)) {
            String upper = value.toUpperCase(Locale.ROOT);
            try {
                ParticleOptions p = Config.parseParticle(upper);
                if (p == null) return null;
            } catch (Exception ignored) {
                return null;
            }
            return upper;
        }
        return value;
    }

    private List<String> editableConfigPaths() {
        Set<String> keys = new java.util.TreeSet<>(rawConfig.stringPropertyNames());
        keys.addAll(defaultConfigValues().keySet());
        return new ArrayList<>(keys);
    }

    private int sendRequest(CommandContext<CommandSourceStack> ctx, RequestType type) {
        if (!ensureAppEnabled(ctx.getSource())) {
            return 0;
        }
        if (type == RequestType.TPA && !config.tpaEnabled) {
            send(ctx.getSource(), "feature_tpa_disabled");
            return 0;
        }
        if (type == RequestType.TPAHERE && !config.tpaHereEnabled) {
            send(ctx.getSource(), "feature_tpahere_disabled");
            return 0;
        }

        ServerPlayer sender = ctx.getSource().getPlayer();
        if (sender == null) {
            send(ctx.getSource(), "player_only");
            return 0;
        }
        ServerPlayer target;
        try {
            target = EntityArgument.getPlayer(ctx, "player");
        } catch (CommandSyntaxException e) {
            send(sender, "player_not_online");
            return 0;
        }
        if (target.getUUID().equals(sender.getUUID())) {
            send(sender, "self_target");
            return 0;
        }
        ServerLevel blocked = null;
        if (type == RequestType.TPA) {
            blocked = firstBlockedTeleportLevel(sender.serverLevel(), target.serverLevel());
        } else if (type == RequestType.TPAHERE) {
            blocked = firstBlockedTeleportLevel(target.serverLevel(), sender.serverLevel());
        }
        if (blocked != null) {
            send(sender, "teleport_disabled_dimension", Map.of("dimension", blocked.dimension().location().toString()));
            return 0;
        }

        int cooldown = cooldownRemaining(sender.getUUID());
        if (cooldown > 0) {
            sendRaw(sender, "Please wait " + cooldown + "s before sending another request.");
            return 0;
        }

        PlayerPrefs prefs = store.playerPrefs.computeIfAbsent(target.getUUID().toString(), k -> new PlayerPrefs());
        if (!prefs.acceptingRequests) {
            send(sender, "target_not_accepting", Map.of("target", target.getName().getString()));
            return 0;
        }
        if (prefs.blocked.contains(sender.getUUID().toString())) {
            send(sender, "you_are_blocked");
            return 0;
        }
        if (type == RequestType.TPA) {
            ChargeResult charge = previewCharge(sender, TeleportKind.TPA);
            if (!charge.success) {
                send(sender, "cost_failed", Map.of("required", charge.required));
                return 0;
            }
        } else {
            ChargeResult charge = previewCharge(sender, TeleportKind.TPAHERE);
            if (!charge.success) {
                send(sender, "cost_failed", Map.of("required", charge.required));
                return 0;
            }
        }
        Request existing = pendingByReceiver.get(target.getUUID());
        if (existing != null && existing.sender.equals(sender.getUUID())) {
            send(sender, "request_exists");
            return 0;
        }

        pendingByReceiver.put(target.getUUID(), new Request(sender.getUUID(), type, System.currentTimeMillis()));
        cooldownBySender.put(sender.getUUID(), System.currentTimeMillis());

        send(sender, "request_sent", Map.of("target", target.getName().getString()));
        send(target, "request_received", Map.of("player", sender.getName().getString()));
        sendActionButtons(target);
        play(sender, "request_sent");
        play(target, "request_received");
        return Command.SINGLE_SUCCESS;
    }

    private void sendActionButtons(ServerPlayer target) {
        String acceptLabel = messages.getOrDefault("accept_button", "[Accept]");
        String denyLabel = messages.getOrDefault("deny_button", "[Deny]");
        String acceptHover = messages.getOrDefault("accept_hover", "Click to accept this request");
        String denyHover = messages.getOrDefault("deny_hover", "Click to deny this request");

        MutableComponent accept = Component.literal(acceptLabel)
            .withStyle(style -> style
                .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/tpaccept"))
                .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.literal(acceptHover))));

        MutableComponent deny = Component.literal(" " + denyLabel)
            .withStyle(style -> style
                .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/tpdeny"))
                .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.literal(denyHover))));
        target.sendSystemMessage(accept.append(deny));
    }

    private int accept(CommandContext<CommandSourceStack> ctx) {
        if (!ensureAppEnabled(ctx.getSource())) {
            return 0;
        }
        ServerPlayer receiver = ctx.getSource().getPlayer();
        if (receiver == null) {
            send(ctx.getSource(), "player_only");
            return 0;
        }

        Request request = pendingByReceiver.get(receiver.getUUID());
        if (request == null) {
            send(receiver, "request_none");
            return 0;
        }

        MinecraftServer minecraftServer = Objects.requireNonNull(receiver.getServer());
        ServerPlayer sender = minecraftServer.getPlayerList().getPlayer(request.sender);
        if (sender == null) {
            send(receiver, "player_not_online");
            return 0;
        }

        if (request.type == RequestType.TPA) {
            if (!config.tpaEnabled) {
                send(receiver, "feature_tpa_disabled");
                send(sender, "request_denied");
                return 0;
            }
            ServerLevel blocked = firstBlockedTeleportLevel(sender.serverLevel(), receiver.serverLevel());
            if (blocked != null) {
                send(receiver, "teleport_disabled_dimension", Map.of("dimension", blocked.dimension().location().toString()));
                send(sender, "teleport_disabled_dimension", Map.of("dimension", blocked.dimension().location().toString()));
                return 0;
            }
            ChargeResult charge = previewCharge(sender, TeleportKind.TPA);
            if (!charge.success) {
                send(sender, "cost_failed", Map.of("required", charge.required));
                return 0;
            }
            pendingByReceiver.remove(receiver.getUUID());
            queueDelayedTeleport(
                sender,
                sender,
                TeleportKind.TPA,
                () -> receiver.serverLevel(),
                () -> receiver.position().x,
                () -> receiver.position().y,
                () -> receiver.position().z,
                sender.getYRot(),
                sender.getXRot(),
                () -> {},
                receiver
            );
        } else {
            if (!config.tpaHereEnabled) {
                send(receiver, "feature_tpahere_disabled");
                send(sender, "request_denied");
                return 0;
            }
            ServerLevel blocked = firstBlockedTeleportLevel(receiver.serverLevel(), sender.serverLevel());
            if (blocked != null) {
                send(receiver, "teleport_disabled_dimension", Map.of("dimension", blocked.dimension().location().toString()));
                send(sender, "teleport_disabled_dimension", Map.of("dimension", blocked.dimension().location().toString()));
                return 0;
            }
            ChargeResult charge = previewCharge(sender, TeleportKind.TPAHERE);
            if (!charge.success) {
                send(sender, "cost_failed", Map.of("required", charge.required));
                return 0;
            }
            pendingByReceiver.remove(receiver.getUUID());
            queueDelayedTeleport(
                receiver,
                sender,
                TeleportKind.TPAHERE,
                () -> sender.serverLevel(),
                () -> sender.position().x,
                () -> sender.position().y,
                () -> sender.position().z,
                receiver.getYRot(),
                receiver.getXRot(),
                () -> {},
                sender
            );
        }

        send(receiver, "request_accepted");
        sender.sendSystemMessage(Component.literal(messages.getOrDefault("prefix", "") + receiver.getName().getString() + " accepted your request."));
        return Command.SINGLE_SUCCESS;
    }

    private int deny(CommandContext<CommandSourceStack> ctx) {
        if (!ensureAppEnabled(ctx.getSource())) {
            return 0;
        }
        ServerPlayer receiver = ctx.getSource().getPlayer();
        if (receiver == null) {
            send(ctx.getSource(), "player_only");
            return 0;
        }
        Request request = pendingByReceiver.remove(receiver.getUUID());
        if (request == null) {
            send(receiver, "request_none");
            return 0;
        }
        MinecraftServer minecraftServer = Objects.requireNonNull(receiver.getServer());
        ServerPlayer sender = minecraftServer.getPlayerList().getPlayer(request.sender);
        if (sender != null) {
            send(sender, "request_denied");
        }
        send(receiver, "request_denied");
        return Command.SINGLE_SUCCESS;
    }

    private int toggle(CommandContext<CommandSourceStack> ctx) {
        if (!ensureAppEnabled(ctx.getSource())) {
            return 0;
        }
        ServerPlayer player = ctx.getSource().getPlayer();
        if (player == null) {
            send(ctx.getSource(), "player_only");
            return 0;
        }
        PlayerPrefs prefs = store.playerPrefs.computeIfAbsent(player.getUUID().toString(), k -> new PlayerPrefs());
        prefs.acceptingRequests = !prefs.acceptingRequests;
        saveStore();
        send(player, prefs.acceptingRequests ? "toggle_on" : "toggle_off");
        return Command.SINGLE_SUCCESS;
    }

    private int block(CommandContext<CommandSourceStack> ctx) {
        if (!ensureAppEnabled(ctx.getSource())) {
            return 0;
        }
        ServerPlayer player = ctx.getSource().getPlayer();
        if (player == null) {
            send(ctx.getSource(), "player_only");
            return 0;
        }
        String name = StringArgumentType.getString(ctx, "name").toLowerCase(Locale.ROOT);
        UUID target = UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(StandardCharsets.UTF_8));
        PlayerPrefs prefs = store.playerPrefs.computeIfAbsent(player.getUUID().toString(), k -> new PlayerPrefs());
        prefs.blocked.add(target.toString());
        saveStore();
        send(player, "blocked_target", Map.of("target", name));
        return Command.SINGLE_SUCCESS;
    }

    private int unblock(CommandContext<CommandSourceStack> ctx) {
        if (!ensureAppEnabled(ctx.getSource())) {
            return 0;
        }
        ServerPlayer player = ctx.getSource().getPlayer();
        if (player == null) {
            send(ctx.getSource(), "player_only");
            return 0;
        }
        String name = StringArgumentType.getString(ctx, "name").toLowerCase(Locale.ROOT);
        UUID target = UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(StandardCharsets.UTF_8));
        PlayerPrefs prefs = store.playerPrefs.computeIfAbsent(player.getUUID().toString(), k -> new PlayerPrefs());
        prefs.blocked.remove(target.toString());
        saveStore();
        send(player, "unblocked_target", Map.of("target", name));
        return Command.SINGLE_SUCCESS;
    }

    private int opYtpPlayer(CommandContext<CommandSourceStack> ctx) {
        if (!ensureAppEnabled(ctx.getSource())) {
            return 0;
        }
        ServerPlayer source = ctx.getSource().getPlayer();
        if (source == null) {
            send(ctx.getSource(), "player_only");
            return 0;
        }
        ServerPlayer target;
        try {
            target = EntityArgument.getPlayer(ctx, "player");
        } catch (CommandSyntaxException e) {
            send(source, "player_not_online");
            return 0;
        }
        TeleportTarget destination = resolveYtpTarget(source, target.serverLevel(), target.getX(), target.getY(), target.getZ(), source.getYRot(), source.getXRot());
        if (teleport(source, destination)) {
            send(source, "teleport_success");
        }
        return Command.SINGLE_SUCCESS;
    }

    private int opYtpHelp(CommandContext<CommandSourceStack> ctx) {
        sendRaw(ctx.getSource(), "-----------------------");
        sendRaw(ctx.getSource(), "YTP Help");
        sendRaw(ctx.getSource(), "/ytp <player> - Teleport yourself to a player");
        sendRaw(ctx.getSource(), "/ytp <player> <target> - Teleport player to another player");
        sendRaw(ctx.getSource(), "/ytp <x> <y> <z> [realm] - Teleport yourself to coordinates");
        sendRaw(ctx.getSource(), "/ytp <player> <x> <y> <z> [realm] - Teleport player to coordinates");
        sendRaw(ctx.getSource(), "Realm can be a world/dimension id, or: overworld/nether/end");
        sendRaw(ctx.getSource(), "-----------------------");
        return Command.SINGLE_SUCCESS;
    }

    private int opYtpPlayerToPlayer(CommandContext<CommandSourceStack> ctx) {
        if (!ensureAppEnabled(ctx.getSource())) {
            return 0;
        }
        ServerPlayer source = ctx.getSource().getPlayer();
        if (source == null) {
            send(ctx.getSource(), "player_only");
            return 0;
        }
        ServerPlayer actor;
        ServerPlayer target;
        try {
            actor = EntityArgument.getPlayer(ctx, "player");
            target = EntityArgument.getPlayer(ctx, "target");
        } catch (CommandSyntaxException e) {
            send(source, "player_not_online");
            return 0;
        }
        TeleportTarget destination = resolveYtpTarget(actor, target.serverLevel(), target.getX(), target.getY(), target.getZ(), actor.getYRot(), actor.getXRot());
        if (teleport(actor, destination)) {
            send(source, "teleport_success");
            if (!source.getUUID().equals(actor.getUUID())) {
                send(actor, "teleport_success");
            }
        }
        return Command.SINGLE_SUCCESS;
    }

    private int opYtpCoordinatesCurrentRealm(CommandContext<CommandSourceStack> ctx) {
        if (!ensureAppEnabled(ctx.getSource())) {
            return 0;
        }
        ServerPlayer player = ctx.getSource().getPlayer();
        if (player == null) {
            send(ctx.getSource(), "player_only");
            return 0;
        }
        return teleportToCoordinates(
            player,
            DoubleArgumentType.getDouble(ctx, "x"),
            DoubleArgumentType.getDouble(ctx, "y"),
            DoubleArgumentType.getDouble(ctx, "z"),
            player.serverLevel()
        );
    }

    private int opYtpCoordinatesRealm(CommandContext<CommandSourceStack> ctx) {
        if (!ensureAppEnabled(ctx.getSource())) {
            return 0;
        }
        ServerPlayer player = ctx.getSource().getPlayer();
        if (player == null) {
            send(ctx.getSource(), "player_only");
            return 0;
        }
        double x = DoubleArgumentType.getDouble(ctx, "x");
        double y = DoubleArgumentType.getDouble(ctx, "y");
        double z = DoubleArgumentType.getDouble(ctx, "z");
        String realm = StringArgumentType.getString(ctx, "realm");
        ServerLevel level = resolveRealm(player, realm);
        if (level == null) {
            sendRaw(player, "Unknown realm/world: " + realm);
            return 0;
        }
        return teleportToCoordinates(player, x, y, z, level);
    }

    private int opYtpPlayerCoordinatesCurrentRealm(CommandContext<CommandSourceStack> ctx) {
        if (!ensureAppEnabled(ctx.getSource())) {
            return 0;
        }
        ServerPlayer source = ctx.getSource().getPlayer();
        if (source == null) {
            send(ctx.getSource(), "player_only");
            return 0;
        }
        ServerPlayer actor;
        try {
            actor = EntityArgument.getPlayer(ctx, "player");
        } catch (CommandSyntaxException e) {
            send(source, "player_not_online");
            return 0;
        }
        return teleportPlayerToCoordinates(source, actor, DoubleArgumentType.getDouble(ctx, "x"), DoubleArgumentType.getDouble(ctx, "y"), DoubleArgumentType.getDouble(ctx, "z"), actor.serverLevel());
    }

    private int opYtpPlayerCoordinatesRealm(CommandContext<CommandSourceStack> ctx) {
        if (!ensureAppEnabled(ctx.getSource())) {
            return 0;
        }
        ServerPlayer source = ctx.getSource().getPlayer();
        if (source == null) {
            send(ctx.getSource(), "player_only");
            return 0;
        }
        ServerPlayer actor;
        try {
            actor = EntityArgument.getPlayer(ctx, "player");
        } catch (CommandSyntaxException e) {
            send(source, "player_not_online");
            return 0;
        }
        double x = DoubleArgumentType.getDouble(ctx, "x");
        double y = DoubleArgumentType.getDouble(ctx, "y");
        double z = DoubleArgumentType.getDouble(ctx, "z");
        String realm = StringArgumentType.getString(ctx, "realm");
        ServerLevel level = resolveRealm(actor, realm);
        if (level == null) {
            sendRaw(source, "Unknown realm/world: " + realm);
            return 0;
        }
        return teleportPlayerToCoordinates(source, actor, x, y, z, level);
    }

    private int teleportToCoordinates(ServerPlayer player, double x, double y, double z, ServerLevel level) {
        TeleportTarget destination = resolveYtpTarget(player, level, x, y, z, player.getYRot(), player.getXRot());
        if (teleport(player, destination)) {
            send(player, "teleport_success");
        }
        return Command.SINGLE_SUCCESS;
    }

    private int teleportPlayerToCoordinates(ServerPlayer source, ServerPlayer actor, double x, double y, double z, ServerLevel level) {
        TeleportTarget destination = resolveYtpTarget(actor, level, x, y, z, actor.getYRot(), actor.getXRot());
        if (teleport(actor, destination)) {
            send(source, "teleport_success");
            if (!source.getUUID().equals(actor.getUUID())) {
                send(actor, "teleport_success");
            }
        }
        return Command.SINGLE_SUCCESS;
    }

    private CompletableFuture<Suggestions> suggestRealms(CommandContext<CommandSourceStack> ctx, SuggestionsBuilder builder) {
        return SharedSuggestionProvider.suggest(realmOptions(ctx.getSource().getServer()), builder);
    }

    private CompletableFuture<Suggestions> suggestOfflineNames(CommandContext<CommandSourceStack> ctx, SuggestionsBuilder builder) {
        return SharedSuggestionProvider.suggest(store.offlineLocations.keySet().stream().sorted().toList(), builder);
    }

    private List<String> realmOptions(MinecraftServer minecraftServer) {
        List<String> options = new ArrayList<>(List.of("overworld", "nether", "end"));
        for (ServerLevel level : minecraftServer.getAllLevels()) {
            ResourceLocation id = level.dimension().location();
            options.add(id.getPath());
            options.add(id.toString());
        }
        return options.stream().map(v -> v.toLowerCase(Locale.ROOT)).distinct().sorted().toList();
    }

    private ServerLevel resolveRealm(ServerPlayer player, String input) {
        MinecraftServer minecraftServer = Objects.requireNonNull(player.getServer());
        String value = input.toLowerCase(Locale.ROOT);
        return switch (value) {
            case "overworld", "world", "normal" -> minecraftServer.getLevel(Level.OVERWORLD);
            case "nether", "the_nether" -> minecraftServer.getLevel(Level.NETHER);
            case "end", "the_end" -> minecraftServer.getLevel(Level.END);
            default -> resolveRealmById(minecraftServer, value);
        };
    }

    private ServerLevel resolveRealmById(MinecraftServer minecraftServer, String input) {
        try {
            ServerLevel direct = minecraftServer.getLevel(ResourceKey.create(Registries.DIMENSION, ResourceLocation.parse(input)));
            if (direct != null) {
                return direct;
            }
        } catch (RuntimeException ignored) {
        }
        for (ServerLevel level : minecraftServer.getAllLevels()) {
            ResourceLocation id = level.dimension().location();
            if (id.getPath().equalsIgnoreCase(input) || id.toString().equalsIgnoreCase(input)) {
                return level;
            }
        }
        return null;
    }

    private int tpOffline(CommandContext<CommandSourceStack> ctx) {
        if (!ensureAppEnabled(ctx.getSource())) {
            return 0;
        }
        ServerPlayer player = ctx.getSource().getPlayer();
        if (player == null) {
            send(ctx.getSource(), "player_only");
            return 0;
        }
        String name = StringArgumentType.getString(ctx, "name").toLowerCase(Locale.ROOT);
        Position pos = store.offlineLocations.get(name);
        if (pos == null) {
            send(player, "offline_missing");
            return 0;
        }
        MinecraftServer minecraftServer = Objects.requireNonNull(player.getServer());
        ServerLevel level = minecraftServer.getLevel(ResourceKey.create(Registries.DIMENSION, ResourceLocation.parse(pos.dimension)));
        if (level == null) {
            level = minecraftServer.overworld();
        }
        if (teleport(player, level, pos.x, pos.y, pos.z, pos.yaw, pos.pitch)) {
            send(player, "teleport_success");
        }
        return Command.SINGLE_SUCCESS;
    }

    private int tpaback(CommandContext<CommandSourceStack> ctx) {
        if (!ensureAppEnabled(ctx.getSource())) {
            return 0;
        }
        if (!config.tpabackEnabled) {
            send(ctx.getSource(), "feature_tpaback_disabled");
            return 0;
        }
        ServerPlayer player = ctx.getSource().getPlayer();
        if (player == null) {
            send(ctx.getSource(), "player_only");
            return 0;
        }
        Position pos = store.deathLocations.get(player.getUUID().toString());
        if (pos == null) {
            send(player, "death_missing");
            return 0;
        }
        MinecraftServer minecraftServer = Objects.requireNonNull(player.getServer());
        ServerLevel level = minecraftServer.getLevel(ResourceKey.create(Registries.DIMENSION, ResourceLocation.parse(pos.dimension)));
        if (level == null) {
            level = minecraftServer.overworld();
        }
        final ServerLevel finalLevel = level;
        queueDelayedTeleport(player, TeleportKind.BACK, () -> finalLevel, () -> pos.x, () -> pos.y, () -> pos.z, pos.yaw, pos.pitch);
        return Command.SINGLE_SUCCESS;
    }

    private int rtp(CommandContext<CommandSourceStack> ctx) {
        if (!ensureAppEnabled(ctx.getSource())) {
            return 0;
        }
        if (!config.rtpEnabled) {
            send(ctx.getSource(), "feature_rtp_disabled");
            return 0;
        }
        ServerPlayer player = ctx.getSource().getPlayer();
        if (player == null) {
            send(ctx.getSource(), "player_only");
            return 0;
        }
        if (config.teleportDisabledIn(player.serverLevel())) {
            send(player, "teleport_disabled_dimension", Map.of("dimension", player.serverLevel().dimension().location().toString()));
            return 0;
        }
        if (config.rtpDisabledIn(player.serverLevel())) {
            send(player, "rtp_disabled_dimension", Map.of("dimension", player.serverLevel().dimension().location().toString()));
            return 0;
        }
        ServerLevel targetLevel = player.serverLevel();
        if (config.rtpToOverworld) {
            targetLevel = resolveSpawnWorld(Objects.requireNonNull(player.getServer()), config.overworldName);
            if (targetLevel == null) {
                sendRaw(player, "World not found, cannot perform RTP to world: " + config.overworldName);
                return 0;
            }
        }
        int remaining = rtpCooldownRemaining(player.getUUID());
        if (remaining > 0) {
            sendRtpCooldown(player, remaining);
            return 0;
        }
        int realmMin = config.rtpMin(targetLevel);
        int realmMax = config.rtpMax(targetLevel);
        Position random = randomSafe(player, targetLevel, player.getX(), player.getZ(), realmMin, realmMax);
        final ServerLevel finalTargetLevel = targetLevel;
        queueDelayedTeleport(
            player,
            player,
            TeleportKind.RTP,
            () -> finalTargetLevel,
            () -> random.x,
            () -> random.y,
            () -> random.z,
            player.getYRot(),
            player.getXRot(),
            () -> rtpCooldownByPlayer.put(player.getUUID(), System.currentTimeMillis())
        );
        return Command.SINGLE_SUCCESS;
    }

    private int spawn(CommandContext<CommandSourceStack> ctx) {
        if (!ensureAppEnabled(ctx.getSource())) {
            return 0;
        }
        if (!config.spawnEnabled) {
            return Command.SINGLE_SUCCESS;
        }
        ServerPlayer player = ctx.getSource().getPlayer();
        if (player == null) {
            send(ctx.getSource(), "player_only");
            return 0;
        }
        if (config.teleportDisabledIn(player.serverLevel())) {
            send(player, "teleport_disabled_dimension", Map.of("dimension", player.serverLevel().dimension().location().toString()));
            return 0;
        }
        TeleportTarget spawn = configuredSpawnTarget(player, player.serverLevel());
        queueDelayedTeleport(player, TeleportKind.SPAWN, () -> spawn.level, () -> spawn.x, () -> spawn.y, () -> spawn.z, spawn.yaw, spawn.pitch);
        return Command.SINGLE_SUCCESS;
    }

    private int setSpawn(CommandContext<CommandSourceStack> ctx) {
        if (!ensureAppEnabled(ctx.getSource())) {
            return 0;
        }
        if (!config.spawnEnabled) {
            return Command.SINGLE_SUCCESS;
        }
        ServerPlayer player = ctx.getSource().getPlayer();
        if (player == null) {
            send(ctx.getSource(), "player_only");
            return 0;
        }
        rawConfig.setProperty("settings.spawn.world", player.serverLevel().dimension().location().toString());
        rawConfig.setProperty("settings.spawn.x", Double.toString(player.getX()));
        rawConfig.setProperty("settings.spawn.y", Double.toString(player.getY()));
        rawConfig.setProperty("settings.spawn.z", Double.toString(player.getZ()));
        rawConfig.setProperty("settings.spawn.yaw", Float.toString(player.getYRot()));
        rawConfig.setProperty("settings.spawn.pitch", Float.toString(player.getXRot()));
        try {
            saveConfigProperties();
            loadConfig(runtimeConfigPath);
        } catch (IOException e) {
            sendRaw(ctx.getSource(), "Failed to save config: " + e.getMessage());
            return 0;
        }
        send(player, "spawn_set");
        return Command.SINGLE_SUCCESS;
    }

    private int homeSetDefaultLiteral(CommandContext<CommandSourceStack> ctx) {
        if (!ensureAppEnabled(ctx.getSource())) {
            return 0;
        }
        if (!config.homesEnabled) {
            send(ctx.getSource(), "feature_homes_disabled");
            return 0;
        }
        ServerPlayer player = ctx.getSource().getPlayer();
        if (player == null) {
            send(ctx.getSource(), "player_only");
            return 0;
        }
        return setHome(player, "default");
    }

    private int homeSetNamed(CommandContext<CommandSourceStack> ctx) {
        if (!ensureAppEnabled(ctx.getSource())) {
            return 0;
        }
        if (!config.homesEnabled) {
            send(ctx.getSource(), "feature_homes_disabled");
            return 0;
        }
        ServerPlayer player = ctx.getSource().getPlayer();
        if (player == null) {
            send(ctx.getSource(), "player_only");
            return 0;
        }
        return setHome(player, StringArgumentType.getString(ctx, "name"));
    }

    private int setHome(ServerPlayer player, String name) {
        String key = player.getUUID().toString();
        Map<String, Position> homes = store.homes.computeIfAbsent(key, k -> new HashMap<>());
        int limit = store.homeLimits.getOrDefault(key, config.maxHomesDefault);
        String id = name.toLowerCase(Locale.ROOT);
        if (!homes.containsKey(id) && homes.size() >= limit) {
            send(player, "home_limit", Map.of("limit", Integer.toString(limit)));
            return 0;
        }
        homes.put(id, Position.fromPlayer(player));
        if (!store.defaultHomes.containsKey(key) && homes.size() == 1) {
            store.defaultHomes.put(key, id);
        }
        saveStore();
        send(player, "home_set", Map.of("name", id));
        return Command.SINGLE_SUCCESS;
    }

    private int homeDelete(CommandContext<CommandSourceStack> ctx) {
        if (!ensureAppEnabled(ctx.getSource())) {
            return 0;
        }
        if (!config.homesEnabled) {
            send(ctx.getSource(), "feature_homes_disabled");
            return 0;
        }
        ServerPlayer player = ctx.getSource().getPlayer();
        if (player == null) {
            send(ctx.getSource(), "player_only");
            return 0;
        }
        String id = StringArgumentType.getString(ctx, "name").toLowerCase(Locale.ROOT);
        Map<String, Position> homes = store.homes.computeIfAbsent(player.getUUID().toString(), k -> new HashMap<>());
        if (homes.remove(id) == null) {
            send(player, "home_missing", Map.of("name", id));
            return 0;
        }
        saveStore();
        send(player, "home_deleted", Map.of("name", id));
        return Command.SINGLE_SUCCESS;
    }

    private int homeList(CommandContext<CommandSourceStack> ctx) {
        if (!ensureAppEnabled(ctx.getSource())) {
            return 0;
        }
        if (!config.homesEnabled) {
            send(ctx.getSource(), "feature_homes_disabled");
            return 0;
        }
        ServerPlayer player = ctx.getSource().getPlayer();
        if (player == null) {
            send(ctx.getSource(), "player_only");
            return 0;
        }
        Map<String, Position> homes = store.homes.computeIfAbsent(player.getUUID().toString(), k -> new HashMap<>());
        send(player, "home_list", Map.of("homes", homes.isEmpty() ? "none" : String.join(", ", homes.keySet())));
        return Command.SINGLE_SUCCESS;
    }

    private int homeDefaultSet(CommandContext<CommandSourceStack> ctx) {
        if (!ensureAppEnabled(ctx.getSource())) {
            return 0;
        }
        if (!config.homesEnabled) {
            send(ctx.getSource(), "feature_homes_disabled");
            return 0;
        }
        ServerPlayer player = ctx.getSource().getPlayer();
        if (player == null) {
            send(ctx.getSource(), "player_only");
            return 0;
        }
        String name = StringArgumentType.getString(ctx, "name").toLowerCase(Locale.ROOT);
        store.defaultHomes.put(player.getUUID().toString(), name);
        saveStore();
        send(player, "home_default_set", Map.of("name", name));
        return Command.SINGLE_SUCCESS;
    }

    private int homeDefault(CommandContext<CommandSourceStack> ctx) {
        if (!ensureAppEnabled(ctx.getSource())) {
            return 0;
        }
        if (!config.homesEnabled) {
            send(ctx.getSource(), "feature_homes_disabled");
            return 0;
        }
        ServerPlayer player = ctx.getSource().getPlayer();
        if (player == null) {
            send(ctx.getSource(), "player_only");
            return 0;
        }
        String defaultHome = store.defaultHomes.getOrDefault(player.getUUID().toString(), "default");
        return teleportHome(player, defaultHome);
    }

    private int homeNamed(CommandContext<CommandSourceStack> ctx) {
        if (!ensureAppEnabled(ctx.getSource())) {
            return 0;
        }
        if (!config.homesEnabled) {
            send(ctx.getSource(), "feature_homes_disabled");
            return 0;
        }
        ServerPlayer player = ctx.getSource().getPlayer();
        if (player == null) {
            send(ctx.getSource(), "player_only");
            return 0;
        }
        return teleportHome(player, StringArgumentType.getString(ctx, "name"));
    }

    private int teleportHome(ServerPlayer player, String name) {
        String id = name.toLowerCase(Locale.ROOT);
        Map<String, Position> homes = store.homes.computeIfAbsent(player.getUUID().toString(), k -> new HashMap<>());
        Position home = homes.get(id);
        if (home == null) {
            send(player, "home_missing", Map.of("name", id));
            return 0;
        }
        MinecraftServer minecraftServer = Objects.requireNonNull(player.getServer());
        ServerLevel level = minecraftServer.getLevel(ResourceKey.create(Registries.DIMENSION, ResourceLocation.parse(home.dimension)));
        if (level == null) {
            level = player.serverLevel();
        }
        ServerLevel blocked = firstBlockedTeleportLevel(player.serverLevel(), level);
        if (blocked != null) {
            send(player, "teleport_disabled_dimension", Map.of("dimension", blocked.dimension().location().toString()));
            return 0;
        }
        Position finalHome = home;
        ServerLevel finalLevel = level;
        queueDelayedTeleport(player, TeleportKind.HOME, () -> finalLevel, () -> finalHome.x, () -> finalHome.y, () -> finalHome.z, finalHome.yaw, finalHome.pitch);
        return Command.SINGLE_SUCCESS;
    }

    private void queueDelayedTeleport(ServerPlayer player, TeleportKind kind, LevelSupplier level, DoubleSupplier x, DoubleSupplier y, DoubleSupplier z, float yaw, float pitch) {
        queueDelayedTeleport(player, player, kind, level, x, y, z, yaw, pitch, () -> {}, null);
    }

    private void queueDelayedTeleport(
        ServerPlayer player,
        ServerPlayer payer,
        TeleportKind kind,
        LevelSupplier level,
        DoubleSupplier x,
        DoubleSupplier y,
        DoubleSupplier z,
        float yaw,
        float pitch,
        Runnable onSuccess,
        ServerPlayer notifyPlayer
    ) {
        if (config.teleportDisabledIn(player.serverLevel())) {
            send(player, "teleport_disabled_dimension", Map.of("dimension", player.serverLevel().dimension().location().toString()));
            return;
        }
        ChargeResult preview = previewCharge(payer, kind);
        if (!preview.success) {
            String template = messages.getOrDefault("cost_failed", "cost_failed");
            if (!template.contains("%required%")) {
                sendRaw(payer, "You require " + preview.required + " to teleport.");
            } else {
                send(payer, "cost_failed", Map.of("required", preview.required));
            }
            return;
        }
        pendingTeleports.remove(player.getUUID());
        TargetSupplier supplier = () -> new TeleportTarget(level.get(), x.get(), y.get(), z.get(), yaw, pitch);
        int delayTicks = Math.max(0, config.teleportDelaySeconds * 20);
        if (delayTicks == 0) {
            ChargeResult charge = charge(payer, kind);
            if (!charge.success) {
                String template = messages.getOrDefault("cost_failed", "cost_failed");
                if (!template.contains("%required%")) {
                    sendRaw(payer, "You require " + charge.required + " to teleport.");
                } else {
                    send(payer, "cost_failed", Map.of("required", charge.required));
                }
                return;
            }
            if (charge.paid != null && !charge.paid.isBlank()) {
                sendRaw(payer, "Paid " + charge.paid + ".");
            }
            if (teleport(player, supplier.get())) {
                onSuccess.run();
                send(player, "teleport_success");
                play(player, "success");
            }
            return;
        }

        pendingTeleports.put(
            player.getUUID(),
            new PendingTeleport(
                BlockPos.containing(player.getX(), player.getY(), player.getZ()),
                delayTicks,
                supplier,
                onSuccess,
                payer.getUUID(),
                kind,
                notifyPlayer == null ? null : notifyPlayer.getUUID()
            )
        );
    }

    private void queueDelayedTeleport(
        ServerPlayer player,
        ServerPlayer payer,
        TeleportKind kind,
        LevelSupplier level,
        DoubleSupplier x,
        DoubleSupplier y,
        DoubleSupplier z,
        float yaw,
        float pitch
    ) {
        queueDelayedTeleport(player, payer, kind, level, x, y, z, yaw, pitch, () -> {}, null);
    }

    private void queueDelayedTeleport(
        ServerPlayer player,
        ServerPlayer payer,
        TeleportKind kind,
        LevelSupplier level,
        DoubleSupplier x,
        DoubleSupplier y,
        DoubleSupplier z,
        float yaw,
        float pitch,
        Runnable onSuccess
    ) {
        queueDelayedTeleport(player, payer, kind, level, x, y, z, yaw, pitch, onSuccess, null);
    }

    private ChargeResult charge(ServerPlayer player, TeleportKind kind) {
        return evaluateCharge(player, kind, true);
    }

    private ChargeResult previewCharge(ServerPlayer player, TeleportKind kind) {
        return evaluateCharge(player, kind, false);
    }

    private ChargeResult evaluateCharge(ServerPlayer player, TeleportKind kind, boolean deduct) {
        if (!config.costsEnabled || config.costMode == CostMode.NONE) {
            return ChargeResult.ok();
        }
        if (config.costMode == CostMode.XP_LEVELS) {
            int cost = config.xpCost(kind, player.serverLevel());
            if (cost <= 0) {
                return ChargeResult.ok();
            }
            if (player.experienceLevel < cost) {
                return ChargeResult.fail(cost + " XP level" + (cost == 1 ? "" : "s"));
            }
            if (deduct) {
                player.giveExperienceLevels(-cost);
            }
            return ChargeResult.okPaid(cost + " XP level" + (cost == 1 ? "" : "s"));
        }
        if (config.costMode == CostMode.CURRENCY) {
            double amount = config.currencyCost(kind, player.serverLevel());
            if (amount <= 0) {
                return ChargeResult.ok();
            }
            return ChargeResult.fail(String.format(Locale.US, "%.2f currency (unsupported on Fabric runtime)", amount));
        }

        int amount = config.itemCost(kind, player.serverLevel());
        if (amount <= 0) {
            return ChargeResult.ok();
        }
        Item item = config.costItem;
        int available = player.getInventory().countItem(item);
        if (available < amount) {
            return ChargeResult.fail(amount + " " + displayMaterial(config.costItemName));
        }
        if (!deduct) {
            return ChargeResult.okPaid(amount + " " + displayMaterial(config.costItemName));
        }
        int remaining = amount;
        for (int slot = 0; slot < player.getInventory().getContainerSize() && remaining > 0; slot++) {
            ItemStack stack = player.getInventory().getItem(slot);
            if (stack.isEmpty() || !stack.is(item)) {
                continue;
            }
            int take = Math.min(remaining, stack.getCount());
            stack.shrink(take);
            remaining -= take;
            player.getInventory().setItem(slot, stack);
        }
        player.getInventory().setChanged();
        return ChargeResult.okPaid(amount + " " + displayMaterial(config.costItemName));
    }

    private void cancelPending(ServerPlayer player, String messageKey) {
        PendingTeleport pending = pendingTeleports.remove(player.getUUID());
        if (pending != null) {
            send(player, messageKey);
            if (pending.notifyPlayerId != null && server != null) {
                ServerPlayer notify = server.getPlayerList().getPlayer(pending.notifyPlayerId);
                if (notify != null) {
                    String reason = messages.getOrDefault(messageKey, messageKey);
                    sendRaw(notify, player.getGameProfile().getName() + "'s teleport was cancelled (" + reason + ")");
                }
            }
            play(player, "cancelled");
        }
    }

    private boolean hasMovedBlock(ServerPlayer player, BlockPos start) {
        BlockPos now = BlockPos.containing(player.getX(), player.getY(), player.getZ());
        return now.getX() != start.getX() || now.getY() != start.getY() || now.getZ() != start.getZ();
    }

    private boolean teleport(ServerPlayer player, TeleportTarget target) {
        if (config.teleportDisabledIn(player.serverLevel())) {
            send(player, "teleport_disabled_dimension", Map.of("dimension", player.serverLevel().dimension().location().toString()));
            return false;
        }
        if (config.teleportDisabledIn(target.level)) {
            send(player, "teleport_disabled_dimension", Map.of("dimension", target.level.dimension().location().toString()));
            return false;
        }
        TeleportTarget adjusted = adjustLanding(target);
        return teleport(player, adjusted.level, adjusted.x, adjusted.y, adjusted.z, adjusted.yaw, adjusted.pitch);
    }

    private TeleportTarget resolveYtpTarget(ServerPlayer player, ServerLevel level, double x, double y, double z, float yaw, float pitch) {
        TeleportTarget desired = new TeleportTarget(level, x, y, z, yaw, pitch);
        if (bypassSafeYtp(player) || isSafeStandLocation(level, (int) Math.floor(x), (int) Math.floor(y), (int) Math.floor(z))) {
            return desired;
        }
        TeleportTarget safe = findNearestSafeYtp(desired, 48, 64);
        return safe != null ? safe : desired;
    }

    private boolean bypassSafeYtp(ServerPlayer player) {
        GameType mode = player.gameMode.getGameModeForPlayer();
        return mode == GameType.CREATIVE || mode == GameType.SPECTATOR;
    }

    private TeleportTarget findNearestSafeYtp(TeleportTarget desired, int maxHorizontalRadius, int verticalRange) {
        ServerLevel level = desired.level;
        int originX = (int) Math.floor(desired.x);
        int originY = (int) Math.floor(desired.y);
        int originZ = (int) Math.floor(desired.z);
        int minY = level.getMinBuildHeight() + 1;
        int maxY = level.getMaxBuildHeight() - 2;
        int startY = clamp(originY, minY, maxY);
        double bestDistanceSq = Double.MAX_VALUE;
        TeleportTarget best = null;

        for (int radius = 0; radius <= maxHorizontalRadius; radius++) {
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if (Math.abs(dx) != radius && Math.abs(dz) != radius) {
                        continue;
                    }
                    int x = originX + dx;
                    int z = originZ + dz;
                    TeleportTarget candidate = findSafeInColumn(level, x, z, startY, verticalRange, desired.yaw, desired.pitch);
                    if (candidate == null) {
                        continue;
                    }
                    double distanceSq = distanceSquared(desired.x, desired.y, desired.z, candidate.x, candidate.y, candidate.z);
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
            BlockPos spawn = level.getSharedSpawnPos();
            best = findSafeInColumn(level, spawn.getX(), spawn.getZ(), spawn.getY(), verticalRange, desired.yaw, desired.pitch);
            if (best == null) {
                best = new TeleportTarget(level, spawn.getX() + 0.5, spawn.getY() + 1, spawn.getZ() + 0.5, desired.yaw, desired.pitch);
            }
        }
        return best;
    }

    private TeleportTarget findSafeInColumn(ServerLevel level, int x, int z, int targetY, int verticalRange, float yaw, float pitch) {
        if (!level.getWorldBorder().isWithinBounds(x, z)) {
            return null;
        }
        int minY = level.getMinBuildHeight() + 1;
        int maxY = level.getMaxBuildHeight() - 2;
        int startY = clamp(targetY, minY, maxY);

        for (int dy = 0; dy <= verticalRange; dy++) {
            int up = startY + dy;
            if (up <= maxY && isSafeStandLocation(level, x, up, z)) {
                return new TeleportTarget(level, x + 0.5, up, z + 0.5, yaw, pitch);
            }
            if (dy > 0) {
                int down = startY - dy;
                if (down >= minY && isSafeStandLocation(level, x, down, z)) {
                    return new TeleportTarget(level, x + 0.5, down, z + 0.5, yaw, pitch);
                }
            }
        }

        int surfaceY = clamp(level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) + 1, minY, maxY);
        if (isSafeStandLocation(level, x, surfaceY, z)) {
            return new TeleportTarget(level, x + 0.5, surfaceY, z + 0.5, yaw, pitch);
        }
        return null;
    }

    private boolean isSafeStandLocation(ServerLevel level, int x, int y, int z) {
        int minY = level.getMinBuildHeight() + 1;
        int maxY = level.getMaxBuildHeight() - 2;
        if (y < minY || y > maxY) {
            return false;
        }

        BlockPos feet = new BlockPos(x, y, z);
        BlockPos head = feet.above();
        BlockPos below = feet.below();
        return isClear(level, feet) && isClear(level, head) && isSolid(level, below);
    }

    private boolean isClear(ServerLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        return state.getCollisionShape(level, pos).isEmpty() && state.getFluidState().isEmpty();
    }

    private boolean isSolid(ServerLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        return !state.getCollisionShape(level, pos).isEmpty() && state.getFluidState().isEmpty();
    }

    private double distanceSquared(double x1, double y1, double z1, double x2, double y2, double z2) {
        double dx = x1 - x2;
        double dy = y1 - y2;
        double dz = z1 - z2;
        return dx * dx + dy * dy + dz * dz;
    }

    private int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private ServerLevel firstBlockedTeleportLevel(ServerLevel source, ServerLevel destination) {
        if (config.teleportDisabledIn(source)) {
            return source;
        }
        if (config.teleportDisabledIn(destination)) {
            return destination;
        }
        return null;
    }

    private TeleportTarget adjustLanding(TeleportTarget target) {
        if (config.landingMode != LandingMode.RANDOM_OFFSET) {
            return target;
        }
        int maxOffset = Math.max(1, config.landingRandomOffset);
        for (int i = 0; i < 8; i++) {
            int dx = target.level.random.nextInt(maxOffset * 2 + 1) - maxOffset;
            int dz = target.level.random.nextInt(maxOffset * 2 + 1) - maxOffset;
            int x = (int) Math.floor(target.x + dx);
            int z = (int) Math.floor(target.z + dz);
            BlockPos top = target.level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, new BlockPos(x, 0, z));
            return new TeleportTarget(target.level, top.getX() + 0.5, top.getY() + 1, top.getZ() + 0.5, target.yaw, target.pitch);
        }
        return target;
    }

    private boolean teleport(ServerPlayer player, ServerLevel level, double x, double y, double z, float yaw, float pitch) {
        if (config.teleportDisabledIn(player.serverLevel())) {
            send(player, "teleport_disabled_dimension", Map.of("dimension", player.serverLevel().dimension().location().toString()));
            return false;
        }
        if (config.teleportDisabledIn(level)) {
            send(player, "teleport_disabled_dimension", Map.of("dimension", level.dimension().location().toString()));
            return false;
        }
        player.teleportTo(level, x, y, z, yaw, pitch);
        return true;
    }

    private Position randomSafe(ServerPlayer player, ServerLevel level, double centerX, double centerZ, int minDistance, int maxDistance) {
        WorldBorder border = level.getWorldBorder();
        int min = Math.max(0, minDistance);
        int max = Math.max(min + 1, maxDistance);

        for (int i = 0; i < 80; i++) {
            double angle = level.random.nextDouble() * Math.PI * 2.0;
            int distance = min + level.random.nextInt(max - min + 1);
            int x = (int) Math.floor(centerX + Math.cos(angle) * distance);
            int z = (int) Math.floor(centerZ + Math.sin(angle) * distance);
            if (!border.isWithinBounds(x, z)) {
                continue;
            }
            BlockPos top = level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, new BlockPos(x, 0, z));
            int y = top.getY() + 1;
            if (bypassSafeYtp(player) || isSafeStandLocation(level, x, y, z)) {
                return new Position(top.getX() + 0.5, y, top.getZ() + 0.5, playerDim(level), player.getYRot(), player.getXRot());
            }

            TeleportTarget desired = new TeleportTarget(level, top.getX() + 0.5, y, top.getZ() + 0.5, player.getYRot(), player.getXRot());
            TeleportTarget safe = findNearestSafeYtp(desired, 48, 64);
            if (safe != null) {
                return new Position(safe.x, safe.y, safe.z, playerDim(level), safe.yaw, safe.pitch);
            }
        }

        BlockPos spawn = level.getSharedSpawnPos();
        TeleportTarget fallback = findNearestSafeYtp(new TeleportTarget(level, spawn.getX() + 0.5, spawn.getY() + 1, spawn.getZ() + 0.5, player.getYRot(), player.getXRot()), 48, 64);
        if (fallback != null) {
            return new Position(fallback.x, fallback.y, fallback.z, playerDim(level), fallback.yaw, fallback.pitch);
        }
        return new Position(spawn.getX() + 0.5, spawn.getY() + 1, spawn.getZ() + 0.5, playerDim(level), player.getYRot(), player.getXRot());
    }

    private String playerDim(ServerLevel level) {
        return level.dimension().location().toString();
    }

    private TeleportTarget configuredSpawnTarget(ServerPlayer player, ServerLevel fallbackLevel) {
        MinecraftServer minecraftServer = Objects.requireNonNull(player.getServer());
        ServerLevel level = resolveSpawnWorld(minecraftServer, config.spawnWorld);
        if (level == null) {
            level = fallbackLevel != null ? fallbackLevel : minecraftServer.overworld();
        }
        return new TeleportTarget(level, config.spawnX, config.spawnY, config.spawnZ, (float) config.spawnYaw, (float) config.spawnPitch);
    }

    private ServerLevel resolveSpawnWorld(MinecraftServer minecraftServer, String worldKey) {
        if (worldKey == null || worldKey.isBlank()) {
            return minecraftServer.overworld();
        }
        String input = worldKey.trim().toLowerCase(Locale.ROOT);
        return switch (input) {
            case "overworld", "world", "normal" -> minecraftServer.getLevel(Level.OVERWORLD);
            case "nether", "the_nether" -> minecraftServer.getLevel(Level.NETHER);
            case "end", "the_end" -> minecraftServer.getLevel(Level.END);
            default -> resolveRealmById(minecraftServer, input);
        };
    }

    private int cooldownRemaining(UUID sender) {
        long last = cooldownBySender.getOrDefault(sender, 0L);
        long elapsed = (System.currentTimeMillis() - last) / 1000L;
        long remain = config.requestCooldownSeconds - elapsed;
        return (int) Math.max(0, remain);
    }

    private int rtpCooldownRemaining(UUID playerId) {
        long last = rtpCooldownByPlayer.getOrDefault(playerId, 0L);
        long elapsed = (System.currentTimeMillis() - last) / 1000L;
        long remain = config.rtpCooldownSeconds - elapsed;
        return (int) Math.max(0, remain);
    }

    private void sendRtpCooldown(ServerPlayer player, int seconds) {
        String template = messages.getOrDefault("rtp_cooldown", "rtp_cooldown");
        if ("rtp_cooldown".equals(template) || !template.contains("%seconds%")) {
            sendRaw(player, "You must wait " + seconds + "s before using /rtp again.");
            return;
        }
        send(player, "rtp_cooldown", Map.of("seconds", Integer.toString(seconds)));
    }

    private boolean ensureAppEnabled(CommandSourceStack source) {
        if (config.appEnabled) {
            return true;
        }
        send(source, "app_disabled");
        return false;
    }

    private void play(ServerPlayer player, String key) {
        SoundEvent sound = config.sound(key);
        if (sound != null) {
            player.playNotifySound(sound, SoundSource.PLAYERS, 1.0f, 1.0f);
        }
        ParticleOptions effect = config.effect(key);
        if (effect != null) {
            player.serverLevel().sendParticles(effect, player.getX(), player.getY() + 1, player.getZ(), 20, 0.4, 0.6, 0.4, 0.02);
        }
    }

    private boolean isKindFeatureEnabled(TeleportKind kind) {
        return switch (kind) {
            case TPA -> config.tpaEnabled;
            case TPAHERE -> config.tpaHereEnabled;
            case HOME -> config.homesEnabled;
            case RTP -> config.rtpEnabled;
            case SPAWN -> true;
            case BACK -> config.tpabackEnabled;
        };
    }

    private String commandFor(TeleportKind kind) {
        return switch (kind) {
            case TPA -> "/tpa";
            case TPAHERE -> "/tpahere";
            case HOME -> "/tphome";
            case RTP -> "/rtp";
            case SPAWN -> "/spawn";
            case BACK -> "/tpaback";
        };
    }

    private String displayMaterial(String material) {
        String base = material.contains(":") ? material.substring(material.indexOf(':') + 1) : material;
        String[] parts = base.toLowerCase(Locale.ROOT).split("_");
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) {
                builder.append(' ');
            }
            String part = parts[i];
            builder.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
        }
        String singular = builder.toString();
        if (singular.endsWith("s")) {
            return singular;
        }
        return singular + "s";
    }

    private void send(CommandSourceStack source, String key) {
        source.sendSuccess(() -> Component.literal(messages.getOrDefault("prefix", "") + messages.getOrDefault(key, key)), false);
    }

    private void send(CommandSourceStack source, String key, Map<String, String> replacements) {
        source.sendSuccess(() -> Component.literal(messages.getOrDefault("prefix", "") + format(key, replacements)), false);
    }

    private void sendRaw(CommandSourceStack source, String text) {
        source.sendSuccess(() -> Component.literal(messages.getOrDefault("prefix", "") + text), false);
    }

    private void send(ServerPlayer player, String key) {
        player.sendSystemMessage(Component.literal(messages.getOrDefault("prefix", "") + messages.getOrDefault(key, key)));
    }

    private void send(ServerPlayer player, String key, Map<String, String> replacements) {
        player.sendSystemMessage(Component.literal(messages.getOrDefault("prefix", "") + format(key, replacements)));
    }

    private void sendRaw(ServerPlayer player, String text) {
        player.sendSystemMessage(Component.literal(messages.getOrDefault("prefix", "") + text));
    }

    private String format(String key, Map<String, String> replacements) {
        String value = messages.getOrDefault(key, key);
        for (Map.Entry<String, String> entry : replacements.entrySet()) {
            value = value.replace("%" + entry.getKey() + "%", entry.getValue());
        }
        return value;
    }

    private void loadFiles() {
        try {
            Files.createDirectories(configDir);
            this.runtimeMessagesPath = configDir.resolve("messages.xml");
            this.runtimeConfigPath = configDir.resolve("config.properties");

            copyDefaultIfMissing("messages-fabric.xml", runtimeMessagesPath);
            copyDefaultIfMissing("yatpa-fabric.properties", runtimeConfigPath);

            loadMessages(runtimeMessagesPath);
            loadConfig(runtimeConfigPath);
            loadStore();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to initialize YATPA Fabric files", e);
        }
    }

    private void copyDefaultIfMissing(String resource, Path target) throws IOException {
        if (Files.exists(target)) {
            return;
        }
        try (var in = Objects.requireNonNull(getClass().getClassLoader().getResourceAsStream(resource), "Missing resource: " + resource)) {
            Files.write(target, in.readAllBytes());
        }
    }

    private void loadMessages(Path path) {
        messages.clear();
        try {
            Document doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(path.toFile());
            NodeList nodes = doc.getElementsByTagName("message");
            for (int i = 0; i < nodes.getLength(); i++) {
                Element element = (Element) nodes.item(i);
                messages.put(element.getAttribute("key"), element.getTextContent());
            }
        } catch (Exception e) {
            throw new IllegalStateException("Failed to load XML messages", e);
        }
    }

    private void loadConfig(Path path) throws IOException {
        Properties properties = new Properties();
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            properties.load(reader);
        }
        boolean changed = false;
        for (Map.Entry<String, String> entry : defaultConfigValues().entrySet()) {
            if (!properties.containsKey(entry.getKey())) {
                properties.setProperty(entry.getKey(), entry.getValue());
                changed = true;
            }
        }
        this.rawConfig = properties;
        this.config = Config.from(properties);
        if (changed) {
            saveConfigProperties();
        }
    }

    private void saveConfigProperties() throws IOException {
        Files.createDirectories(runtimeConfigPath.getParent());
        try (Writer writer = Files.newBufferedWriter(runtimeConfigPath, StandardCharsets.UTF_8)) {
            rawConfig.store(writer, "YATPA Fabric configuration");
        }
    }

    private void loadStore() throws IOException {
        if (!Files.exists(storePath)) {
            this.store = new Store();
            saveStore();
            return;
        }
        try (Reader reader = Files.newBufferedReader(storePath, StandardCharsets.UTF_8)) {
            Store loaded = GSON.fromJson(reader, STORE_TYPE);
            this.store = loaded == null ? new Store() : loaded;
        }
    }

    private void saveStore() {
        try {
            Files.createDirectories(storePath.getParent());
            try (Writer writer = Files.newBufferedWriter(storePath, StandardCharsets.UTF_8)) {
                GSON.toJson(store, STORE_TYPE, writer);
            }
        } catch (IOException ignored) {
        }
    }

    private static Map<String, String> defaultConfigValues() {
        Map<String, String> defaults = new LinkedHashMap<>();
        defaults.put("settings.max_homes_default", "3");
        defaults.put("settings.request_timeout_seconds", "60");
        defaults.put("settings.request_cooldown_seconds", "30");
        defaults.put("settings.teleport_delay_seconds", "5");
        defaults.put("settings.cancel_on_move", "true");
        defaults.put("settings.cancel_on_damage", "true");
        defaults.put("settings.spawn_radius", "50");
        defaults.put("settings.rtp_cooldown_seconds", "300");
        defaults.put("settings.rtp.default_min_distance", "64");
        defaults.put("settings.rtp.default_max_distance", "2500");
        defaults.put("settings.rtp.rtp_to_overworld", "false");
        defaults.put("settings.rtp.blacklisted_worlds", "");
        defaults.put("settings.rtp.overworld_name", "world");
        defaults.put("settings.dimension_restrictions.disable_rtp", "");
        defaults.put("settings.dimension_restrictions.disable_teleport", "");
        defaults.put("settings.spawn.enabled", "true");
        defaults.put("settings.spawn.x", "0");
        defaults.put("settings.spawn.y", "100");
        defaults.put("settings.spawn.z", "0");
        defaults.put("settings.spawn.yaw", "0");
        defaults.put("settings.spawn.pitch", "0");
        defaults.put("settings.spawn.world", "world");
        // Optional realm-specific distance defaults can be provided by users:
        // settings.rtp.default_min_distance.overworld / nether / end
        // settings.rtp.default_max_distance.overworld / nether / end
        defaults.put("settings.landing.mode", "EXACT");
        defaults.put("settings.landing.random_offset_max", "4");
        defaults.put("settings.features.enabled", "true");
        defaults.put("settings.features.tpa", "true");
        defaults.put("settings.features.tpahere", "true");
        defaults.put("settings.features.homes", "true");
        defaults.put("settings.features.rtp", "true");
        defaults.put("settings.features.tpaback", "true");
        defaults.put("settings.costs.enabled", "false");
        defaults.put("settings.costs.mode", "NONE");
        defaults.put("settings.costs.item.material", "ENDER_PEARL");
        defaults.put("settings.costs.xp_levels.tpa", "4");
        defaults.put("settings.costs.xp_levels.tpahere", "4");
        defaults.put("settings.costs.xp_levels.home", "16");
        defaults.put("settings.costs.xp_levels.back", "0");
        defaults.put("settings.costs.xp_levels.rtp", "30");
        defaults.put("settings.costs.xp_levels.spawn", "8");
        defaults.put("settings.costs.item.tpa", "2");
        defaults.put("settings.costs.item.tpahere", "2");
        defaults.put("settings.costs.item.home", "20");
        defaults.put("settings.costs.item.back", "0");
        defaults.put("settings.costs.item.rtp", "50");
        defaults.put("settings.costs.item.spawn", "10");
        defaults.put("settings.costs.currency.tpa", "0.0");
        defaults.put("settings.costs.currency.tpahere", "0.0");
        defaults.put("settings.costs.currency.home", "0.0");
        defaults.put("settings.costs.currency.back", "0.0");
        defaults.put("settings.costs.currency.rtp", "0.0");
        defaults.put("settings.costs.currency.spawn", "0.0");
        defaults.put("sounds.request_sent", "ENTITY_EXPERIENCE_ORB_PICKUP");
        defaults.put("sounds.request_received", "BLOCK_NOTE_BLOCK_PLING");
        defaults.put("sounds.countdown", "BLOCK_BELL_USE");
        defaults.put("sounds.success", "ENTITY_ENDERMAN_TELEPORT");
        defaults.put("sounds.cancelled", "BLOCK_GLASS_BREAK");
        defaults.put("effects.request_sent", "PORTAL");
        defaults.put("effects.request_received", "ENCHANT");
        defaults.put("effects.countdown", "WAX_OFF");
        defaults.put("effects.success", "END_ROD");
        defaults.put("effects.cancelled", "SMOKE");
        return defaults;
    }

    private enum RequestType { TPA, TPAHERE }

    private enum TeleportKind { TPA, TPAHERE, HOME, RTP, SPAWN, BACK }

    private enum LandingMode { EXACT, RANDOM_OFFSET }

    private enum CostMode { NONE, XP_LEVELS, ITEM, CURRENCY }

    private record ChargeResult(boolean success, String required, String paid) {
        private static ChargeResult ok() { return new ChargeResult(true, "", ""); }
        private static ChargeResult okPaid(String paid) { return new ChargeResult(true, "", paid); }
        private static ChargeResult fail(String required) { return new ChargeResult(false, required, ""); }
    }

    private record ExpiredRequest(UUID receiver, Request request) {}

    private static class Request {
        private final UUID sender;
        private final RequestType type;
        private final long createdAtMillis;

        private Request(UUID sender, RequestType type, long createdAtMillis) {
            this.sender = sender;
            this.type = type;
            this.createdAtMillis = createdAtMillis;
        }
    }

    private static class PendingTeleport {
        private final BlockPos startPos;
        private int ticksLeft;
        private final TargetSupplier targetSupplier;
        private final Runnable onSuccess;
        private final UUID payerId;
        private final TeleportKind kind;
        private final UUID notifyPlayerId;

        private PendingTeleport(
            BlockPos startPos,
            int ticksLeft,
            TargetSupplier targetSupplier,
            Runnable onSuccess,
            UUID payerId,
            TeleportKind kind,
            UUID notifyPlayerId
        ) {
            this.startPos = startPos;
            this.ticksLeft = ticksLeft;
            this.targetSupplier = targetSupplier;
            this.onSuccess = onSuccess;
            this.payerId = payerId;
            this.kind = kind;
            this.notifyPlayerId = notifyPlayerId;
        }
    }

    @FunctionalInterface
    private interface TargetSupplier {
        TeleportTarget get();
    }

    @FunctionalInterface
    private interface LevelSupplier {
        ServerLevel get();
    }

    @FunctionalInterface
    private interface DoubleSupplier {
        double get();
    }

    private record TeleportTarget(ServerLevel level, double x, double y, double z, float yaw, float pitch) {}

    private static class Config {
        int maxHomesDefault = 3;
        int requestTimeoutSeconds = 60;
        int requestCooldownSeconds = 30;
        int teleportDelaySeconds = 5;
        boolean cancelOnMove = true;
        boolean cancelOnDamage = true;
        int spawnRadius = 50;
        int rtpCooldownSeconds = 300;
        int rtpMinDistance = 64;
        int rtpMaxDistance = 2500;
        Map<String, Integer> rtpMinByDimension = new HashMap<>();
        Map<String, Integer> rtpMaxByDimension = new HashMap<>();
        Set<String> rtpDisabledDimensions = new HashSet<>();
        Set<String> teleportDisabledDimensions = new HashSet<>();
        LandingMode landingMode = LandingMode.EXACT;
        int landingRandomOffset = 4;
        boolean appEnabled = true;
        boolean tpaEnabled = true;
        boolean tpaHereEnabled = true;
        boolean homesEnabled = true;
        boolean rtpEnabled = true;
        boolean tpabackEnabled = true;
        boolean rtpToOverworld = false;
        String overworldName = "world";
        boolean spawnEnabled = true;
        double spawnX = 0.0;
        double spawnY = 100.0;
        double spawnZ = 0.0;
        double spawnYaw = 0.0;
        double spawnPitch = 0.0;
        String spawnWorld = "world";
        boolean costsEnabled = false;
        CostMode costMode = CostMode.NONE;
        String costItemName = "ENDER_PEARL";
        Item costItem = Items.ENDER_PEARL;
        Map<TeleportKind, Integer> xpCosts = new EnumMap<>(TeleportKind.class);
        Map<TeleportKind, Integer> itemCosts = new EnumMap<>(TeleportKind.class);
        Map<TeleportKind, Double> currencyCosts = new EnumMap<>(TeleportKind.class);
        Map<String, Map<TeleportKind, Integer>> xpCostsByDimension = new HashMap<>();
        Map<String, Map<TeleportKind, Integer>> itemCostsByDimension = new HashMap<>();
        Map<String, Map<TeleportKind, Double>> currencyCostsByDimension = new HashMap<>();
        Map<String, SoundEvent> sounds = new HashMap<>();
        Map<String, ParticleOptions> effects = new HashMap<>();

        static Config from(Properties p) {
            Config c = new Config();
            c.maxHomesDefault = intProp(p, 3, "settings.max_homes_default", "max_homes_default");
            c.requestTimeoutSeconds = intProp(p, 60, "settings.request_timeout_seconds", "request_timeout_seconds");
            c.requestCooldownSeconds = intProp(p, 30, "settings.request_cooldown_seconds", "request_cooldown_seconds");
            c.teleportDelaySeconds = intProp(p, 5, "settings.teleport_delay_seconds", "teleport_delay_seconds");
            c.cancelOnMove = boolProp(p, true, "settings.cancel_on_move", "cancel_on_move");
            c.cancelOnDamage = boolProp(p, true, "settings.cancel_on_damage", "cancel_on_damage");
            c.spawnRadius = intProp(p, 50, "settings.spawn_radius", "spawn_radius");
            c.rtpCooldownSeconds = intProp(p, 300, "settings.rtp_cooldown_seconds", "rtp_cooldown_seconds");
            c.rtpMinDistance = intProp(p, 64, "settings.rtp.default_min_distance", "settings.rtp_min_distance", "rtp_min_distance");
            c.rtpMaxDistance = intProp(p, 2500, "settings.rtp.default_max_distance", "settings.rtp_max_distance", "rtp_max_distance");
            c.rtpToOverworld = boolProp(p, false, "settings.rtp.rtp_to_overworld");
            c.overworldName = stringProp(p, "world", "settings.rtp.overworld_name");
            c.landingMode = parseLanding(stringProp(p, "EXACT", "settings.landing.mode", "landing_mode"));
            c.landingRandomOffset = intProp(p, 4, "settings.landing.random_offset_max", "landing_random_offset");
            c.appEnabled = boolProp(p, true, "settings.features.enabled", "features_enabled");
            c.tpaEnabled = boolProp(p, true, "settings.features.tpa", "feature_tpa");
            c.tpaHereEnabled = boolProp(p, true, "settings.features.tpahere", "feature_tpahere");
            c.homesEnabled = boolProp(p, true, "settings.features.homes", "feature_homes");
            c.rtpEnabled = boolProp(p, true, "settings.features.rtp", "feature_rtp");
            c.tpabackEnabled = boolProp(p, true, "settings.features.tpaback", "feature_tpaback");
            c.spawnEnabled = boolProp(p, true, "settings.spawn.enabled");
            c.spawnX = doubleProp(p, 0.0, "settings.spawn.x");
            c.spawnY = doubleProp(p, 100.0, "settings.spawn.y");
            c.spawnZ = doubleProp(p, 0.0, "settings.spawn.z");
            c.spawnYaw = doubleProp(p, 0.0, "settings.spawn.yaw");
            c.spawnPitch = doubleProp(p, 0.0, "settings.spawn.pitch");
            c.spawnWorld = stringProp(p, "world", "settings.spawn.world");
            c.costsEnabled = boolProp(p, false, "settings.costs.enabled", "costs_enabled");
            c.costMode = parseCostMode(stringProp(p, "NONE", "settings.costs.mode", "cost_mode"));
            c.costItemName = stringProp(p, "ENDER_PEARL", "settings.costs.item.material", "cost_item_material");
            c.costItem = parseItem(c.costItemName);

            for (TeleportKind kind : TeleportKind.values()) {
                String key = kind.name().toLowerCase(Locale.ROOT);
                c.xpCosts.put(kind, intProp(p, 0, "settings.costs.xp_levels." + key));
                c.itemCosts.put(kind, intProp(p, 0, "settings.costs.item." + key));
                c.currencyCosts.put(kind, doubleProp(p, 0, "settings.costs.currency." + key));
            }

            // Parse any per-dimension cost overrides using keys like
            // settings.costs.xp_levels.<dimension>.<teleport>
            for (String rawKey : p.stringPropertyNames()) {
                if (rawKey.startsWith("settings.costs.xp_levels.")) {
                    String rest = rawKey.substring("settings.costs.xp_levels.".length());
                    int idx = rest.indexOf('.');
                    if (idx > 0) {
                        String dim = rest.substring(0, idx);
                        String kindKey = rest.substring(idx + 1);
                        try {
                            TeleportKind kind = TeleportKind.valueOf(kindKey.toUpperCase(Locale.ROOT));
                            int val = intProp(p, 0, rawKey);
                            c.xpCostsByDimension.computeIfAbsent(dim, d -> new EnumMap<>(TeleportKind.class)).put(kind, val);
                        } catch (IllegalArgumentException ignored) {
                        }
                    }
                }
                if (rawKey.startsWith("settings.costs.item.")) {
                    String rest = rawKey.substring("settings.costs.item.".length());
                    int idx = rest.indexOf('.');
                    if (idx > 0) {
                        String dim = rest.substring(0, idx);
                        String kindKey = rest.substring(idx + 1);
                        try {
                            TeleportKind kind = TeleportKind.valueOf(kindKey.toUpperCase(Locale.ROOT));
                            int val = intProp(p, 0, rawKey);
                            c.itemCostsByDimension.computeIfAbsent(dim, d -> new EnumMap<>(TeleportKind.class)).put(kind, val);
                        } catch (IllegalArgumentException ignored) {
                        }
                    }
                }
                if (rawKey.startsWith("settings.dimension_restrictions.disable_rtp.")) {
                    String dim = normalizeDimension(rawKey.substring("settings.dimension_restrictions.disable_rtp.".length()));
                    if (boolProp(p, false, rawKey) && !dim.isBlank()) {
                        c.rtpDisabledDimensions.add(dim);
                    }
                }
                if (rawKey.startsWith("settings.costs.currency.")) {
                    String rest = rawKey.substring("settings.costs.currency.".length());
                    int idx = rest.indexOf('.');
                    if (idx > 0) {
                        String dim = rest.substring(0, idx);
                        String kindKey = rest.substring(idx + 1);
                        try {
                            TeleportKind kind = TeleportKind.valueOf(kindKey.toUpperCase(Locale.ROOT));
                            double val = doubleProp(p, 0, rawKey);
                            c.currencyCostsByDimension.computeIfAbsent(dim, d -> new EnumMap<>(TeleportKind.class)).put(kind, val);
                        } catch (IllegalArgumentException ignored) {
                        }
                    }
                }
                if (rawKey.startsWith("settings.dimension_restrictions.disable_teleport.")) {
                    String dim = normalizeDimension(rawKey.substring("settings.dimension_restrictions.disable_teleport.".length()));
                    if (boolProp(p, false, rawKey) && !dim.isBlank()) {
                        c.teleportDisabledDimensions.add(dim);
                    }
                }
            }
            addDimensionList(c.rtpDisabledDimensions, stringProp(p, "", "settings.dimension_restrictions.disable_rtp"));
            addDimensionList(c.teleportDisabledDimensions, stringProp(p, "", "settings.dimension_restrictions.disable_teleport"));
            c.rtpDisabledDimensions.addAll(parseDimensionRestrictions(p, "settings.rtp.blacklisted_worlds"));
            c.rtpDisabledDimensions.addAll(parseDimensionRestrictions(p, "settings.rtp.blacklistedworlds"));
            c.rtpDisabledDimensions.addAll(parseDimensionRestrictions(p, "rtp.blacklisted_worlds"));
            c.rtpDisabledDimensions.addAll(parseDimensionRestrictions(p, "rtp.blacklistedworlds"));

            // Realm-friendly overrides for RTP: settings.costs.xp_levels.rtp.<realm>, item.rtp.<realm>
            Map<String, String> realmToDim = Map.of(
                "overworld", "minecraft:overworld",
                "nether", "minecraft:the_nether",
                "end", "minecraft:the_end"
            );

            // Distance overrides per realm: settings.rtp.default_min_distance.<realm>, settings.rtp.default_max_distance.<realm>
            for (Map.Entry<String, String> e2 : realmToDim.entrySet()) {
                String realm = e2.getKey();
                String dim = e2.getValue();
                int rmin = intProp(p, -1, "settings.rtp.default_min_distance." + realm);
                int rmax = intProp(p, -1, "settings.rtp.default_max_distance." + realm);
                if (rmin >= 0) c.rtpMinByDimension.put(dim, rmin);
                if (rmax >= 0) c.rtpMaxByDimension.put(dim, rmax);
            }
            for (Map.Entry<String, String> e : realmToDim.entrySet()) {
                String realm = e.getKey();
                String dim = e.getValue();
                int rxp = intProp(p, 0, "settings.costs.xp_levels.rtp." + realm);
                int rit = intProp(p, 0, "settings.costs.item.rtp." + realm);
                double rcur = doubleProp(p, 0, "settings.costs.currency.rtp." + realm);
                if (rxp > 0) {
                    c.xpCostsByDimension.computeIfAbsent(dim, d -> new EnumMap<>(TeleportKind.class)).put(TeleportKind.RTP, rxp);
                }
                if (rit > 0) {
                    c.itemCostsByDimension.computeIfAbsent(dim, d -> new EnumMap<>(TeleportKind.class)).put(TeleportKind.RTP, rit);
                }
                if (rcur > 0) {
                    c.currencyCostsByDimension.computeIfAbsent(dim, d -> new EnumMap<>(TeleportKind.class)).put(TeleportKind.RTP, rcur);
                }
            }

            for (String key : new String[]{"request_sent", "request_received", "countdown", "success", "cancelled"}) {
                c.sounds.put(key, parseSound(stringProp(p, "", "sounds." + key)));
                c.effects.put(key, parseParticle(stringProp(p, "", "effects." + key)));
            }
            return c;
        }

        private static int intProp(Properties p, int def, String... keys) {
            String value = find(p, keys);
            if (value == null) {
                return def;
            }
            try {
                return Integer.parseInt(value);
            } catch (NumberFormatException ignored) {
                return def;
            }
        }

        private static double doubleProp(Properties p, double def, String... keys) {
            String value = find(p, keys);
            if (value == null) {
                return def;
            }
            try {
                return Double.parseDouble(value);
            } catch (NumberFormatException ignored) {
                return def;
            }
        }

        private static boolean boolProp(Properties p, boolean def, String... keys) {
            String value = find(p, keys);
            if (value == null) {
                return def;
            }
            return Boolean.parseBoolean(value);
        }

        private static String stringProp(Properties p, String def, String... keys) {
            String value = find(p, keys);
            return value == null ? def : value;
        }

        private static String find(Properties p, String... keys) {
            for (String key : keys) {
                if (p.containsKey(key)) {
                    return p.getProperty(key);
                }
            }
            return null;
        }

        private static LandingMode parseLanding(String raw) {
            try {
                return LandingMode.valueOf(raw.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ignored) {
                return LandingMode.EXACT;
            }
        }

        private static CostMode parseCostMode(String raw) {
            try {
                return CostMode.valueOf(raw.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ignored) {
                return CostMode.NONE;
            }
        }

        private static Item parseItem(String raw) {
            if (raw == null || raw.isBlank()) {
                return Items.ENDER_PEARL;
            }
            String value = raw.trim().toLowerCase(Locale.ROOT);
            if (!value.contains(":")) {
                value = "minecraft:" + value;
            }
            ResourceLocation id = ResourceLocation.tryParse(value);
            if (id == null) {
                return Items.ENDER_PEARL;
            }
            return BuiltInRegistries.ITEM.getOptional(id).orElse(Items.ENDER_PEARL);
        }

        private static SoundEvent parseSound(String raw) {
            if (raw == null || raw.isBlank()) {
                return null;
            }
            String value = raw.trim().toLowerCase(Locale.ROOT);
            if (!value.contains(":")) {
                value = "minecraft:" + value.replace('_', '.');
            }
            ResourceLocation id = ResourceLocation.tryParse(value);
            if (id == null) {
                return null;
            }
            return BuiltInRegistries.SOUND_EVENT.getOptional(id).orElse(null);
        }

        private static ParticleOptions parseParticle(String raw) {
            if (raw == null || raw.isBlank()) {
                return null;
            }
            String value = raw.trim().toLowerCase(Locale.ROOT);
            if (!value.contains(":")) {
                value = "minecraft:" + value;
            }
            ResourceLocation id = ResourceLocation.tryParse(value);
            if (id == null) {
                return null;
            }
            var type = BuiltInRegistries.PARTICLE_TYPE.getOptional(id).orElse(null);
            if (type instanceof ParticleOptions options) {
                return options;
            }
            return null;
        }

        int xpCost(TeleportKind kind) {
            return xpCosts.getOrDefault(kind, 0);
        }

        int itemCost(TeleportKind kind) {
            return itemCosts.getOrDefault(kind, 0);
        }

        double currencyCost(TeleportKind kind) {
            return currencyCosts.getOrDefault(kind, 0.0);
        }

        int xpCost(TeleportKind kind, ServerLevel level) {
            if (level == null) return xpCost(kind);
            String dim = level.dimension().location().toString();
            Map<TeleportKind, Integer> map = xpCostsByDimension.get(dim);
            if (map != null && map.containsKey(kind)) {
                return map.get(kind);
            }
            return xpCost(kind);
        }

        int itemCost(TeleportKind kind, ServerLevel level) {
            if (level == null) return itemCost(kind);
            String dim = level.dimension().location().toString();
            Map<TeleportKind, Integer> map = itemCostsByDimension.get(dim);
            if (map != null && map.containsKey(kind)) {
                return map.get(kind);
            }
            return itemCost(kind);
        }

        double currencyCost(TeleportKind kind, ServerLevel level) {
            if (level == null) return currencyCost(kind);
            String dim = level.dimension().location().toString();
            Map<TeleportKind, Double> map = currencyCostsByDimension.get(dim);
            if (map != null && map.containsKey(kind)) {
                return map.get(kind);
            }
            return currencyCost(kind);
        }

        int rtpMin(ServerLevel level) {
            if (level == null) return rtpMinDistance;
            String dim = level.dimension().location().toString();
            return rtpMinByDimension.getOrDefault(dim, rtpMinDistance);
        }

        int rtpMax(ServerLevel level) {
            if (level == null) return rtpMaxDistance;
            String dim = level.dimension().location().toString();
            return rtpMaxByDimension.getOrDefault(dim, rtpMaxDistance);
        }

        boolean rtpDisabledIn(ServerLevel level) {
            return dimensionIsRestricted(level, rtpDisabledDimensions);
        }

        boolean teleportDisabledIn(ServerLevel level) {
            return dimensionIsRestricted(level, teleportDisabledDimensions);
        }

        private static void addDimensionList(Set<String> sink, String raw) {
            if (raw == null || raw.isBlank()) {
                return;
            }
            for (String part : raw.split(",")) {
                String normalized = normalizeDimension(part);
                if (!normalized.isBlank()) {
                    sink.add(normalized);
                }
            }
        }

        private static Set<String> parseDimensionRestrictions(Properties p, String rootPath) {
            Set<String> values = new HashSet<>();
            addDimensionList(values, stringProp(p, "", rootPath));
            String prefix = rootPath + ".";
            for (String key : p.stringPropertyNames()) {
                if (!key.startsWith(prefix)) {
                    continue;
                }
                if (!boolProp(p, false, key)) {
                    continue;
                }
                String normalized = normalizeDimension(key.substring(prefix.length()));
                if (!normalized.isBlank()) {
                    values.add(normalized);
                }
            }
            return values;
        }

        private static String normalizeDimension(String value) {
            if (value == null) {
                return "";
            }
            return value.trim().toLowerCase(Locale.ROOT);
        }

        private static boolean dimensionIsRestricted(ServerLevel level, Set<String> restrictions) {
            if (level == null || restrictions.isEmpty()) {
                return false;
            }
            String id = normalizeDimension(level.dimension().location().toString());
            if (restrictions.contains(id)) {
                return true;
            }
            String path = normalizeDimension(level.dimension().location().getPath());
            if (restrictions.contains(path)) {
                return true;
            }
            String realm = "";
            if (level.dimension().equals(Level.OVERWORLD)) {
                realm = "overworld";
            } else if (level.dimension().equals(Level.NETHER)) {
                realm = "nether";
            } else if (level.dimension().equals(Level.END)) {
                realm = "end";
            }
            if (level.dimension().equals(Level.OVERWORLD) && restrictions.contains("world")) {
                return true;
            }
            return !realm.isBlank() && restrictions.contains(realm);
        }

        SoundEvent sound(String key) {
            return sounds.get(key);
        }

        ParticleOptions effect(String key) {
            return effects.get(key);
        }
    }

    private static class Store {
        Map<String, PlayerPrefs> playerPrefs = new HashMap<>();
        Map<String, Map<String, Position>> homes = new HashMap<>();
        Map<String, String> defaultHomes = new HashMap<>();
        Map<String, Integer> homeLimits = new HashMap<>();
        Map<String, Position> offlineLocations = new HashMap<>();
        Map<String, Position> deathLocations = new HashMap<>();
        Set<String> joinedPlayers = new HashSet<>();
    }

    private static class PlayerPrefs {
        boolean acceptingRequests = true;
        Set<String> blocked = new HashSet<>();
    }

    private static class Position {
        double x;
        double y;
        double z;
        String dimension;
        float yaw;
        float pitch;

        Position(double x, double y, double z, String dimension, float yaw, float pitch) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.dimension = dimension;
            this.yaw = yaw;
            this.pitch = pitch;
        }

        static Position fromPlayer(ServerPlayer player) {
            return new Position(player.getX(), player.getY(), player.getZ(), player.serverLevel().dimension().location().toString(), player.getYRot(), player.getXRot());
        }
    }
}
