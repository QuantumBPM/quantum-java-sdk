package com.quantumbpm.client.workers;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.quantumbpm.client.generated.ApiException;
import com.quantumbpm.client.generated.api.BpmnApi;
import com.quantumbpm.client.generated.api.DefaultApi;
import com.quantumbpm.client.generated.model.CompleteBpmnExternalJobRequest;
import com.quantumbpm.client.generated.model.ExternalJob;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.context.Scope;
import com.quantumbpm.client.generated.model.HeartbeatBpmnExternalJobRequest;
import com.quantumbpm.client.generated.model.PollBpmnJobRequest;
import com.quantumbpm.client.generated.model.ThrowBpmnExternalJobErrorRequest;
import com.quantumbpm.client.variables.Vars;

import java.lang.management.ManagementFactory;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Long-poll runtime owning a set of handlers, one per task type.
 *
 * <p>Use {@link #handle} to register a handler; {@link #start} spawns the
 * polling threads (virtual threads on Java 21+); {@link #stop} initiates
 * graceful shutdown and waits for in-flight handlers to finish.</p>
 */
public class Worker {

    private static final Logger LOG = Logger.getLogger(Worker.class.getName());
    private static final Duration DEFAULT_POLL_TIMEOUT = Duration.parse("30s");
    private static final Duration DEFAULT_LOCK_DURATION = Duration.parse("30s");
    private static final int DEFAULT_MAX_ERROR_MESSAGE_BYTES = 2048;
    private static final long POLL_ERROR_BACKOFF_MS = 2_000L;
    // USE_BIG_DECIMAL_FOR_FLOATS mirrors Vars/QuantumBPM: job variables must
    // reach handlers with exact decimals, not doubles.
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);

    private final DefaultApi defaultApi;
    private final BpmnApi bpmnApi;
    private final UUID projectId;
    private final String clientId;
    private final int maxErrorMessageBytes;

    private final Map<String, Registration<?>> registrations = new HashMap<>();
    private final AtomicBoolean started = new AtomicBoolean(false);
    private final AtomicBoolean stopping = new AtomicBoolean(false);
    private final AtomicReference<ExecutorService> pollExecutor = new AtomicReference<>();
    private final AtomicReference<ExecutorService> dispatchExecutor = new AtomicReference<>();

    public Worker(DefaultApi defaultApi, BpmnApi bpmnApi, UUID projectId, String clientId) {
        this(defaultApi, bpmnApi, projectId, clientId, DEFAULT_MAX_ERROR_MESSAGE_BYTES);
    }

    /**
     * Same as {@link #Worker(DefaultApi, BpmnApi, UUID, String)} but lets the
     * caller tune the byte cap on the auto-built WORKER_ERROR message attached
     * when a handler throws a non-{@link BpmnError}. User-thrown BpmnError
     * variables are not clamped. Pass {@code 0} or a negative number to use
     * the default ({@value #DEFAULT_MAX_ERROR_MESSAGE_BYTES}).
     */
    public Worker(DefaultApi defaultApi, BpmnApi bpmnApi, UUID projectId, String clientId, int maxErrorMessageBytes) {
        this.defaultApi = defaultApi;
        this.bpmnApi = bpmnApi;
        this.projectId = projectId;
        this.clientId = (clientId == null || clientId.isBlank()) ? defaultClientId() : clientId;
        this.maxErrorMessageBytes = maxErrorMessageBytes > 0 ? maxErrorMessageBytes : DEFAULT_MAX_ERROR_MESSAGE_BYTES;
    }

    public String clientId() {
        return clientId;
    }

    /** Register {@code handler} as the processor for {@code taskType}. */
    public <T> void handle(String taskType, Class<T> typed, Handler<T> handler, HandleOption... options) {
        if (started.get()) {
            throw new IllegalStateException("workers: cannot register after Worker.start()");
        }
        HandleOptions opts = HandleOptions.from(options);
        registrations.put(taskType, new Registration<>(taskType, typed, handler, opts));
    }

    /** Convenience overload for handlers that take {@code Map<String, Object>}. */
    @SuppressWarnings("unchecked")
    public void handle(String taskType, Handler<Map<String, Object>> handler, HandleOption... options) {
        handle(taskType, (Class<Map<String, Object>>) (Class<?>) Map.class, handler, options);
    }

    /**
     * Start the polling loops. Returns immediately. Each registered task
     * type gets a dedicated poll loop on a virtual thread; jobs dispatch on
     * a shared virtual-thread pool.
     */
    public void start() {
        if (!started.compareAndSet(false, true)) {
            throw new IllegalStateException("workers: already started");
        }
        if (registrations.isEmpty()) {
            started.set(false);
            throw new IllegalStateException("workers: no handlers registered");
        }
        pollExecutor.set(Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("quantum-worker-poll-", 0).factory()));
        dispatchExecutor.set(Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("quantum-worker-dispatch-", 0).factory()));
        for (Registration<?> r : registrations.values()) {
            pollExecutor.get().submit(() -> runTaskType(r));
        }
    }

    /**
     * Stop polling and wait up to {@code timeoutMillis} for in-flight
     * handlers to finish.
     */
    public void stop(long timeoutMillis) {
        if (!stopping.compareAndSet(false, true)) {
            return;
        }
        ExecutorService poll = pollExecutor.get();
        ExecutorService dispatch = dispatchExecutor.get();
        if (poll != null) poll.shutdownNow();
        if (dispatch != null) dispatch.shutdown();
        try {
            if (dispatch != null) {
                dispatch.awaitTermination(timeoutMillis, java.util.concurrent.TimeUnit.MILLISECONDS);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private <T> void runTaskType(Registration<T> r) {
        Semaphore sem = new Semaphore(r.options.maxJobs);
        while (!stopping.get() && !Thread.currentThread().isInterrupted()) {
            try {
                sem.acquire();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }

            List<ExternalJob> jobs;
            try {
                jobs = poll(r);
            } catch (Exception e) {
                sem.release();
                if (stopping.get()) return;
                LOG.log(Level.WARNING, "poll " + r.taskType + ": " + e.getMessage());
                sleep(POLL_ERROR_BACKOFF_MS);
                continue;
            }

            if (jobs == null || jobs.isEmpty()) {
                sem.release();
                continue;
            }

            for (int i = 0; i < jobs.size(); i++) {
                if (i > 0) {
                    try {
                        sem.acquire();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
                ExternalJob job = jobs.get(i);
                dispatchExecutor.get().submit(() -> {
                    try {
                        dispatch(r, job);
                    } finally {
                        sem.release();
                    }
                });
            }
        }
    }

    private List<ExternalJob> poll(Registration<?> r) throws ApiException {
        PollBpmnJobRequest body = new PollBpmnJobRequest();
        body.setClientID(clientId);
        body.setTaskType(r.taskType);
        body.setMaxJobs(r.options.maxJobs);
        body.setTimeout(r.options.pollTimeout.serialize());
        body.setLockDuration(r.options.lockDuration.serialize());
        return defaultApi.pollBpmnExternalJobs(projectId, body);
    }

    private <T> void dispatch(Registration<T> r, ExternalJob raw) {
        AtomicBoolean stopHeartbeat = new AtomicBoolean(false);
        Thread heartbeatThread = Thread.ofVirtual().start(() -> heartbeat(r, raw, stopHeartbeat));
        Span span = JobTracing.startJobSpan(raw, r.taskType);
        try (Scope ignored = span.makeCurrent()) {
            Vars vars = Vars.fromWireMap(raw.getVariables());
            T typed;
            try {
                typed = decode(vars, r.typed);
            } catch (Exception e) {
                LOG.log(Level.WARNING, "decode vars " + r.taskType + ": " + e.getMessage());
                throwError(raw, "WORKER_ERROR", new Vars().set("error", "decode vars: " + e.getMessage()));
                return;
            }

            Job<T> job = new Job<>(raw, vars, typed);
            Vars result;
            try {
                result = r.handler.handle(job);
            } catch (BpmnError be) {
                span.setAttribute("bpmn.error_code", be.code());
                throwError(raw, be.code(), be.variables() != null ? be.variables() : new Vars());
                return;
            } catch (Throwable t) {
                LOG.log(Level.SEVERE, "handler " + r.taskType + ": " + t.getMessage(), t);
                String rawMsg = t.getMessage() == null ? t.toString() : t.getMessage();
                span.recordException(t);
                span.setStatus(StatusCode.ERROR, rawMsg);
                String message = clampWorkerErrorMessage(r.taskType, rawMsg);
                throwError(raw, "WORKER_ERROR", new Vars().set("error", message));
                return;
            }
            complete(raw, result == null ? new Vars() : result);
        } finally {
            span.end();
            stopHeartbeat.set(true);
            heartbeatThread.interrupt();
        }
    }

    private void heartbeat(Registration<?> r, ExternalJob raw, AtomicBoolean stop) {
        long intervalMs = Math.max(1_000L, r.options.lockDuration.toMillis() / 2);
        while (!stop.get()) {
            try {
                Thread.sleep(intervalMs);
            } catch (InterruptedException e) {
                return;
            }
            if (stop.get()) return;
            try {
                HeartbeatBpmnExternalJobRequest body = new HeartbeatBpmnExternalJobRequest();
                body.setClientID(clientId);
                body.setLockDuration(r.options.lockDuration.serialize());
                defaultApi.heartbeatBpmnExternalJob(projectId, raw.getExecutionKey(), body);
            } catch (ApiException e) {
                LOG.log(Level.WARNING, "heartbeat " + raw.getExecutionKey() + ": " + e.getMessage());
            }
        }
    }

    private void complete(ExternalJob raw, Vars vars) {
        try {
            CompleteBpmnExternalJobRequest body = new CompleteBpmnExternalJobRequest();
            body.setWorkflowID(raw.getWorkflowID());
            body.setClientID(clientId);
            body.setVariables(vars.toWireMap());
            defaultApi.completeBpmnExternalJob(projectId, raw.getExecutionKey(), body);
        } catch (ApiException e) {
            LOG.log(Level.SEVERE, "complete " + raw.getExecutionKey() + ": " + e.getMessage());
        }
    }

    private void throwError(ExternalJob raw, String code, Vars vars) {
        try {
            ThrowBpmnExternalJobErrorRequest body = new ThrowBpmnExternalJobErrorRequest();
            body.setErrorCode(code);
            body.setClientID(clientId);
            body.setVariables(vars.toWireMap());
            bpmnApi.throwBpmnExternalJobError(projectId, raw.getExecutionKey(), body);
        } catch (ApiException e) {
            LOG.log(Level.SEVERE, "throwError " + raw.getExecutionKey() + ": " + e.getMessage());
        }
    }

    /**
     * Shortens an unhandled handler exception's message to the configured
     * byte budget. UTF-8 safe (cuts on code-point boundary). Logs a WARN
     * and appends a truncation marker when it triggers. Package-private for
     * tests.
     */
    String clampWorkerErrorMessage(String taskType, String msg) {
        if (msg == null) {
            return null;
        }
        byte[] bytes = msg.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        if (maxErrorMessageBytes <= 0 || bytes.length <= maxErrorMessageBytes) {
            return msg;
        }
        String marker = "…[truncated, original " + bytes.length + " bytes]";
        int markerBytes = marker.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
        int budget = Math.max(0, maxErrorMessageBytes - markerBytes);
        // Walk back from `budget` until we land on a UTF-8 lead byte so we
        // don't emit half a multi-byte rune.
        int cut = Math.min(budget, bytes.length);
        while (cut > 0 && (bytes[cut] & 0xC0) == 0x80) {
            cut--;
        }
        String prefix = new String(bytes, 0, cut, java.nio.charset.StandardCharsets.UTF_8);
        LOG.log(
            Level.WARNING,
            "workers: WORKER_ERROR message truncated for task=" + taskType
                + " from " + bytes.length + " to " + maxErrorMessageBytes + " bytes"
        );
        return prefix + marker;
    }

    @SuppressWarnings("unchecked")
    private static <T> T decode(Vars vars, Class<T> type) {
        if (type == null || type == Map.class || type == Object.class) {
            return (T) vars.toMap();
        }
        return MAPPER.convertValue(vars.toMap(), type);
    }

    private static String defaultClientId() {
        String host;
        try {
            host = InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException e) {
            host = "unknown";
        }
        String pid = ManagementFactory.getRuntimeMXBean().getName().split("@")[0];
        return "worker-" + host + "-" + pid;
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // -------------------- options --------------------

    @FunctionalInterface
    public interface HandleOption {
        void apply(HandleOptions opts);
    }

    public static final class HandleOptions {
        int maxJobs = 1;
        Duration pollTimeout = DEFAULT_POLL_TIMEOUT;
        Duration lockDuration = DEFAULT_LOCK_DURATION;

        static HandleOptions from(HandleOption[] options) {
            HandleOptions o = new HandleOptions();
            for (HandleOption opt : options) opt.apply(o);
            return o;
        }
    }

    public static HandleOption withMaxJobs(int n) {
        return o -> o.maxJobs = n;
    }

    public static HandleOption withPollTimeout(String duration) {
        return o -> o.pollTimeout = Duration.parse(duration);
    }

    public static HandleOption withLockDuration(String duration) {
        return o -> o.lockDuration = Duration.parse(duration);
    }

    // -------------------- internal helpers --------------------

    private record Registration<T>(String taskType, Class<T> typed, Handler<T> handler, HandleOptions options) {}

    /**
     * Compact duration parser that accepts Go-style strings ({@code 30s},
     * {@code 2m}, {@code 1h}, {@code 500ms}). Round-trips back to the same
     * shape via {@link #serialize()} so the server sees what it expects.
     */
    private static final class Duration {
        private static final Pattern PATTERN = Pattern.compile("^(\\d+)(ms|s|m|h)$");

        private final long ms;
        private final String repr;

        private Duration(long ms, String repr) {
            this.ms = ms;
            this.repr = repr;
        }

        static Duration parse(String s) {
            Matcher m = PATTERN.matcher(s.trim());
            if (!m.matches()) throw new IllegalArgumentException("invalid duration: " + s);
            long value = Long.parseLong(m.group(1));
            return switch (m.group(2)) {
                case "ms" -> new Duration(value, s);
                case "s"  -> new Duration(value * 1_000L, s);
                case "m"  -> new Duration(value * 60_000L, s);
                case "h"  -> new Duration(value * 3_600_000L, s);
                default -> throw new IllegalArgumentException("invalid duration: " + s);
            };
        }

        long toMillis() {
            return ms;
        }

        String serialize() {
            return repr;
        }
    }
}
