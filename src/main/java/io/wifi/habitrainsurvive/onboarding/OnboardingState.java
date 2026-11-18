package io.wifi.habitrainsurvive.onboarding;

import io.wifi.habitrainsurvive.gift.GiftPackage;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class OnboardingState {
    public int version = 1;
    public Map<UUID, Progress> players = new HashMap<>();

    public static final class Progress {
        public boolean existingPlayer;
        public boolean welcomed;
        public boolean rewardDelivered;
        public String rewardKit = "";
        public Set<String> readSteps = new HashSet<>();
        /** Durable outbox, removed only after both gift persistence and state commit succeed. */
        public List<GiftPackage> pendingGifts = new ArrayList<>();
    }

    public void seedExisting(Set<UUID> existing) {
        for (UUID uuid : existing) {
            if (uuid == null) throw new IllegalArgumentException("existingPlayers contains null");
            players.computeIfAbsent(uuid, ignored -> {
                Progress progress = new Progress();
                progress.existingPlayer = true;
                progress.welcomed = true;
                progress.rewardDelivered = true;
                return progress;
            });
        }
    }

    public void validate() {
        if (version != 1 || players == null) throw new IllegalArgumentException("onboarding state: invalid version or players");
        for (var entry : players.entrySet()) {
            Progress p = entry.getValue();
            if (entry.getKey() == null || p == null || p.readSteps == null || p.readSteps.contains(null)
                    || p.pendingGifts == null || p.rewardKit == null || p.pendingGifts.size() > 4096
                    || (p.rewardDelivered && !p.pendingGifts.isEmpty())) {
                throw new IllegalArgumentException("onboarding state: invalid progress for " + entry.getKey());
            }
            Set<String> ids = new HashSet<>();
            for (GiftPackage gift : p.pendingGifts) {
                if (gift == null || gift.id == null || !ids.add(gift.id) || gift.serializedItem == null
                        || gift.serializedItem.isBlank() || gift.itemCount != 1 || gift.attachedCoins != 0) {
                    throw new IllegalArgumentException("onboarding state: invalid pending gift for " + entry.getKey());
                }
            }
        }
    }
}
