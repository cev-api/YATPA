package dev.yatpa.paper;

import dev.yatpa.paper.command.YatpaCommandHandler;
import dev.yatpa.paper.config.XmlMessages;
import dev.yatpa.paper.config.YatpaConfig;
import dev.yatpa.paper.gui.SettingsGui;
import dev.yatpa.paper.listener.PlayerEventListener;
import dev.yatpa.paper.service.CostService;
import dev.yatpa.paper.service.DataStore;
import dev.yatpa.paper.service.RequestService;
import dev.yatpa.paper.service.TeleportLogService;
import dev.yatpa.paper.service.TeleportService;
import java.io.File;
import java.util.Map;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.command.PluginCommand;
import org.bukkit.event.HandlerList;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;

public class YatpaPaperPlugin extends JavaPlugin {
    private XmlMessages messages;
    private YatpaConfig configModel;
    private DataStore dataStore;
    private RequestService requests;
    private TeleportService teleports;
    private TeleportLogService teleportLog;
    private Economy economy;

    @Override
    public void onEnable() {
        bootstrap();
    }

    public void bootstrap() {
        getServer().getScheduler().cancelTasks(this);
        HandlerList.unregisterAll(this);

        if (!new File(getDataFolder(), "config.yml").exists()) {
            saveDefaultConfig();
        }
        if (!new File(getDataFolder(), "messages.xml").exists()) {
            saveResource("messages.xml", false);
        }
        ensureConfigDefaults();

        this.messages = new XmlMessages();
        this.messages.load(new File(getDataFolder(), "messages.xml"));
        this.configModel = YatpaConfig.from(getConfig());
        this.economy = setupEconomy();
        this.dataStore = new DataStore(getDataFolder());
        this.requests = new RequestService(configModel.requestTimeoutSeconds(), configModel.requestCooldownSeconds());
        if (this.teleportLog == null) {
            this.teleportLog = new TeleportLogService(200);
        }
        this.teleports = new TeleportService(this, configModel, messages, new CostService(configModel, economy), teleportLog);

        SettingsGui settingsGui = new SettingsGui(this, messages);
        YatpaCommandHandler handler = new YatpaCommandHandler(this, messages, configModel, dataStore, requests,
                teleports, settingsGui, teleportLog);
        for (String command : new String[] { "tpa", "yatpa", "tpahelp", "tphelp", "tpahere", "tpaccept", "tpdeny",
                "tpatoggle", "tpablock", "tpaunblock", "tphome", "rtp", "spawn", "ytp", "tpoffline", "tpaback",
                "tpalog",
                "setspawn" }) {
            PluginCommand pluginCommand = getCommand(command);
            if (pluginCommand != null) {
                pluginCommand.setExecutor(handler);
                pluginCommand.setTabCompleter(handler);
            } else {
                getLogger().warning("Command '" + command + "' was not registered for YATPA. "
                        + "If another plugin provides it, use the namespaced form '/yatpa:" + command + "'.");
            }
        }

        getServer().getPluginManager().registerEvents(new PlayerEventListener(teleports, dataStore), this);
        getServer().getPluginManager().registerEvents(settingsGui, this);
        getServer().getScheduler().runTaskTimer(this, () -> {
            for (var expired : requests.purgeExpired()) {
                String receiverName = Bukkit.getOfflinePlayer(expired.receiver()).getName();
                if (receiverName == null) {
                    receiverName = expired.receiver().toString();
                }
                String senderName = Bukkit.getOfflinePlayer(expired.sender()).getName();
                if (senderName == null) {
                    senderName = expired.sender().toString();
                }
                Player sender = Bukkit.getPlayer(expired.sender());
                if (sender != null) {
                    sender.sendMessage(messages.get("prefix")
                            + messages.format("request_sender_expired", Map.of("target", receiverName)));
                }
                Player receiver = Bukkit.getPlayer(expired.receiver());
                if (receiver != null) {
                    receiver.sendMessage(messages.get("prefix")
                            + messages.format("request_receiver_expired", Map.of("player", senderName)));
                }
            }
        }, 20L, 20L);
    }

    public void reloadAll() {
        reloadConfig();
        bootstrap();
    }

