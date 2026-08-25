package com.quantumbpm.client.workers;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

class WorkerClampTest {

    private static final UUID PROJECT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final int DEFAULT_LIMIT = 2048;

    private Worker newWorker(int maxBytes) {
        // null API clients are fine - the clamp helper never touches them.
        return new Worker(null, null, PROJECT_ID, null, maxBytes);
    }

    private CapturingHandler attachCapture() {
        Logger logger = Logger.getLogger(Worker.class.getName());
        CapturingHandler h = new CapturingHandler();
        h.setLevel(Level.ALL);
        logger.addHandler(h);
        logger.setLevel(Level.ALL);
        return h;
    }

    private void detach(CapturingHandler h) {
        Logger.getLogger(Worker.class.getName()).removeHandler(h);
    }

    @Test
    void passesShortMessageThrough() {
        Worker w = newWorker(0); // 0 → default
        CapturingHandler h = attachCapture();
        try {
            String out = w.clampWorkerErrorMessage("payment", "boom");
            assertEquals("boom", out);
            assertTrue(h.warnings().isEmpty(), "expected no WARN log, got " + h.warnings());
        } finally {
            detach(h);
        }
    }

    @Test
    void truncatesAtDefaultAndWarnsOnce() {
        Worker w = newWorker(0);
        CapturingHandler h = attachCapture();
        try {
            String huge = "x".repeat(100_000);
            String out = w.clampWorkerErrorMessage("payment", huge);
            int bytes = out.getBytes(StandardCharsets.UTF_8).length;
            assertTrue(bytes <= DEFAULT_LIMIT, "clamped len=" + bytes + " exceeds limit=" + DEFAULT_LIMIT);
            assertTrue(out.endsWith(" bytes]"), "expected truncation marker, got tail=" + out.substring(Math.max(0, out.length() - 40)));
            assertEquals(1, h.warnings().size(), "expected exactly one WARN, got " + h.warnings());
            assertTrue(h.warnings().get(0).contains("WORKER_ERROR message truncated"));
        } finally {
            detach(h);
        }
    }

    @Test
    void honorsOverride() {
        Worker w = newWorker(256);
        String out = w.clampWorkerErrorMessage("payment", "x".repeat(10_000));
        int bytes = out.getBytes(StandardCharsets.UTF_8).length;
        assertTrue(bytes <= 256, "clamped len=" + bytes + " exceeds override=256");
    }

    @Test
    void cutsOnUtf8Boundary() {
        // "é" is 2 bytes in UTF-8 - a naive byte slice could split it.
        Worker w = newWorker(200);
        String msg = "é".repeat(1000); // 2000 bytes
        String out = w.clampWorkerErrorMessage("t", msg);
        // Round-trip is lossless only if we cut on a code-point boundary.
        String roundTrip = new String(out.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8);
        assertEquals(out, roundTrip);
        assertFalse(out.contains("�"), "unexpected replacement character");
    }

    @Test
    void nullMessagePassesThroughNull() {
        Worker w = newWorker(0);
        assertNull(w.clampWorkerErrorMessage("t", null));
    }

    private static final class CapturingHandler extends Handler {
        private final List<String> warnings = new ArrayList<>();

        @Override
        public void publish(LogRecord record) {
            if (record.getLevel().intValue() >= Level.WARNING.intValue()) {
                warnings.add(record.getMessage());
            }
        }

        @Override public void flush() {}
        @Override public void close() {}

        List<String> warnings() { return warnings; }
    }
}
