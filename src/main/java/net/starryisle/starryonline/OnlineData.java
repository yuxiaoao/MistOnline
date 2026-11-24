package net.starryisle.starryonline;

/** Per-player online counters and claimed-tier indexes. */
public final class OnlineData {

    String dailyKey = "";
    String weeklyKey = "";
    long dailySeconds;
    long weeklySeconds;
    long hourlySeconds;
    long dailyClaimedSeconds;
    long weeklyClaimedSeconds;
    long lastSeen;
    long lastAccrualMillis;
    long nextRewardAttempt;
    int dailyTier;
    int weeklyTier;
}
