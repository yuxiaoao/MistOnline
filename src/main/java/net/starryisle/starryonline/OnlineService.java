package net.starryisle.starryonline;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.WeekFields;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

/** Counts online seconds and pays hourly, daily and weekly rewards. */
public final class OnlineService {

    private record Tier(long seconds, int coins) {
        String label() {
            return durationLabel(seconds);
        }
    }

    private record Upcoming(String label, long seconds, int coins) {
    }

    private final JavaPlugin plugin;
    private final DataStore store;
    private final ZoneId zone;
    private final List<Tier> dailyTiers;
    private final List<Tier> weeklyTiers;
    private final long hourlySeconds;
    private final int hourlyCoins;
    private final String rewardCommand;
    private final String hourlyMessage;
    private final String dailyMessage;
    private final String weeklyMessage;
    private final String failedMessage;
    private final Map<UUID, OnlineData> players = new HashMap<>();
    /** 结算任务的调度间隔（tick）。累计按时长计算，间隔只影响结算粒度。 */
    private final long tickTicks;

    /** 日期/周 key 缓存：一天才变一次，没必要每个 tick 都算。 */
    private static final long KEY_REFRESH_MILLIS = 60_000L;
    private String cachedDailyKey;
    private String cachedWeeklyKey;
    private long nextKeyRefreshMillis;

    private BukkitTask tickTask;
    private BukkitTask saveTask;
    private BukkitTask cleanupTask;

    public OnlineService(JavaPlugin plugin) {
        this.plugin = plugin;
        this.store = new DataStore(plugin);
        this.zone = parseZone(plugin.getConfig().getString("timezone", "Asia/Shanghai"));
        this.dailyTiers = parseTiers("daily.tiers");
        this.weeklyTiers = parseTiers("weekly.tiers");
        long tickSeconds = Math.max(1L, Math.min(300L,
                plugin.getConfig().getLong("tick-seconds", 5L)));
        this.tickTicks = tickSeconds * 20L;
        long configuredSeconds = plugin.getConfig().getLong("hourly.seconds", 0L);
        if (configuredSeconds <= 0L) {
            configuredSeconds = Math.max(1L,
                    plugin.getConfig().getLong("hourly.minutes", 60L)) * 60L;
        }
        this.hourlySeconds = Math.max(1L, configuredSeconds);
        this.hourlyCoins = Math.max(0, plugin.getConfig().getInt("hourly.coins", 20));
        this.rewardCommand = plugin.getConfig().getString("reward-command",
                "money give %player% %amount%");
        this.hourlyMessage = plugin.getConfig().getString("messages.hourly",
                "&8[&6在线奖励&8] &a在线 &e{time} &a，&6+{coins} &a金币已到账");
        this.dailyMessage = plugin.getConfig().getString("messages.daily",
                "&8[&6在线奖励&8] &a每日在线 &e{time} &a达成，&6+{coins} &a金币已到账");
        this.weeklyMessage = plugin.getConfig().getString("messages.weekly",
                "&8[&6在线奖励&8] &a本周在线 &e{time} &a达成，&6+{coins} &a金币已到账");
        this.failedMessage = plugin.getConfig().getString("messages.reward-failed",
                "&c奖励发放失败，请联系管理员。");
    }

