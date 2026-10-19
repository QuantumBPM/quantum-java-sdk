package com.quantumbpm.client.workers;

import com.quantumbpm.client.generated.ApiClient;
import com.quantumbpm.client.generated.api.BpmnApi;
import com.quantumbpm.client.generated.api.DefaultApi;
import com.quantumbpm.client.variables.Vars;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkerPollTest {

    private static final UUID PROJECT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final String JOBS = "/projects/" + PROJECT_ID + "/bpmn/external-jobs/";
    private static final String ONE_JOB =
            "[{\"executionKey\":\"k1\",\"workflowID\":\"wf-1\",\"taskType\":\"issue-payout\",\"variables\":{\"amount\":800}}]";

    @Test
    void varsHandlerCompletesAndEmptyPollsLogNothing() throws Exception {
        AtomicInteger polls = new AtomicInteger();
        CompletableFuture<String> completed = new CompletableFuture<>();
        CompletableFuture<String> failed = new CompletableFuture<>();

        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(JOBS + "poll", ex -> {
            if (polls.incrementAndGet() == 1) {
                respond(ex, 200, ONE_JOB);
                return;
            }
            sleep(50);
            respond(ex, 204, null);
        });
        server.createContext(JOBS + "k1/complete", ex -> {
            completed.complete(body(ex));
            respond(ex, 204, null);
        });
        server.createContext(JOBS + "k1/error", ex -> {
            failed.complete(body(ex));
            respond(ex, 204, null);
        });
        server.start();

        ApiClient client = new ApiClient();
        client.updateBaseUri("http://127.0.0.1:" + server.getAddress().getPort());
        Worker worker = new Worker(new DefaultApi(client), new BpmnApi(client), PROJECT_ID, "test");
        worker.handle("issue-payout", Vars.class,
                job -> Vars.of().set("paid", job.typed().get("amount", BigDecimal.class)));

        CapturingHandler log = new CapturingHandler();
        Logger.getLogger(Worker.class.getName()).addHandler(log);
        worker.start();
        try {
            String body = completed.get(5, TimeUnit.SECONDS);
            assertTrue(body.contains("\"paid\":800"), "complete body: " + body);
            long deadline = System.currentTimeMillis() + 5_000;
            while (polls.get() < 4 && System.currentTimeMillis() < deadline) {
                Thread.sleep(20);
            }
            assertTrue(polls.get() >= 4, "expected the worker to keep polling, got " + polls.get() + " polls");
            assertFalse(failed.isDone(), "job failed instead of completing: " + failed.getNow(null));
            assertTrue(log.warnings().isEmpty(), "expected no warnings, got " + log.warnings());
        } finally {
            worker.stop(1_000);
            server.stop(0);
            Logger.getLogger(Worker.class.getName()).removeHandler(log);
        }
    }

    @Test
    void heartbeatConflictInterruptsHandlerAndReportsNothing() throws Exception {
        AtomicInteger reports = new AtomicInteger();
        CompletableFuture<Void> interrupted = new CompletableFuture<>();
        HttpServer server = serveOneJob(new AtomicInteger());
        server.createContext(JOBS + "k1/heartbeat", ex -> respond(ex, 409, "{\"message\":\"job not held\"}"));
        countReports(server, reports);
        server.start();

        Worker worker = newWorker(server);
        worker.handle("issue-payout", job -> {
            try {
                Thread.sleep(30_000);
            } catch (InterruptedException e) {
                interrupted.complete(null);
                throw e;
            }
            return null;
        }, Worker.withLockDuration("2s"));

        CapturingHandler log = capture();
        worker.start();
        try {
            interrupted.get(5, TimeUnit.SECONDS);
            Thread.sleep(300);
            assertEquals(0, reports.get(), "a job with a lost lock must not be reported");
            List<String> warnings = log.warnings();
            assertEquals(1, warnings.size(), "expected one lock-lost warning, got " + warnings);
            assertTrue(warnings.get(0).contains("lock lost"), warnings.get(0));
        } finally {
            worker.stop(1_000);
            server.stop(0);
            detach(log);
        }
    }

    @Test
    void stopLetsRunningHandlerHeartbeatAndComplete() throws Exception {
        AtomicInteger polls = new AtomicInteger();
        AtomicInteger heartbeats = new AtomicInteger();
        AtomicInteger heartbeatsAtStop = new AtomicInteger(-1);
        CompletableFuture<String> completed = new CompletableFuture<>();
        CountDownLatch started = new CountDownLatch(1);
        HttpServer server = serveOneJob(polls);
        server.createContext(JOBS + "k1/heartbeat", ex -> {
            heartbeats.incrementAndGet();
            respond(ex, 204, null);
        });
        server.createContext(JOBS + "k1/complete", ex -> {
            completed.complete(body(ex));
            respond(ex, 204, null);
        });
        server.start();

        Worker worker = newWorker(server);
        worker.handle("issue-payout", job -> {
            started.countDown();
            // Finish only after a heartbeat sent while stop() is waiting.
            long deadline = System.currentTimeMillis() + 5_000;
            while (heartbeatsAtStop.get() < 0 || heartbeats.get() <= heartbeatsAtStop.get()) {
                if (System.currentTimeMillis() > deadline) throw new IllegalStateException("no heartbeat during stop");
                Thread.sleep(20);
            }
            return Vars.of().set("done", true);
        }, Worker.withLockDuration("2s"));

        worker.start();
        try {
            assertTrue(started.await(5, TimeUnit.SECONDS), "handler never started");
            heartbeatsAtStop.set(heartbeats.get());
            worker.stop(10_000);
            assertTrue(completed.isDone(), "stop returned before the running handler completed");
            assertTrue(completed.get().contains("\"done\":true"), "complete body: " + completed.get());
            assertTrue(heartbeats.get() > heartbeatsAtStop.get(), "no heartbeat while stop was waiting");
            assertEquals(1, polls.get(), "polled again with no free slot or after stop");
        } finally {
            worker.stop(1_000);
            server.stop(0);
        }
    }

    @Test
    void stopTimeoutInterruptsHandlerAndReportsNothing() throws Exception {
        AtomicInteger reports = new AtomicInteger();
        AtomicInteger heartbeats = new AtomicInteger();
        CompletableFuture<Void> interrupted = new CompletableFuture<>();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(1);
        HttpServer server = serveOneJob(new AtomicInteger());
        server.createContext(JOBS + "k1/heartbeat", ex -> {
            heartbeats.incrementAndGet();
            respond(ex, 204, null);
        });
        countReports(server, reports);
        server.start();

        Worker worker = newWorker(server);
        worker.handle("issue-payout", job -> {
            started.countDown();
            try {
                Thread.sleep(30_000);
            } catch (InterruptedException e) {
                interrupted.complete(null);
            }
            // Ignore the interrupt and return a result well past the timeout.
            Thread.sleep(2_500);
            finished.countDown();
            return Vars.of().set("late", true);
        }, Worker.withLockDuration("2s"));

        worker.start();
        try {
            assertTrue(started.await(5, TimeUnit.SECONDS), "handler never started");
            long t0 = System.nanoTime();
            worker.stop(500);
            long tookMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0);
            assertTrue(tookMs < 2_000, "stop took " + tookMs + "ms");
            interrupted.get(1, TimeUnit.SECONDS);
            Thread.sleep(100);
            int heartbeatsAfterStop = heartbeats.get();
            assertTrue(finished.await(5, TimeUnit.SECONDS), "handler never returned");
            Thread.sleep(300);
            assertEquals(0, reports.get(), "a handler past the stop timeout must not be reported");
            assertEquals(heartbeatsAfterStop, heartbeats.get(), "heartbeat sent after the stop timeout");
        } finally {
            worker.stop(500);
            server.stop(0);
        }
    }

    @Test
    void pollsRequestOnlyFreeSlots() throws Exception {
        List<String> pollBodies = new CopyOnWriteArrayList<>();
        CompletableFuture<String> completed = new CompletableFuture<>();
        CountDownLatch release = new CountDownLatch(1);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(JOBS + "poll", ex -> {
            pollBodies.add(body(ex));
            if (pollBodies.size() == 1) {
                respond(ex, 200, ONE_JOB);
                return;
            }
            sleep(50);
            respond(ex, 204, null);
        });
        server.createContext(JOBS + "k1/complete", ex -> {
            completed.complete(body(ex));
            respond(ex, 204, null);
        });
        server.start();

        Worker worker = newWorker(server);
        worker.handle("issue-payout", job -> {
            release.await();
            return null;
        }, Worker.withMaxJobs(3));

        worker.start();
        try {
            awaitPolls(pollBodies, 2);
            assertTrue(pollBodies.get(0).contains("\"maxJobs\":3"), "first poll: " + pollBodies.get(0));
            assertTrue(pollBodies.get(1).contains("\"maxJobs\":2"), "poll with one job running: " + pollBodies.get(1));
            release.countDown();
            completed.get(5, TimeUnit.SECONDS);
            int after = pollBodies.size();
            long deadline = System.currentTimeMillis() + 5_000;
            while (pollBodies.stream().skip(after).noneMatch(b -> b.contains("\"maxJobs\":3"))) {
                assertTrue(System.currentTimeMillis() < deadline, "no poll for 3 slots after the job finished: " + pollBodies);
                Thread.sleep(20);
            }
        } finally {
            release.countDown();
            worker.stop(1_000);
            server.stop(0);
        }
    }

    @Test
    void completeConflictLogsLockLostWarning() throws Exception {
        CompletableFuture<Void> completeSent = new CompletableFuture<>();
        HttpServer server = serveOneJob(new AtomicInteger());
        server.createContext(JOBS + "k1/complete", ex -> {
            respond(ex, 409, "{\"message\":\"job not held\"}");
            completeSent.complete(null);
        });
        server.start();

        Worker worker = newWorker(server);
        worker.handle("issue-payout", job -> null);

        CapturingHandler log = capture();
        worker.start();
        try {
            completeSent.get(5, TimeUnit.SECONDS);
            long deadline = System.currentTimeMillis() + 2_000;
            while (log.warnings().isEmpty() && System.currentTimeMillis() < deadline) {
                Thread.sleep(20);
            }
            assertEquals(List.of("complete k1: lock lost, result discarded"), log.warnings());
            assertTrue(log.severe().isEmpty(), "expected no SEVERE, got " + log.severe());
        } finally {
            worker.stop(1_000);
            server.stop(0);
            detach(log);
        }
    }

    // Serves ONE_JOB on the first poll and empty polls after it.
    private static HttpServer serveOneJob(AtomicInteger polls) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(JOBS + "poll", ex -> {
            if (polls.incrementAndGet() == 1) {
                respond(ex, 200, ONE_JOB);
                return;
            }
            sleep(50);
            respond(ex, 204, null);
        });
        return server;
    }

    private static void countReports(HttpServer server, AtomicInteger reports) {
        for (String op : List.of("complete", "error")) {
            server.createContext(JOBS + "k1/" + op, ex -> {
                reports.incrementAndGet();
                respond(ex, 204, null);
            });
        }
    }

    private static Worker newWorker(HttpServer server) {
        ApiClient client = new ApiClient();
        client.updateBaseUri("http://127.0.0.1:" + server.getAddress().getPort());
        return new Worker(new DefaultApi(client), new BpmnApi(client), PROJECT_ID, "test");
    }

    private static void awaitPolls(List<String> pollBodies, int n) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5_000;
        while (pollBodies.size() < n && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
        assertTrue(pollBodies.size() >= n, "expected " + n + " polls, got " + pollBodies.size());
    }

    private static CapturingHandler capture() {
        CapturingHandler log = new CapturingHandler();
        Logger.getLogger(Worker.class.getName()).addHandler(log);
        return log;
    }

    private static void detach(CapturingHandler log) {
        Logger.getLogger(Worker.class.getName()).removeHandler(log);
    }

    private static String body(HttpExchange ex) throws IOException {
        return new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
    }

    private static void respond(HttpExchange ex, int status, String json) throws IOException {
        if (json == null) {
            ex.sendResponseHeaders(status, -1);
        } else {
            byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().set("Content-Type", "application/json");
            ex.sendResponseHeaders(status, bytes.length);
            ex.getResponseBody().write(bytes);
        }
        ex.close();
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static final class CapturingHandler extends Handler {
        private final List<String> warnings = new ArrayList<>();
        private final List<String> severe = new ArrayList<>();

        @Override
        public synchronized void publish(LogRecord record) {
            if (record.getLevel().intValue() >= Level.WARNING.intValue()) {
                warnings.add(record.getMessage());
            }
            if (record.getLevel().intValue() >= Level.SEVERE.intValue()) {
                severe.add(record.getMessage());
            }
        }

        @Override public void flush() {}
        @Override public void close() {}

        synchronized List<String> warnings() { return new ArrayList<>(warnings); }

        synchronized List<String> severe() { return new ArrayList<>(severe); }
    }
}
