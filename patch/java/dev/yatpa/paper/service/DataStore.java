package dev.yatpa.paper.service;

import dev.yatpa.paper.data.HomeLocation;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.file.YamlConfiguration;

public class DataStore {
    private final File playersFile;
    private final File homesFile;
    private final File offlineFile;
    private YamlConfiguration players;
    private YamlConfiguration homes;
    private YamlConfiguration offline;

    public DataStore(File dataFolder) {
        File db = new File(dataFolder, "data");
        if (!db.exists() && !db.mkdirs()) {
            throw new IllegalStateException("Could not create data directory");
        }
        this.playersFile = new File(db, "players.yml");
        this.homesFile = new File(db, "homes.yml");
        this.offlineFile = new File(db, "offline.yml");
        load();
    }

    public final void load() {
        this.players = YamlConfiguration.loadConfiguration(playersFile);
        this.homes = YamlConfiguration.loadConfiguration(homesFile);
        this.offline = YamlConfiguration.loadConfiguration(offlineFile);
    }

    public void saveAll() {
        save(players, playersFile);
        save(homes, homesFile);
        save(offline, offlineFile);
    }

    private void save(YamlConfiguration cfg, File file) {
        try {
            cfg.save(file);
        } catch (IOException e) {
            throw new IllegalStateException("Failed saving " + file.getName(), e);
        }
    }

    private String playerPath(UUID uuid) {
        return "players." + uuid;
    }

    public boolean acceptingRequests(UUID uuid) {
        return players.getBoolean(playerPath(uuid) + ".accepting", true);
    }

    public void setAcceptingRequests(UUID uuid, boolean accepting) {
        players.set(playerPath(uuid) + ".accepting", accepting);
        save(players, playersFile);
    }

    public Set<UUID> blocked(UUID uuid) {
        List<String> raw = players.getStringList(playerPath(uuid) + ".blocked");
        Set<UUID> out = new HashSet<>();
        for (String value : raw) {
            try {
                out.add(UUID.fromString(value));
            } catch (IllegalArgumentException ignored) {
            }
        }
        return out;
    }

    public boolean isBlocked(UUID owner, UUID other) {
        return blocked(owner).contains(other);
    }

    public void block(UUID owner, UUID target) {
        Set<UUID> blocked = blocked(owner);
        blocked.add(target);
        storeBlocked(owner, blocked);
    }

    public void unblock(UUID owner, UUID target) {
        Set<UUID> blocked = blocked(owner);
        blocked.remove(target);
        storeBlocked(owner, blocked);
    }

    private void storeBlocked(UUID owner, Set<UUID> blocked) {
        List<String> list = blocked.stream().map(UUID::toString).toList();
        players.set(playerPath(owner) + ".blocked", list);
        save(players, playersFile);
    }

    public int homeLimit(UUID uuid, int defaultLimit) {
        return players.getInt(playerPath(uuid) + ".home_limit", defaultLimit);
    }

    public void setHomeLimit(UUID uuid, int limit) {
        players.set(playerPath(uuid) + ".home_limit", limit);
        save(players, playersFile);
    }

    public String defaultHome(UUID uuid) {
        return players.getString(playerPath(uuid) + ".default_home");
    }

    public boolean hasDefaultHome(UUID uuid) {
        return players.contains(playerPath(uuid) + ".default_home");
    }

    public void setDefaultHome(UUID uuid, String homeName) {
        players.set(playerPath(uuid) + ".default_home", homeName.toLowerCase());
        save(players, playersFile);
    }

    public Map<String, HomeLocation> homes(UUID uuid) {
        String base = "players." + uuid + ".homes";
        if (!homes.isConfigurationSection(base)) {
            return Collections.emptyMap();
        }

        Map<String, HomeLocation> out = new HashMap<>();
        for (String key : homes.getConfigurationSection(base).getKeys(false)) {
            String path = base + "." + key;
            String world = homes.getString(path + ".world");
            if (world == null) {
                continue;
            }
            out.put(key.toLowerCase(), new HomeLocation(
                world,
                homes.getDouble(path + ".x"),
                homes.getDouble(path + ".y"),
                homes.getDouble(path + ".z"),
                (float) homes.getDouble(path + ".yaw"),
                (float) homes.getDouble(path + ".pitch")
            ));
        }
        return out;
    }

    public HomeLocation home(UUID uuid, String name) {
        return homes(uuid).get(name.toLowerCase());
    }

    public void setHome(UUID uuid, String name, Location location) {
        String path = "players." + uuid + ".homes." + name.toLowerCase();
        HomeLocation home = HomeLocation.fromLocation(location);
        homes.set(path + ".world", home.world());
        homes.set(path + ".x", home.x());
        homes.set(path + ".y", home.y());
        homes.set(path + ".z", home.z());
        homes.set(path + ".yaw", home.yaw());
        homes.set(path + ".pitch", home.pitch());
        save(homes, homesFile);
    }

    public boolean deleteHome(UUID uuid, String name) {
        String path = "players." + uuid + ".homes." + name.toLowerCase();
        if (homes.get(path) == null) {
            return false;
        }
        homes.set(path, null);
        save(homes, homesFile);
        return true;
    }

    public void setOfflineLocation(String name, Location location) {
        String path = "players." + name.toLowerCase();
        offline.set(path + ".world", location.getWorld().getName());
        offline.set(path + ".x", location.getX());
        offline.set(path + ".y", location.getY());
        offline.set(path + ".z", location.getZ());
        offline.set(path + ".yaw", location.getYaw());
        offline.set(path + ".pitch", location.getPitch());
        save(offline, offlineFile);
    }

    public Location offlineLocation(String name) {
        String path = "players." + name.toLowerCase();
        if (offline.get(path) == null) {
            return null;
        }
        World world = Bukkit.getWorld(offline.getString(path + ".world", ""));
        if (world == null) {
            return null;
        }
        return new Location(
            world,
            offline.getDouble(path + ".x"),
            offline.getDouble(path + ".y"),
            offline.getDouble(path + ".z"),
            (float) offline.getDouble(path + ".yaw"),
            (float) offline.getDouble(path + ".pitch")
        );
    }

    public List<String> homeNames(UUID uuid) {
        return new ArrayList<>(homes(uuid).keySet());
    }
}