    public void start() {
        // 累计逻辑用的是时间戳差（now - lastAccrualMillis），不是 tick 计数，
        // 所以把调度间隔从 1 秒放宽到 tick-seconds 秒不会改变结算结果。
        tickTask = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, tickTicks, tickTicks);
        long interval = Math.max(30L, plugin.getConfig().getLong("save-interval-seconds", 300L)) * 20L;
        saveTask = Bukkit.getScheduler().runTaskTimer(plugin, this::saveAll, interval, interval);
        prune();
        cleanupTask = Bukkit.getScheduler().runTaskTimer(plugin, this::prune,
                20L * 60 * 60 * 6, 20L * 60 * 60 * 6);
        plugin.getLogger().info("MistOnline loaded: daily=" + dailyTiers.size()
                + " tiers, weekly=" + weeklyTiers.size()
                + " tiers, hourly=" + hourlyCoins + " coins/" + (hourlySeconds / 60) + "min");
    }

    public void stop() {
        if (tickTask != null) {
            tickTask.cancel();
        }
        if (saveTask != null) {
            saveTask.cancel();
        }
        if (cleanupTask != null) {
            cleanupTask.cancel();
        }
        saveAll();
        store.shutdown();
    }

    public void onJoin(Player player) {
        UUID uuid = player.getUniqueId();
        OnlineData state = store.load(uuid);
        LocalDate today = today();
        long now = System.currentTimeMillis();
        state.lastSeen = now;
        state.lastAccrualMillis = now;
        ensurePeriods(state, today.toString(), weekKey(today));
        migrateClaimed(state);
        players.put(uuid, state);
        store.save(uuid, state);
    }

    public void onQuit(Player player) {
        UUID uuid = player.getUniqueId();
        OnlineData state = players.remove(uuid);
        if (state != null) {
            state.lastSeen = System.currentTimeMillis();
            store.save(uuid, state);
        }
    }

    /** Sends the player's current daily/weekly/hourly progress. */
    public void showProgress(Player player) {
        OnlineData state = dataFor(player);
        LocalDate date = today();
        ensurePeriods(state, date.toString(), weekKey(date));
        long dailyMax = maxSeconds(dailyTiers);
        long weeklyMax = maxSeconds(weeklyTiers);
        int dailyClaimed = claimedCount(dailyTiers, state.dailyClaimedSeconds);
        int weeklyClaimed = claimedCount(weeklyTiers, state.weeklyClaimedSeconds);
        player.sendMessage(color("&8--==-- &6MistOnline &8==--"));
        player.sendMessage(color("&a每日在线 &7· &e" + duration(state.dailySeconds)
                + " &7/ &e" + duration(dailyMax)
                + " &7（已领 &e" + dailyClaimed + "/" + dailyTiers.size() + " &7档）"));
        player.sendMessage(color(nextTierLine("每日下一档", nextTier(dailyTiers, state.dailyClaimedSeconds, state.dailySeconds))));
        player.sendMessage(color("&a每周在线 &7· &e" + duration(state.weeklySeconds)
                + " &7/ &e" + duration(weeklyMax)
                + " &7（已领 &e" + weeklyClaimed + "/" + weeklyTiers.size() + " &7档）"));
        player.sendMessage(color(nextTierLine("每周下一档", nextTier(weeklyTiers, state.weeklyClaimedSeconds, state.weeklySeconds))));
        player.sendMessage(color("&a每小时奖励 &7· &e" + duration(state.hourlySeconds)
                + " &7/ &e" + duration(hourlySeconds)
                + " &7（&6+" + hourlyCoins + " &7金币）"));

        Upcoming next = nextReward(state);
        player.sendMessage(color("&a下个奖励 &7· &f" + next.label()
                + " &7还差 &e" + duration(next.seconds())
                + " &7（&6+" + next.coins() + " &7金币）"));
    }

    private static String nextTierLine(String label, String value) {
        if ("已全部领取".equals(value)) {
            return "&7" + label + " &8· &a已全部领取";
        }
        return "&7" + label + " &8· &e" + value;
    }

    /** The soonest unclaimed reward: hourly, daily or weekly. */
    private Upcoming nextReward(OnlineData state) {
        Upcoming best = new Upcoming("每小时奖励",
                Math.max(0L, hourlySeconds - state.hourlySeconds), hourlyCoins);
        Upcoming daily = nextDaily(state);
        if (daily != null && daily.seconds() <= best.seconds()) {
            best = daily;
        }
        Upcoming weekly = nextWeekly(state);
        if (weekly != null && weekly.seconds() < best.seconds()) {
            best = weekly;
        }
        return best;
    }

    private Upcoming nextDaily(OnlineData state) {
        for (Tier tier : dailyTiers) {
            if (tier.seconds() <= state.dailyClaimedSeconds) {
                continue;
            }
            long remain = Math.max(0L, tier.seconds() - state.dailySeconds);
            return new Upcoming("每日 " + tier.label(), remain, tier.coins());
        }
        return null;
    }

    private Upcoming nextWeekly(OnlineData state) {
        for (Tier tier : weeklyTiers) {
            if (tier.seconds() <= state.weeklyClaimedSeconds) {
                continue;
            }
            long remain = Math.max(0L, tier.seconds() - state.weeklySeconds);
            return new Upcoming("每周 " + tier.label(), remain, tier.coins());
        }
        return null;
    }

    /** Resets all counters and claimed tiers for one player. */
    public void reset(Player player) {
        OnlineData state = dataFor(player);
        LocalDate date = today();
        state.dailyKey = date.toString();
        state.weeklyKey = weekKey(date);
        state.dailySeconds = 0L;
        state.weeklySeconds = 0L;
        state.hourlySeconds = 0L;
        state.dailyClaimedSeconds = 0L;
        state.weeklyClaimedSeconds = 0L;
        state.dailyTier = 0;
        state.weeklyTier = 0;
        state.lastAccrualMillis = System.currentTimeMillis();
        state.lastSeen = state.lastAccrualMillis;
        state.nextRewardAttempt = 0L;
        store.save(player.getUniqueId(), state);
        player.sendMessage(color("&a你的在线奖励进度已重置。"));
    }

    private OnlineData dataFor(Player player) {
        UUID uuid = player.getUniqueId();
        OnlineData state = players.get(uuid);
        if (state == null) {
            state = store.load(uuid);
            state.lastAccrualMillis = System.currentTimeMillis();
            players.put(uuid, state);
        }
        return state;
    }

    private static long maxSeconds(List<Tier> tiers) {
        return tiers.isEmpty() ? 0L : tiers.get(tiers.size() - 1).seconds();
    }

    private static int claimedCount(List<Tier> tiers, long claimedSeconds) {
        int count = 0;
        for (Tier tier : tiers) {
            if (tier.seconds() <= claimedSeconds) {
                count++;
            }
        }
        return count;
    }

    /** Migrates old data that only stored a tier index. */
    private void migrateClaimed(OnlineData state) {
        if (state.dailyClaimedSeconds <= 0L && state.dailyTier > 0 && !dailyTiers.isEmpty()) {
            int index = Math.min(state.dailyTier - 1, dailyTiers.size() - 1);
            state.dailyClaimedSeconds = dailyTiers.get(index).seconds();
        }
        if (state.weeklyClaimedSeconds <= 0L && state.weeklyTier > 0 && !weeklyTiers.isEmpty()) {
            int index = Math.min(state.weeklyTier - 1, weeklyTiers.size() - 1);
            state.weeklyClaimedSeconds = weeklyTiers.get(index).seconds();
        }
        state.dailyTier = claimedCount(dailyTiers, state.dailyClaimedSeconds);
        state.weeklyTier = claimedCount(weeklyTiers, state.weeklyClaimedSeconds);
    }

    private static String nextTier(List<Tier> tiers, long claimedSeconds, long seconds) {
        for (Tier tier : tiers) {
            if (tier.seconds() <= claimedSeconds) {
                continue;
            }
            long remain = Math.max(0L, tier.seconds() - seconds);
            return tier.label() + "（还差 " + duration(remain) + "，+" + tier.coins() + " 金币）";
        }
        return "已全部领取";
    }

    private static String duration(long seconds) {
        long hours = seconds / 3600L;
        long minutes = (seconds % 3600L) / 60L;
        long secs = seconds % 60L;
        StringBuilder text = new StringBuilder();
        if (hours > 0) {
            text.append(hours).append("小时");
        }
        if (minutes > 0) {
            text.append(minutes).append("分钟");
        }
        if (secs > 0 || text.length() == 0) {
            text.append(secs).append("秒");
        }
        return text.toString();
    }

    /** Removes stale player data using the configured retention window. */
    public void prune() {
        store.prune(Math.max(0, plugin.getConfig().getInt("data-retention-days", 90)));
    }

    private void tick() {
        long now = System.currentTimeMillis();
        // 跨天/跨周最多晚 KEY_REFRESH_MILLIS 生效；省掉每 tick 的 LocalDate.now
        // 和 WeekFields 计算（实测占本插件 70% 以上的开销）。
        if (now >= nextKeyRefreshMillis) {
            LocalDate date = today();
            cachedDailyKey = date.toString();
            cachedWeeklyKey = weekKey(date);
            nextKeyRefreshMillis = now + KEY_REFRESH_MILLIS;
        }
        String dailyKey = cachedDailyKey;
        String weeklyKey = cachedWeeklyKey;

        for (Player player : Bukkit.getOnlinePlayers()) {
            UUID uuid = player.getUniqueId();
            OnlineData state = players.get(uuid);
            if (state == null) {
                state = store.load(uuid);
                state.lastAccrualMillis = now;
                players.put(uuid, state);
            }
            state.lastSeen = now;
            if (state.lastAccrualMillis <= 0L) {
                state.lastAccrualMillis = now;
                continue;
            }
            long deltaMillis = now - state.lastAccrualMillis;
            if (deltaMillis < 0L) {
                state.lastAccrualMillis = now;
                continue;
            }
            if (deltaMillis < 1000L) {
                continue;
            }
            long elapsedSeconds = deltaMillis / 1000L;
            state.lastAccrualMillis += elapsedSeconds * 1000L;
            ensurePeriods(state, dailyKey, weeklyKey);
            migrateClaimed(state);
            state.dailySeconds += elapsedSeconds;
            state.weeklySeconds += elapsedSeconds;
            state.hourlySeconds += elapsedSeconds;

            payHourly(player, state);
            payDaily(player, state);
            payWeekly(player, state);
        }
    }

    private void ensurePeriods(OnlineData state, String dailyKey, String weeklyKey) {
        if (!dailyKey.equals(state.dailyKey)) {
            state.dailyKey = dailyKey;
            state.dailySeconds = 0L;
            state.dailyClaimedSeconds = 0L;
            state.dailyTier = 0;
        }
        if (!weeklyKey.equals(state.weeklyKey)) {
            state.weeklyKey = weeklyKey;
            state.weeklySeconds = 0L;
            state.weeklyClaimedSeconds = 0L;
            state.weeklyTier = 0;
        }
    }

    private void payHourly(Player player, OnlineData state) {
        if (!canAttempt(state)) {
            return;
        }
        while (state.hourlySeconds >= hourlySeconds) {
            if (!award(player, hourlyCoins, hourlyMessage, hourlySeconds)) {
                markFailure(state);
                return;
            }
            state.hourlySeconds -= hourlySeconds;
        }
    }

    private void payDaily(Player player, OnlineData state) {
        if (!canAttempt(state)) {
            return;
        }
        for (Tier tier : dailyTiers) {
            if (tier.seconds() <= state.dailyClaimedSeconds) {
                continue;
            }
            if (state.dailySeconds < tier.seconds()) {
                break;
            }
            if (!award(player, tier.coins(), dailyMessage, tier.seconds())) {
                markFailure(state);
                return;
            }
            state.dailyClaimedSeconds = tier.seconds();
        }
        state.dailyTier = claimedCount(dailyTiers, state.dailyClaimedSeconds);
    }

    private void payWeekly(Player player, OnlineData state) {
        if (!canAttempt(state)) {
            return;
        }
        for (Tier tier : weeklyTiers) {
            if (tier.seconds() <= state.weeklyClaimedSeconds) {
                continue;
            }
            if (state.weeklySeconds < tier.seconds()) {
                break;
            }
            if (!award(player, tier.coins(), weeklyMessage, tier.seconds())) {
                markFailure(state);
                return;
            }
            state.weeklyClaimedSeconds = tier.seconds();
        }
        state.weeklyTier = claimedCount(weeklyTiers, state.weeklyClaimedSeconds);
    }

    private static boolean canAttempt(OnlineData state) {
        return System.currentTimeMillis() >= state.nextRewardAttempt;
    }

    private static void markFailure(OnlineData state) {
        state.nextRewardAttempt = System.currentTimeMillis() + 5_000L;
    }

    private boolean award(Player player, int coins, String template, long timeSeconds) {
        boolean paid = dispatch(player, coins);
        if (!paid) {
            player.sendMessage(color(failedMessage));
            return false;
        }
        String message = template
                .replace("{coins}", String.valueOf(coins))
                .replace("{time}", durationLabel(timeSeconds))
                .replace("{hours}", String.valueOf(timeSeconds / 3600L))
                .replace("{minutes}", String.valueOf(timeSeconds / 60L));
        player.sendMessage(color(message));
        return true;
    }

    private static String durationLabel(long seconds) {
        return duration(seconds);
    }

    private boolean dispatch(Player player, int coins) {
        if (rewardCommand == null || rewardCommand.isBlank()) {
            plugin.getLogger().warning("reward-command 为空，无法发放金币");
            return false;
        }
        String resolved = rewardCommand
                .replace("%player%", player.getName())
                .replace("%amount%", String.valueOf(coins));
        boolean paid = Bukkit.dispatchCommand(Bukkit.getConsoleSender(), resolved);
        if (!paid) {
            plugin.getLogger().warning("奖励命令执行失败: " + resolved);
        }
        return paid;
    }

    private void saveAll() {
        for (Map.Entry<UUID, OnlineData> entry : players.entrySet()) {
            store.save(entry.getKey(), entry.getValue());
        }
    }

    private List<Tier> parseTiers(String path) {
        List<Tier> tiers = new ArrayList<>();
        for (Map<?, ?> map : plugin.getConfig().getMapList(path)) {
            Object hours = map.get("hours");
            Object minutes = map.get("minutes");
            Object secondsValue = map.get("seconds");
            Object coins = map.get("coins");
            if (!(coins instanceof Number)) {
                plugin.getLogger().warning("忽略无效档位 " + path + ": " + map);
                continue;
            }
            long seconds;
            if (hours instanceof Number h && h.longValue() > 0L) {
                seconds = h.longValue() * 3600L;
            } else if (minutes instanceof Number m && m.longValue() > 0L) {
                seconds = m.longValue() * 60L;
            } else if (secondsValue instanceof Number s && s.longValue() > 0L) {
                seconds = s.longValue();
            } else {
                plugin.getLogger().warning("忽略无效档位 " + path + ": " + map);
                continue;
            }
            int c = ((Number) coins).intValue();
            if (c < 0) {
                plugin.getLogger().warning("忽略无效档位 " + path + ": " + map);
                continue;
            }
            tiers.add(new Tier(seconds, c));
        }
        tiers.sort(Comparator.comparingLong(Tier::seconds));
        if (tiers.isEmpty()) {
            plugin.getLogger().warning(path + " 没有有效档位，该周期不会发放档位奖励");
        }
        return tiers;
    }

    private static ZoneId parseZone(String name) {
        try {
            return ZoneId.of(name);
        } catch (Exception exception) {
            return ZoneId.of("Asia/Shanghai");
        }
    }

    private LocalDate today() {
        return LocalDate.now(zone);
    }

    private static String weekKey(LocalDate date) {
        WeekFields fields = WeekFields.ISO;
        int year = date.get(fields.weekBasedYear());
        int week = date.get(fields.weekOfWeekBasedYear());
        return year + "-W" + String.format("%02d", week);
    }

    private static String color(String text) {
        return ChatColor.translateAlternateColorCodes('&', text == null ? "" : text);
    }
}