    private void ensureConfigDefaults() {
        var cfg = getConfig();
        boolean changed = false;
        changed |= migrateLegacySpawnSection(cfg);
        changed |= ensureDefault(cfg, "settings.features.enabled", true);
        changed |= ensureDefault(cfg, "settings.features.tpa", true);
        changed |= ensureDefault(cfg, "settings.features.tpahere", true);
        changed |= ensureDefault(cfg, "settings.features.homes", true);
        changed |= ensureDefault(cfg, "settings.features.rtp", true);
        changed |= ensureDefault(cfg, "settings.features.tpaback", true);
        changed |= ensureDefault(cfg, "settings.rtp.rtp_to_overworld", false);
        changed |= ensureDefault(cfg, "settings.rtp.blacklisted_worlds", java.util.List.of());
        changed |= ensureDefault(cfg, "settings.rtp.overworld_name", "world");
        changed |= ensureDefault(cfg, "settings.dimension_restrictions.disable_rtp.overworld", false);
        changed |= ensureDefault(cfg, "settings.dimension_restrictions.disable_rtp.nether", false);
        changed |= ensureDefault(cfg, "settings.dimension_restrictions.disable_rtp.end", false);
        changed |= ensureDefault(cfg, "settings.dimension_restrictions.disable_teleport.overworld", false);
        changed |= ensureDefault(cfg, "settings.dimension_restrictions.disable_teleport.nether", false);
        changed |= ensureDefault(cfg, "settings.dimension_restrictions.disable_teleport.end", false);
        changed |= ensureDefault(cfg, "settings.spawn.world", "world");
        changed |= ensureDefault(cfg, "settings.spawn.x", 0.0);
        changed |= ensureDefault(cfg, "settings.spawn.y", 100.0);
        changed |= ensureDefault(cfg, "settings.spawn.z", 0.0);
        changed |= ensureDefault(cfg, "settings.spawn.yaw", 0.0f);
        changed |= ensureDefault(cfg, "settings.spawn.pitch", 0.0f);
        changed |= ensureDefault(cfg, "settings.spawn.enabled", true);
        changed |= ensureDefault(cfg, "settings.costs.currency.tpa", 0.0);
        changed |= ensureDefault(cfg, "settings.costs.currency.tpahere", 0.0);
        changed |= ensureDefault(cfg, "settings.costs.currency.home", 0.0);
        changed |= ensureDefault(cfg, "settings.costs.currency.rtp", 0.0);
        changed |= ensureDefault(cfg, "settings.costs.currency.rtp.overworld", 0.0);
        changed |= ensureDefault(cfg, "settings.costs.currency.rtp.nether", 0.0);
        changed |= ensureDefault(cfg, "settings.costs.currency.rtp.end", 0.0);
        changed |= ensureDefault(cfg, "settings.costs.currency.spawn", 0.0);
        changed |= ensureDefault(cfg, "settings.costs.xp_levels.back", 0);
        changed |= ensureDefault(cfg, "settings.costs.item.back", 0);
        changed |= ensureDefault(cfg, "settings.costs.currency.back", 0.0);

        // Migrate accidental string booleans (e.g. rtp_to_overworld: "true") into real booleans.
        changed |= coerceStringBoolean(cfg, "settings.rtp.rtp_to_overworld");

        if (changed) {
            saveConfig();
        }
    }

    private boolean migrateLegacySpawnSection(FileConfiguration cfg) {
        String legacy = "settings.costs.spawn";
        if (!cfg.isConfigurationSection(legacy)) {
            return false;
        }
        boolean changed = false;
        for (String key : new String[] { "enabled", "x", "y", "z", "yaw", "pitch", "world" }) {
            String legacyPath = legacy + "." + key;
            String modernPath = "settings.spawn." + key;
            if (!cfg.contains(legacyPath)) {
                continue;
            }
            Object legacyValue = cfg.get(legacyPath);
            if (!cfg.contains(modernPath)) {
                cfg.set(modernPath, legacyValue);
                changed = true;
                continue;
            }
        }
        return changed;
    }

    private boolean coerceStringBoolean(FileConfiguration cfg, String path) {
        if (!cfg.isString(path)) {
            return false;
        }
        String raw = cfg.getString(path);
        if (raw == null) {
            return false;
        }
        String s = raw.trim();
        if (!s.equalsIgnoreCase("true") && !s.equalsIgnoreCase("false")) {
            return false;
        }
        cfg.set(path, Boolean.parseBoolean(s));
        return true;
    }

    private boolean ensureDefault(org.bukkit.configuration.file.FileConfiguration cfg, String path, Object value) {
        if (cfg.contains(path)) {
            return false;
        }
        cfg.set(path, value);
        return true;
    }

    public XmlMessages messages() {
        return messages;
    }

    public YatpaConfig configModel() {
        return configModel;
    }

    public DataStore dataStore() {
        return dataStore;
    }

    public RequestService requests() {
        return requests;
    }

    public TeleportService teleports() {
        return teleports;
    }

    public TeleportLogService teleportLog() {
        return teleportLog;
    }

    private Economy setupEconomy() {
        RegisteredServiceProvider<Economy> registration = getServer().getServicesManager().getRegistration(Economy.class);
        if (registration == null) {
            if (configModel.costsEnabled() && configModel.costMode() == YatpaConfig.CostMode.CURRENCY) {
                getLogger().warning("Currency costs are enabled but no Vault economy provider was found.");
                getLogger().warning("Install Vault + an economy plugin (for example EssentialsX Economy).");
            }
            return null;
        }
        return registration.getProvider();
    }
}
