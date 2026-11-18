package io.wifi.habitrainsurvive.onboarding;

import io.wifi.habitrainsurvive.gift.GiftPackage;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Small server-thread outbox transaction, kept free of Minecraft so crash/failure paths can be tested. */
final class RewardDelivery {
    @FunctionalInterface interface Write { void run() throws IOException; }
    @FunctionalInterface interface ReadIds { Set<String> read() throws IOException; }

    private RewardDelivery() { }

    static void commit(OnboardingState.Progress progress, List<GiftPackage> box,
                       Write saveGifts, ReadIds persistedIds, Write saveState) throws IOException {
        if (progress.rewardDelivered || progress.pendingGifts.isEmpty()) return;
        List<GiftPackage> pending = progress.pendingGifts;
        Set<String> ids = new HashSet<>();
        for (GiftPackage gift : box) ids.add(gift.id);
        for (GiftPackage gift : pending) if (ids.add(gift.id)) box.add(gift);
        try {
            saveGifts.run();
            Set<String> persisted = persistedIds.read();
            if (pending.stream().anyMatch(gift -> !persisted.contains(gift.id))) throw new IOException("GiftManager did not persist all onboarding parcels");
            progress.pendingGifts = new ArrayList<>();
            progress.rewardDelivered = true;
            saveState.run();
        } catch (IOException | RuntimeException e) {
            progress.pendingGifts = pending;
            progress.rewardDelivered = false;
            // No claims can interleave this transaction. Hide uncommitted parcels even if present in an older disk snapshot.
            Set<String> pendingIds = new HashSet<>();
            pending.forEach(gift -> pendingIds.add(gift.id));
            box.removeIf(gift -> pendingIds.contains(gift.id));
            throw e;
        }
    }
}
