package io.wifi.habitrainsurvive.cmi;

import io.wifi.habitrainsurvive.util.GlobalPosInfo;

import java.util.*;

public class PlayerProfileData {
    public UUID uuid;
    public String lastKnownName;
    public String nickname;
    public Map<String, GlobalPosInfo> homes = new HashMap<>();
    public int purchasedHomeSlots = 0; // 永久购买次数，同时决定额外额度与下次价格
    public GlobalPosInfo lastLocation;
    public GlobalPosInfo preJailLocation;
    public boolean godMode = false;
    public boolean vanished = false;
    public boolean afk = false;
    public boolean tpaToggle = true; // true = 允许接收 TPA 请求
    public boolean payToggle = true; // true = 允许接收转账
    public boolean frozen = false;
    public long mutedUntil = 0L;
    public String muteReason = "";
    public long bannedUntil = 0L;
    public String banReason = "";
    public long jailedUntil = 0L;
    public String jailName = "";
    public String jailReason = "";
    public long lastSeenTimestamp = System.currentTimeMillis();
    public long lastRtpTimestamp = 0L;
    public long totalPlaytimeSeconds = 0L;
    public long lastDailyClaimDay = -1L;
    public int dailyStreak = 0;
    public List<String> warnings = new ArrayList<>();
    public List<String> mailbox = new ArrayList<>();
    public Set<UUID> ignoredPlayers = new HashSet<>();
    public Map<String, Long> kitCooldowns = new HashMap<>();

    public PlayerProfileData() {
    }

    public PlayerProfileData(UUID uuid, String name) {
        this.uuid = uuid;
        this.lastKnownName = name;
        this.lastSeenTimestamp = System.currentTimeMillis();
    }

    public boolean isMuted() {
        return System.currentTimeMillis() < mutedUntil;
    }

    public boolean isBanned() {
        return System.currentTimeMillis() < bannedUntil;
    }

    public boolean isJailed() {
        return System.currentTimeMillis() < jailedUntil;
    }
}
