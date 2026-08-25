package com.quantumbpm.client;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.quantumbpm.client.auth.TokenException;
import com.quantumbpm.client.auth.TokenProvider;
import com.quantumbpm.client.bpmn.BpmnClient;
import com.quantumbpm.client.dmn.DmnClient;
import com.quantumbpm.client.generated.ApiClient;
import com.quantumbpm.client.generated.api.BpmnApi;
import com.quantumbpm.client.generated.api.DefaultApi;
import com.quantumbpm.client.workers.Worker;

import java.net.URI;
import java.util.UUID;

/**
 * Top-level QuantumBPM SDK entry point.
 *
 * <pre>{@code
 * QuantumBPM client = QuantumBPM.builder()
 *     .baseUrl("https://api.quantumbpm.com")
 *     .projectId(UUID.fromString("00000000-0000-0000-0000-000000000000"))
 *     .tokenProvider(new ZitadelTokenProvider(keyFile, issuer, projectId))
 *     .build();
 *
 * Map<String, EvaluationResult> result = client.dmn().evaluate("loan-eligibility", vars);
 * String workflowId = client.bpmn().startInstance(processDefId, vars);
 *
 * Worker worker = client.newWorker("billing-svc");
 * worker.handle("send-email", job -> {
 *     // ...
 *     return new Vars().set("messageID", "msg-123");
 * });
 * worker.start();
 * }</pre>
 */
public final class QuantumBPM {

    private final ApiClient api;
    private final UUID projectId;
    private final DmnClient dmn;
    private final BpmnClient bpmn;

    private QuantumBPM(Builder b) {
        if (b.baseUrl == null || b.baseUrl.isBlank()) throw new IllegalArgumentException("quantumbpm: baseUrl is required");
        if (b.projectId == null) throw new IllegalArgumentException("quantumbpm: projectId is required");
        if (b.tokenProvider == null) throw new IllegalArgumentException("quantumbpm: tokenProvider is required");

        this.projectId = b.projectId;
        this.api = buildApiClient(b.baseUrl, b.tokenProvider);
        DefaultApi defaultApi = new DefaultApi(api);
        BpmnApi bpmnApi = new BpmnApi(api);
        this.dmn = new DmnClient(defaultApi, projectId);
        this.bpmn = new BpmnClient(defaultApi, bpmnApi, projectId);
    }

    /** DMN evaluation surface. */
    public DmnClient dmn() {
        return dmn;
    }

    /** BPMN runtime surface - resources, instances, messaging, user tasks. */
    public BpmnClient bpmn() {
        return bpmn;
    }

    /** Project the client is bound to. */
    public UUID projectId() {
        return projectId;
    }

    /** Underlying generated client. Use for endpoints not yet wrapped. */
    public ApiClient raw() {
        return api;
    }

    /**
     * Construct a worker bound to this client's project. Register handlers
     * via {@link Worker#handle}, then call {@link Worker#start} to start
     * polling.
     *
     * @param clientId stable identifier for the worker. {@code null} or
     *                 blank produces a default of {@code worker-<host>-<pid>}.
     */
    public Worker newWorker(String clientId) {
        return new Worker(new DefaultApi(api), new BpmnApi(api), projectId, clientId);
    }

    public static Builder builder() {
        return new Builder();
    }

    private static ApiClient buildApiClient(String baseUrl, TokenProvider provider) {
        URI uri = URI.create(baseUrl);
        ApiClient client = new ApiClient();
        // FEEL numbers are exact decimals; Jackson's default double parsing
        // would silently narrow anything beyond ~15 significant digits.
        // getObjectMapper() hands out a copy, so enable-and-set-back.
        client.setObjectMapper(client.getObjectMapper()
                .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS));
        if (uri.getScheme() != null) client.setScheme(uri.getScheme());
        if (uri.getHost() != null) client.setHost(uri.getHost());
        if (uri.getPort() != -1) client.setPort(uri.getPort());
        if (uri.getPath() != null && !uri.getPath().isEmpty() && !uri.getPath().equals("/")) {
            client.setBasePath(uri.getPath());
        }
        client.setRequestInterceptor(req -> {
            String token;
            try {
                token = provider.getToken();
            } catch (TokenException e) {
                throw new RuntimeException("quantumbpm: token provider failed", e);
            }
            req.header("Authorization", "Bearer " + token);
        });
        return client;
    }

    public static final class Builder {
        private String baseUrl;
        private UUID projectId;
        private TokenProvider tokenProvider;

        public Builder baseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
            return this;
        }

        public Builder projectId(UUID projectId) {
            this.projectId = projectId;
            return this;
        }

        public Builder projectId(String projectId) {
            this.projectId = UUID.fromString(projectId);
            return this;
        }

        public Builder tokenProvider(TokenProvider tokenProvider) {
            this.tokenProvider = tokenProvider;
            return this;
        }

        public QuantumBPM build() {
            return new QuantumBPM(this);
        }
    }
}
