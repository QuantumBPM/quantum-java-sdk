package com.quantumbpm.client.workers;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.MismatchedInputException;
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
import java.math.BigDecimal;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
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
    private final AtomicBoolean stopTimedOut = new AtomicBoolean(false);
    private final CountDownLatch terminated = new CountDownLatch(1);
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
        this.clientId = (clientId == null || clientId.isBlank()) ? defaultClientId() : clientId.strip();
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
     * Stop polling and wait up to {@code timeoutMillis} for in-flight handlers
     * to finish and report. Past the timeout the remaining handlers are
     * interrupted and not reported. Their locks run out on the server.
     */
    public void stop(long timeoutMillis) {
        if (!stopping.compareAndSet(false, true)) {
            return;
        }
        try {
            shutdownExecutors(timeoutMillis);
        } finally {
            terminated.countDown();
        }
    }

    /**
     * Block until {@link #stop} has returned. The worker runs on virtual
     * threads, which do not keep the JVM alive, so a plain {@code main} calls
     * this after {@link #start}.
     */
    public void awaitTermination() throws InterruptedException {
        terminated.await();
    }

    private void shutdownExecutors(long timeoutMillis) {
        ExecutorService poll = pollExecutor.get();
        ExecutorService dispatch = dispatchExecutor.get();
        if (poll == null || dispatch == null) return;
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        poll.shutdownNow();
        try {
            // Poll loops exit before the dispatcher closes, so every received job is dispatched.
            poll.awaitTermination(timeoutMillis, TimeUnit.MILLISECONDS);
            dispatch.shutdown();
            if (dispatch.awaitTermination(deadline - System.nanoTime(), TimeUnit.NANOSECONDS)) return;
            LOG.log(Level.WARNING, "workers: stop timed out after " + timeoutMillis
                    + "ms, interrupting running handlers, their results will not be reported");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        stopTimedOut.set(true);
        dispatch.shutdownNow();
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
            // Poll only for free slots. Each received job already holds a
            // permit, so nothing blocks between receiving and dispatching.
            int slots = 1 + sem.drainPermits();

            List<ExternalJob> jobs;
            try {
                jobs = poll(r, slots);
            } catch (Exception e) {
                sem.release(slots);
                if (stopping.get()) return;
                LOG.log(Level.WARNING, "poll " + r.taskType + ": " + e.getMessage());
                sleep(POLL_ERROR_BACKOFF_MS);
                continue;
            }
            if (jobs == null) jobs = List.of();
            sem.release(Math.max(0, slots - jobs.size()));

            for (ExternalJob job : jobs) {
                dispatchExecutor.get().execute(() -> {
                    try {
                        dispatch(r, job);
                    } finally {
                        sem.release();
                    }
                });
            }
        }
    }

    private List<ExternalJob> poll(Registration<?> r, int maxJobs) throws ApiException {
        PollBpmnJobRequest body = new PollBpmnJobRequest();
        body.setClientID(clientId);
        body.setTaskType(r.taskType);
        body.setMaxJobs(maxJobs);
        body.setTimeout(r.options.pollTimeout.serialize());
        body.setLockDuration(r.options.lockDuration.serialize());
        try {
            return defaultApi.pollBpmnExternalJobs(projectId, body);
        } catch (ApiException e) {
            if (e.getCause() instanceof MismatchedInputException m
                    && m.getMessage() != null && m.getMessage().startsWith("No content to map")) {
                return List.of();
            }
            throw e;
        }
    }

    private <T> void dispatch(Registration<T> r, ExternalJob raw) {
        // Set once, either by the heartbeat when the lock is lost or by this
        // thread right before it reports. A job is never both.
        AtomicBoolean settled = new AtomicBoolean(false);
        Thread handlerThread = Thread.currentThread();
        long intervalMs = heartbeatIntervalMillis(r.options.lockDuration.toMillis(), raw.getLockExpiresAt(), Instant.now());
        Thread heartbeatThread = Thread.ofVirtual().start(() -> heartbeat(r, raw, intervalMs, settled, handlerThread));
        Span span = JobTracing.startJobSpan(raw, r.taskType);
        try (Scope ignored = span.makeCurrent()) {
            Vars vars;
            T typed;
            try {
                vars = Vars.fromWireMap(raw.getVariables());
                typed = decode(vars, r.typed);
            } catch (Exception e) {
                LOG.log(Level.WARNING, "decode vars " + r.taskType + ": " + e.getMessage());
                if (claimReport(settled)) {
                    throwError(raw, "WORKER_ERROR", new Vars().set("error", "decode vars: " + e.getMessage()), true);
                }
                return;
            }

            Job<T> job = new Job<>(raw, vars, typed);
            Vars result;
            try {
                result = r.handler.handle(job);
            } catch (BpmnError be) {
                if (!claimReport(settled)) return;
                span.setAttribute("bpmn.error_code", be.code());
                throwError(raw, be.code(), be.variables() != null ? be.variables() : new Vars(), false);
                return;
            } catch (Throwable t) {
                if (!claimReport(settled)) return;
                LOG.log(Level.SEVERE, "handler " + r.taskType + ": " + t.getMessage(), t);
                String rawMsg = t.getMessage() == null ? t.toString() : t.getMessage();
                span.recordException(t);
                span.setStatus(StatusCode.ERROR, rawMsg);
                String message = clampWorkerErrorMessage(r.taskType, rawMsg);
                throwError(raw, "WORKER_ERROR", new Vars().set("error", message), true);
                return;
            }
            if (claimReport(settled)) {
                complete(raw, result == null ? new Vars() : result);
            }
        } finally {
            span.end();
            settled.set(true);
            heartbeatThread.interrupt();
        }
    }

    // False when the handler was interrupted for a lost lock or a stop timeout.
    // Such a job is left to the server and not reported, not even as a failure.
    private boolean claimReport(AtomicBoolean settled) {
        return !stopTimedOut.get() && settled.compareAndSet(false, true);
    }

    // Half of the lock the server granted, never more than half of the requested one.
    // A missing or non-positive grant (client clock skew) falls back to the requested lock.
    static long heartbeatIntervalMillis(long requestedMs, OffsetDateTime lockExpiresAt, Instant now) {
        long baseMs = requestedMs;
        if (lockExpiresAt != null) {
            long grantedMs = lockExpiresAt.toInstant().toEpochMilli() - now.toEpochMilli();
            if (grantedMs > 0) baseMs = Math.min(requestedMs, grantedMs);
        }
        return Math.max(1_000L, baseMs / 2);
    }

    private void heartbeat(Registration<?> r, ExternalJob raw, long intervalMs, AtomicBoolean settled, Thread handler) {
        while (true) {
            try {
                Thread.sleep(intervalMs);
            } catch (InterruptedException e) {
                return;
            }
            if (settled.get() || stopTimedOut.get()) return;
            try {
                HeartbeatBpmnExternalJobRequest body = new HeartbeatBpmnExternalJobRequest();
                body.setClientID(clientId);
                body.setLockDuration(r.options.lockDuration.serialize());
                defaultApi.heartbeatBpmnExternalJob(projectId, raw.getExecutionKey(), body);
            } catch (ApiException e) {
                // 409: this worker no longer holds the job. 404: the job is gone.
                if (e.getCode() == 409 || e.getCode() == 404) {
                    if (settled.compareAndSet(false, true)) {
                        LOG.log(Level.WARNING, "heartbeat " + raw.getExecutionKey() + ": " + conflictReason(e)
                                + ", interrupting handler, its result will not be reported");
                        handler.interrupt();
                    }
                    return;
                }
                if (!settled.get()) {
                    LOG.log(Level.WARNING, "heartbeat " + raw.getExecutionKey() + ": " + e.getMessage());
                }
            } catch (RuntimeException e) {
                LOG.log(Level.WARNING, "heartbeat " + raw.getExecutionKey() + ": " + e.getMessage(), e);
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
        } catch (ApiException | RuntimeException e) {
            logReportFailure("complete", raw, e);
        }
    }

    // retryable=false raises errorCode as a business BPMN error immediately;
    // retryable=true (technical failure) consumes the retry budget first.
    private void throwError(ExternalJob raw, String code, Vars vars, boolean retryable) {
        try {
            ThrowBpmnExternalJobErrorRequest body = new ThrowBpmnExternalJobErrorRequest();
            body.setErrorCode(code);
            body.setClientID(clientId);
            body.setVariables(vars.toWireMap());
            body.setRetryable(retryable);
            bpmnApi.throwBpmnExternalJobError(projectId, raw.getExecutionKey(), body);
        } catch (ApiException | RuntimeException e) {
            logReportFailure("throwError", raw, e);
        }
    }

    // A 409 means this worker no longer holds the job and the server dropped the result.
    private static void logReportFailure(String op, ExternalJob raw, Exception e) {
        if (e instanceof ApiException ae && ae.getCode() == 409) {
            LOG.log(Level.WARNING, op + " " + raw.getExecutionKey() + ": result discarded: " + conflictReason(ae));
            return;
        }
        Throwable trace = e instanceof RuntimeException ? e : null;
        LOG.log(Level.SEVERE, op + " " + raw.getExecutionKey() + ": " + e.getMessage(), trace);
    }

    // The message of the server's error body, which says why this worker no
    // longer holds the job, for example that its instance was cancelled.
    static String conflictReason(ApiException e) {
        try {
            String message = MAPPER.readTree(e.getResponseBody()).path("message").asText("");
            if (!message.isEmpty()) return message;
        } catch (Exception notJson) {
            // fall through to the generic reason
        }
        return "this worker no longer holds the job";
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
        if (type == Vars.class) {
            return (T) vars;
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
     * Go duration string with the grammar of Go's {@code time.ParseDuration}:
     * {@code 30s}, {@code 500ms}, {@code 12.5s}, {@code 1m30s}, {@code 1.5h}.
     * {@link #serialize()} returns the trimmed input, which the server parses.
     */
    static final class Duration {
        private static final String PART = "(\\d+(?:\\.\\d*)?|\\.\\d+)(ns|us|µs|μs|ms|s|m|h)";
        private static final Pattern FORMAT = Pattern.compile("[-+]?(?:0|(?:" + PART + ")+)");
        private static final Pattern PARTS = Pattern.compile(PART);
        private static final Map<String, Long> UNIT_NANOS = Map.of(
                "ns", 1L, "us", 1_000L, "µs", 1_000L, "μs", 1_000L, "ms", 1_000_000L,
                "s", 1_000_000_000L, "m", 60_000_000_000L, "h", 3_600_000_000_000L);
        private static final BigDecimal MAX_NANOS = BigDecimal.valueOf(Long.MAX_VALUE);

        private final long nanos;
        private final String repr;

        private Duration(long nanos, String repr) {
            this.nanos = nanos;
            this.repr = repr;
        }

        static Duration parse(String s) {
            String t = s == null ? "" : s.trim();
            if (!FORMAT.matcher(t).matches()) throw invalid(s);
            BigDecimal total = BigDecimal.ZERO;
            Matcher m = PARTS.matcher(t);
            while (m.find()) {
                total = total.add(new BigDecimal(m.group(1)).multiply(BigDecimal.valueOf(UNIT_NANOS.get(m.group(2)))));
            }
            if (total.compareTo(MAX_NANOS) > 0) throw invalid(s);
            long nanos = total.longValue();
            return new Duration(t.startsWith("-") ? -nanos : nanos, t);
        }

        private static IllegalArgumentException invalid(String s) {
            return new IllegalArgumentException(
                    "invalid duration \"" + s + "\", use a Go duration such as 30s, 1m30s or 12.5s");
        }

        long toMillis() {
            return nanos / 1_000_000L;
        }

        String serialize() {
            return repr;
        }
    }
}
