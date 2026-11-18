package io.wifi.habitrainsurvive.onboarding;

import io.wifi.habitrainsurvive.gift.GiftPackage;
import io.wifi.habitrainsurvive.presentation.ConfigFiles;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class OnboardingStateTest {
    @TempDir Path directory;

    @Test void migrationExcludesOldPlayersWithoutOverwritingPendingNewcomers() {
        UUID old = UUID.randomUUID();
        UUID newcomer = UUID.randomUUID();
        OnboardingState state = new OnboardingState();
        var pending = new OnboardingState.Progress();
        pending.welcomed = true;
        state.players.put(newcomer, pending);
        state.seedExisting(Set.of(old, newcomer));
        assertTrue(state.players.get(old).existingPlayer);
        assertTrue(state.players.get(old).rewardDelivered);
        assertTrue(state.players.get(old).welcomed);
        assertSame(pending, state.players.get(newcomer));
        assertFalse(pending.existingPlayer);
        assertFalse(pending.rewardDelivered);
        state.validate();
    }

    @Test void outboxAndReadProgressSurviveUtf8RoundTrip() throws Exception {
        UUID uuid = UUID.randomUUID();
        OnboardingState state = new OnboardingState();
        var progress = pending();
        progress.readSteps.add("world");
        state.players.put(uuid, progress);
        Path file = directory.resolve("onboarding-state.json");
        ConfigFiles.write(file, state);
        var restored = ConfigFiles.read(file, OnboardingState.class);
        restored.validate();
        assertEquals("新手向导", restored.players.get(uuid).pendingGifts.getFirst().senderName);
        assertTrue(restored.players.get(uuid).readSteps.contains("world"));
        assertFalse(restored.players.get(uuid).rewardDelivered);
    }

    @Test void repeatedCommitDoesNotReissueClaimedGifts() throws Exception {
        var progress = pending();
        var box = new ArrayList<GiftPackage>();
        Set<String> disk = new HashSet<>();
        RewardDelivery.commit(progress, box, () -> box.forEach(g -> disk.add(g.id)), () -> disk, () -> { });
        assertTrue(progress.rewardDelivered);
        assertTrue(progress.pendingGifts.isEmpty());
        assertEquals(1, box.size());
        box.clear(); // A successful GiftManager claim.
        RewardDelivery.commit(progress, box, () -> fail("must not write gifts again"), () -> disk, () -> fail("must not change state"));
        assertTrue(box.isEmpty());
    }

    @Test void crashAfterGiftSaveReconcilesStableIdsWithoutDuplication() throws Exception {
        var progress = pending();
        var box = new ArrayList<GiftPackage>(progress.pendingGifts); // Gift file committed before crash.
        RewardDelivery.commit(progress, box, () -> { }, () -> Set.of("onboarding:test:0"), () -> { });
        assertEquals(1, box.size());
        assertTrue(progress.rewardDelivered);
    }

    @Test void failedStateCommitHidesParcelsAndPreservesRetry() throws Exception {
        var progress = pending();
        var box = new ArrayList<GiftPackage>();
        Set<String> disk = new HashSet<>();
        assertThrows(IOException.class, () -> RewardDelivery.commit(progress, box,
                () -> box.forEach(g -> disk.add(g.id)), () -> disk, () -> { throw new IOException("state disk full"); }));
        assertFalse(progress.rewardDelivered);
        assertEquals(1, progress.pendingGifts.size());
        assertTrue(box.isEmpty());
        RewardDelivery.commit(progress, box, () -> box.forEach(g -> disk.add(g.id)), () -> disk, () -> { });
        assertTrue(progress.rewardDelivered);
        assertEquals(1, box.size());
    }

    @Test void silentlyFailedLegacyGiftSaveCannotConsumeReward() {
        var progress = pending();
        var box = new ArrayList<GiftPackage>();
        assertThrows(IOException.class, () -> RewardDelivery.commit(progress, box, () -> { }, Set::of, () -> fail("must not commit onboarding state")));
        assertFalse(progress.rewardDelivered);
        assertEquals(1, progress.pendingGifts.size());
        assertTrue(box.isEmpty());
    }

    @Test void invalidDeliveredWithOutboxIsRejected() {
        OnboardingState state = new OnboardingState();
        var progress = pending();
        progress.rewardDelivered = true;
        state.players.put(UUID.randomUUID(), progress);
        assertThrows(IllegalArgumentException.class, state::validate);
    }

    private static OnboardingState.Progress pending() {
        var progress = new OnboardingState.Progress();
        progress.rewardKit = "newbie";
        progress.pendingGifts = new ArrayList<>(List.of(new GiftPackage("onboarding:test:0", new UUID(0, 0), "新手向导",
                "{\"id\":\"minecraft:bread\",\"count\":1}", "面包", 1, 0, "首次加入奖励")));
        return progress;
    }
}
