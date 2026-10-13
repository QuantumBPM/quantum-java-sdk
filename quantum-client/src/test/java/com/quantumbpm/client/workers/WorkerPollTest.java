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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

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

        @Override
        public synchronized void publish(LogRecord record) {
            if (record.getLevel().intValue() >= Level.WARNING.intValue()) {
                warnings.add(record.getMessage());
            }
        }

        @Override public void flush() {}
        @Override public void close() {}

        synchronized List<String> warnings() { return new ArrayList<>(warnings); }
    }
}
