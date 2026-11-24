package net.starryisle.starryonline;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Per-player data under players/&lt;uuid&gt;.yml.
 *
 * <p>The main thread snapshots primitive counters; one IO thread serialises and
 * writes that snapshot through a temp file + atomic replace. The old data.yml
 * is migrated to per-player files on startup and then renamed, so pruning a
 * player file cannot resurrect stale data from the legacy file.
 */
public final class DataStore {

    private final JavaPlugin plugin;
    private final File playersDir;
    private final File legacyFile;
    private final ExecutorService writer = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "MistOnline-IO");
        thread.setDaemon(true);
        return thread;
    });
    private YamlConfiguration legacy;

    public DataStore(JavaPlugin plugin) {
        this.plugin = plugin;
        this.playersDir = new File(plugin.getDataFolder(), "players");
        this.legacyFile = new File(plugin.getDataFolder(), "data.yml");
        this.legacy = legacyFile.isFile()
                ? YamlConfiguration.loadConfiguration(legacyFile)
                : null;
        migrateLegacy();
    }

    /** Copies every entry in data.yml to players/&lt;uuid&gt;.yml, then retires it. */
    private void migrateLegacy() {
        if (legacy == null) {
            return;
        }
        ConfigurationSection players = legacy.getConfigurationSection("players");
        if (players == null) {
            retireLegacy();
            return;
        }
        int migrated = 0;
        int skipped = 0;
        for (String key : new ArrayList<>(players.getKeys(false))) {
            UUID uuid;
            try {
                uuid = UUID.fromString(key);
            } catch (IllegalArgumentException ignored) {
                continue;
            }
            if (Files.isRegularFile(playerFile(uuid))) {
                skipped++;
                continue;
            }
            OnlineData state = fromConfig(legacy, uuid);
            if (!writeSnapshot(uuid, Snapshot.of(state))) {
                plugin.getLogger().warning("旧 data.yml 迁移未完成，保留原文件以便下次重试");
                return;
            }
            migrated++;
        }
        retireLegacy();
        plugin.getLogger().info("已从 data.yml 迁移 " + migrated + " 名玩家数据"
                + (skipped > 0 ? "，跳过已有文件 " + skipped + " 名" : ""));
    }

    private void retireLegacy() {
        try {
            Path target = legacyFile.toPath();
            Path done = target.resolveSibling("data.yml.migrated");
            Files.move(target, done, StandardCopyOption.REPLACE_EXISTING);
            legacy = null;
        } catch (IOException exception) {
            plugin.getLogger().severe("无法重命名旧 data.yml，保留回退数据: "
                    + exception.getMessage());
        }
    }

    public OnlineData load(UUID uuid) {
        Path file = playerFile(uuid);
        if (Files.isRegularFile(file)) {
            YamlConfiguration config = YamlConfiguration.loadConfiguration(file.toFile());
            return fromConfig(config, uuid);
        }
        if (legacy != null) {
            String path = "players." + uuid;
            if (legacy.isConfigurationSection(path)) {
                return fromConfig(legacy, uuid);
            }
        }
        return new OnlineData();
    }

    /** Copies the mutable state to an immutable snapshot on the caller thread. */
    public void save(UUID uuid, OnlineData state) {
        Snapshot snapshot = Snapshot.of(state);
        writer.execute(() -> writePlayer(uuid, snapshot));
    }

    /** Deletes player files whose last join is older than {@code days}; 0 disables. */
    public void prune(int days) {
        if (days <= 0) {
            return;
        }
        Set<UUID> online = new HashSet<>();
        for (var player : Bukkit.getOnlinePlayers()) {
            online.add(player.getUniqueId());
        }
        writer.execute(() -> pruneFiles(days, online));
    }

    public void shutdown() {
        writer.shutdown();
        try {
            if (!writer.awaitTermination(10, TimeUnit.SECONDS)) {
                plugin.getLogger().warning("在线奖励数据保存超时");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    private void writePlayer(UUID uuid, Snapshot state) {
        writeSnapshot(uuid, state);
    }

    private boolean writeSnapshot(UUID uuid, Snapshot state) {
        Path target = playerFile(uuid);
        Path temp = target.resolveSibling(target.getFileName() + ".tmp");
        try {
            Files.createDirectories(playersDir.toPath());
            YamlConfiguration config = new YamlConfiguration();
            config.set("daily-key", state.dailyKey());
            config.set("weekly-key", state.weeklyKey());
            config.set("daily-seconds", state.dailySeconds());
            config.set("weekly-seconds", state.weeklySeconds());
            config.set("hourly-seconds", state.hourlySeconds());
            config.set("daily-claimed-seconds", state.dailyClaimedSeconds());
            config.set("weekly-claimed-seconds", state.weeklyClaimedSeconds());
            config.set("last-seen", state.lastSeen());
            config.set("daily-tier", state.dailyTier());
            config.set("weekly-tier", state.weeklyTier());

            Files.writeString(temp, config.saveToString(), StandardCharsets.UTF_8);
            try {
                Files.move(temp, target,
                        StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
            return true;
        } catch (Exception exception) {
            try {
                Files.deleteIfExists(temp);
            } catch (Exception ignored) {
                // Best effort cleanup.
            }
            plugin.getLogger().severe("保存玩家数据失败 " + uuid + ": " + exception.getMessage());
            return false;
        }
    }

    private void pruneFiles(int days, Set<UUID> online) {
        long cutoff = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(days);
        int removed = 0;
        if (!Files.isDirectory(playersDir.toPath())) {
            return;
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(playersDir.toPath(), "*.yml")) {
            for (Path path : stream) {
                String name = path.getFileName().toString();
                UUID uuid;
                try {
                    uuid = UUID.fromString(name.substring(0, name.length() - 4));
                } catch (IllegalArgumentException ignored) {
                    continue;
                }
                if (online.contains(uuid)) {
                    continue;
                }
                YamlConfiguration config = YamlConfiguration.loadConfiguration(path.toFile());
                long lastSeen = Math.max(0L, config.getLong("last-seen", 0L));
                if (lastSeen > 0L && lastSeen < cutoff) {
                    try {
                        Files.deleteIfExists(path);
                        removed++;
                    } catch (IOException exception) {
                        plugin.getLogger().warning("删除过期数据失败 " + name + ": "
                                + exception.getMessage());
                    }
                }
            }
        } catch (IOException exception) {
            plugin.getLogger().warning("清理玩家数据失败: " + exception.getMessage());
        }
        if (removed > 0) {
            plugin.getLogger().info("已清理 " + removed + " 名超过 " + days + " 天未上线的在线奖励数据");
        }
    }

    private Path playerFile(UUID uuid) {
        return new File(playersDir, uuid + ".yml").toPath();
    }

    private static OnlineData fromConfig(YamlConfiguration config, UUID uuid) {
        String path = "players." + uuid;
        String prefix = config.isConfigurationSection(path) ? path + "." : "";
        OnlineData state = new OnlineData();
        state.dailyKey = config.getString(prefix + "daily-key", "");
        state.weeklyKey = config.getString(prefix + "weekly-key", "");
        state.dailySeconds = Math.max(0L, config.getLong(prefix + "daily-seconds", 0L));
        state.weeklySeconds = Math.max(0L, config.getLong(prefix + "weekly-seconds", 0L));
        state.hourlySeconds = Math.max(0L, config.getLong(prefix + "hourly-seconds", 0L));
        state.dailyClaimedSeconds = Math.max(0L,
                config.getLong(prefix + "daily-claimed-seconds", 0L));
        state.weeklyClaimedSeconds = Math.max(0L,
                config.getLong(prefix + "weekly-claimed-seconds", 0L));
        state.lastSeen = Math.max(0L, config.getLong(prefix + "last-seen", 0L));
        state.dailyTier = Math.max(0, config.getInt(prefix + "daily-tier", 0));
        state.weeklyTier = Math.max(0, config.getInt(prefix + "weekly-tier", 0));
        return state;
    }

    private record Snapshot(String dailyKey, String weeklyKey, long dailySeconds,
                            long weeklySeconds, long hourlySeconds,
                            long dailyClaimedSeconds, long weeklyClaimedSeconds,
                            long lastSeen, int dailyTier, int weeklyTier) {

        static Snapshot of(OnlineData state) {
            return new Snapshot(state.dailyKey, state.weeklyKey, state.dailySeconds,
                    state.weeklySeconds, state.hourlySeconds,
                    state.dailyClaimedSeconds, state.weeklyClaimedSeconds,
                    state.lastSeen, state.dailyTier, state.weeklyTier);
        }
    }
}
