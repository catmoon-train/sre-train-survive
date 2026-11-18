package io.wifi.habitrainsurvive.redpacket;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class RedPacketTest {
    private static RedPacket packet(long cents, int count, long now, long ttl) {
        return new RedPacket("abc12345", UUID.randomUUID(), "Sender", cents, count, now, now + ttl, "恭喜发财");
    }

    private static long sumClaims(RedPacket packet) {
        return packet.claims.values().stream().mapToLong(Long::longValue).sum();
    }

    @Test void everyShareIsAtLeastOneCentAndSumsToTheTotal() {
        Random random = new Random(20261203L);
        for (int count : new int[]{1, 2, 3, 10, 100}) {
            for (long total : new long[]{count, count + 1L, 1000L, 999_999L}) {
                RedPacket packet = packet(total, count, 0, 60_000);
                long paid = 0;
                for (int i = 0; i < count; i++) {
                    long share = packet.claim(UUID.randomUUID(), 10, random);
                    assertTrue(share >= 1, "每份至少 0.01 金币");
                    paid += share;
                }
                assertEquals(total, paid);
                assertEquals(0, packet.remainingCents);
                assertEquals(0, packet.remainingCount);
            }
        }
    }

    @Test void oneSharePerPlayerAndNoClaimAfterExhaustion() {
        RedPacket packet = packet(500, 2, 0, 60_000);
        UUID first = UUID.randomUUID();
        assertTrue(packet.claim(first, 1, new Random(1)) > 0);
        assertEquals(0, packet.claim(first, 2, new Random(2)), "同一玩家不能重复领取");
        assertTrue(packet.claim(UUID.randomUUID(), 2, new Random(3)) > 0);
        assertEquals(0, packet.claim(UUID.randomUUID(), 3, new Random(4)), "抢完后不再发放");
    }

    @Test void expiryIsExclusiveOfTheDeadlineAndRemainingCentsStayAccountedFor() {
        RedPacket packet = packet(300, 3, 0, 1000);
        assertTrue(packet.open(999));
        assertFalse(packet.open(1000));
        assertTrue(packet.expired(1000));
        long first = packet.claim(UUID.randomUUID(), 10, new Random(7));
        assertEquals(300 - first, packet.remainingCents);
        assertEquals(2, packet.remainingCount);
        assertEquals(first, sumClaims(packet));
        packet.close();
        assertFalse(packet.open(11));
        assertEquals(0, packet.remainingCents);
        assertEquals(0, packet.remainingCount);
    }

    @Test void populationKeepsEachRemainingSharePayable() {
        RedPacket packet = packet(100, 10, 0, 1000);
        for (int i = 0; i < 5; i++) packet.claim(UUID.randomUUID(), i, new Random(i));
        assertTrue(packet.normalize());
        assertTrue(packet.remainingCents >= packet.remainingCount);
        assertTrue(packet.open(0));
    }

    @Test void malformedEntriesAreRejectedInsteadOfCrashingTheLoad() {
        assertFalse(new RedPacket().normalize());
        RedPacket badSender = packet(100, 1, 0, 1000);
        badSender.senderUuid = "not-a-uuid";
        assertFalse(badSender.normalize());
        RedPacket badCount = packet(100, 1, 0, 1000);
        badCount.count = 0;
        assertFalse(badCount.normalize());
    }

    @Test void amountAndCountBoundariesFollowTheCommandContract() {
        assertEquals(1, RedPacketValues.cents(0.01));
        assertEquals(100, RedPacketValues.cents(1.0));
        assertEquals(1_000_000_000L * 100, RedPacketValues.cents(1_000_000_000.0));
        for (double invalid : new double[]{0, 0.001, -1, Double.NaN, Double.POSITIVE_INFINITY, 1_000_000_001}) {
            assertThrows(IllegalArgumentException.class, () -> RedPacketValues.cents(invalid));
        }
        assertEquals(1, RedPacketValues.count(1));
        assertEquals(100, RedPacketValues.count(100));
        for (int invalid : new int[]{0, -1, 101}) {
            assertThrows(IllegalArgumentException.class, () -> RedPacketValues.count(invalid));
        }
    }

    @Test void messageStripsControlCharactersAndFallsBackToTheDefault() {
        assertEquals(RedPacketValues.DEFAULT_MESSAGE, RedPacketValues.message(null));
        assertEquals(RedPacketValues.DEFAULT_MESSAGE, RedPacketValues.message("   "));
        assertEquals("恭喜发财大吉大利", RedPacketValues.message("恭喜发财\u0000大吉\u0007大利"));
        String longText = RedPacketValues.message("礼".repeat(200));
        assertEquals(RedPacketValues.MAX_MESSAGE, longText.codePointCount(0, longText.length()));
    }

    @Test void randomSharesVaryInsteadOfAlwaysSplittingEvenly() {
        Set<Long> firstShares = new HashSet<>();
        Random random = new Random(42);
        for (int trial = 0; trial < 20; trial++) {
            RedPacket packet = packet(10_000, 10, 0, 1000);
            firstShares.add(packet.claim(UUID.randomUUID(), 1, random));
        }
        assertTrue(firstShares.size() > 1, "拼手气结果不应固定为平均值");
    }
}
