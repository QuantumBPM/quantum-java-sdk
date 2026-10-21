package com.quantumbpm.client.workers;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkerOptionsTest {

    private static final UUID PROJECT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final Instant NOW = Instant.parse("2026-10-21T10:00:00Z");

    @Test
    void parsesGoDurations() {
        assertMillis(30_000, "30s");
        assertMillis(500, "500ms");
        assertMillis(12_500, "12.5s");
        assertMillis(90_000, "1m30s");
        assertMillis(3_723_500, "1h2m3.5s");
        assertMillis(5_400_000, "1.5h");
        assertMillis(500, ".5s");
        assertMillis(5_000, "5.s");
        assertMillis(1, "1000us");
        assertMillis(1, "1000µs");
        assertMillis(1, "1000μs");
        assertMillis(1, "1000000ns");
        assertMillis(0, "0");
        assertMillis(-1_500, "-1.5s");
        assertMillis(2_000, "+2s");
        assertMillis(30_000, " 30s ");
    }

    @Test
    void serializesTrimmedInput() {
        assertEquals("1m30s", Worker.Duration.parse(" 1m30s ").serialize());
    }

    @Test
    void rejectsInvalidDurations() {
        for (String s : List.of("", " ", "30", "1.5", "s", ".s", "1sec", "1d", "1.5.5s", "1m 30s", "--1s",
                "1S", "abc", "3000000h")) {
            assertThrows(IllegalArgumentException.class, () -> Worker.Duration.parse(s), "accepted " + s);
        }
        assertThrows(IllegalArgumentException.class, () -> Worker.Duration.parse(null));
    }

    @Test
    void handleFailsFastOnInvalidLockDuration() {
        Worker w = new Worker(null, null, PROJECT_ID, "t");
        assertThrows(IllegalArgumentException.class,
                () -> w.handle("t", job -> null, Worker.withLockDuration("12.5")));
    }

    @Test
    void heartbeatsAtHalfTheGrantedLock() {
        // 11m requested, 5m granted by the server clamp.
        assertEquals(150_000, Worker.heartbeatIntervalMillis(660_000, NOW.plusSeconds(300).atOffset(ZoneOffset.UTC), NOW));
        // A grant above the request (client clock behind the server) stays at half the request.
        assertEquals(15_000, Worker.heartbeatIntervalMillis(30_000, NOW.plusSeconds(60).atOffset(ZoneOffset.UTC), NOW));
        assertEquals(6_250, Worker.heartbeatIntervalMillis(12_500, NOW.plusMillis(12_500).atOffset(ZoneOffset.UTC), NOW));
    }

    @Test
    void heartbeatFallsBackToTheRequestedLock() {
        assertEquals(330_000, Worker.heartbeatIntervalMillis(660_000, null, NOW));
        OffsetDateTime past = NOW.minusSeconds(5).atOffset(ZoneOffset.UTC);
        assertEquals(330_000, Worker.heartbeatIntervalMillis(660_000, past, NOW));
        assertEquals(330_000, Worker.heartbeatIntervalMillis(660_000, NOW.atOffset(ZoneOffset.UTC), NOW));
    }

    @Test
    void heartbeatIntervalIsAtLeastOneSecond() {
        assertEquals(1_000, Worker.heartbeatIntervalMillis(500, null, NOW));
        assertEquals(1_000, Worker.heartbeatIntervalMillis(30_000, NOW.plusMillis(800).atOffset(ZoneOffset.UTC), NOW));
    }

    @Test
    void stripsClientId() {
        assertEquals("w1", new Worker(null, null, PROJECT_ID, "  w1 ").clientId());
    }

    @Test
    void blankClientIdFallsBackToGeneratedId() {
        for (String blank : new String[] {null, "", "   "}) {
            String id = new Worker(null, null, PROJECT_ID, blank).clientId();
            assertTrue(id.startsWith("worker-"), "client id for " + blank + ": " + id);
        }
    }

    private static void assertMillis(long expected, String s) {
        assertEquals(expected, Worker.Duration.parse(s).toMillis(), s);
    }
}
