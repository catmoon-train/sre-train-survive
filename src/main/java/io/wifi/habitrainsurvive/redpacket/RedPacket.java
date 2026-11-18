package io.wifi.habitrainsurvive.redpacket;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.random.RandomGenerator;

/** One persisted 拼手气 packet: random shares, each claimant receives at most one share. */
public final class RedPacket {
    public String id;
    public String senderUuid;
    public String senderName;
    public long totalCents;
    public long remainingCents;
    public int count;
    public int remainingCount;
    public long createdAt;
    public long expiresAt;
    public String message = "";
    public Map<String, Long> claims = new LinkedHashMap<>();

    public RedPacket() {}

    public RedPacket(String id, UUID sender, String senderName, long totalCents, int count,
                     long createdAt, long expiresAt, String message) {
        this.id = id;
        this.senderUuid = sender.toString();
        this.senderName = senderName;
        this.totalCents = totalCents;
        this.remainingCents = totalCents;
        this.count = count;
        this.remainingCount = count;
        this.createdAt = createdAt;
        this.expiresAt = expiresAt;
        this.message = message;
    }

    public UUID senderId() {
        try {
            return senderUuid == null ? null : UUID.fromString(senderUuid);
        } catch (IllegalArgumentException invalid) {
            return null;
        }
    }

    public boolean open(long now) {
        return remainingCount > 0 && remainingCents > 0 && now < expiresAt;
    }

    public boolean expired(long now) {
        return now >= expiresAt;
    }

    public boolean claimedBy(UUID player) {
        return player != null && claims.containsKey(player.toString());
    }

    /** Mutates the packet and returns the share in cents; 0 means the player may not claim. */
    public long claim(UUID player, long now, RandomGenerator random) {
        if (player == null || !open(now) || claimedBy(player)) return 0;
        long share = drawShare(remainingCents, remainingCount, random);
        remainingCents -= share;
        remainingCount--;
        claims.put(player.toString(), share);
        return share;
    }

    /** Stops further claims; the caller decides whether the remainder is refunded. */
    public void close() {
        remainingCents = 0;
        remainingCount = 0;
    }

    /** Returns false when the stored entry is unusable and must be discarded. */
    public boolean normalize() {
        if (id == null || id.isBlank() || senderId() == null || totalCents <= 0 || count <= 0) return false;
        if (message == null) message = "";
        if (claims == null) claims = new LinkedHashMap<>();
        remainingCents = Math.max(0, Math.min(remainingCents, totalCents));
        remainingCount = Math.max(0, Math.min(remainingCount, count));
        if (remainingCents == 0 || remainingCount == 0) {
            remainingCents = 0;
            remainingCount = 0;
        }
        // Every remaining share needs at least one cent.
        if (remainingCents < remainingCount) remainingCount = (int) remainingCents;
        if (remainingCount == 0) remainingCents = 0;
        return true;
    }
    /**
     * 拼手气 split: reserve one cent for every later claimant, cap this share near twice the
     * running average, and let the last claimant take the exact remainder.
     */
    static long drawShare(long remainingCents, int remainingCount, RandomGenerator random) {
        if (remainingCount <= 1) return remainingCents;
        long reserved = remainingCount - 1L;
        long max = remainingCents - reserved;
        if (max <= 1) return 1;
        long doubledAverage = Math.max(2, (remainingCents * 2) / remainingCount);
        long cap = Math.min(max, doubledAverage);
        long share = 1 + (long) Math.floor(random.nextDouble() * cap);
        return Math.max(1, Math.min(share, max));
    }
}
