package com.quantumbpm.spring;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * Configuration properties for the QuantumBPM Spring Boot starter.
 *
 * <pre>
 * quantumbpm:
 *   base-url: https://quantumbpm.example.com
 *   project-id: 00000000-0000-0000-0000-000000000000
 *   token: ${MY_BEARER_TOKEN}        # optional - or supply a TokenProvider bean
 *   auth:
 *     client-credentials:
 *       token-url: https://idp.example.com/realms/acme/protocol/openid-connect/token
 *       client-id: billing-svc
 *       client-secret: ${QUANTUMBPM_CLIENT_SECRET}
 *   worker:
 *     enabled: true                   # registers @JobWorker beans
 *     client-id: billing-svc          # optional; defaults to worker-{host}-{pid}
 * </pre>
 */
@ConfigurationProperties(prefix = "quantumbpm")
public class QuantumBpmProperties {

    /** API base URL (e.g. {@code https://quantumbpm.example.com}). */
    private String baseUrl;

    /** Project the client is scoped to. */
    private String projectId;

    /** Static bearer token. Used when {@code auth.client-credentials} is not configured. */
    private String token;

    private Auth auth = new Auth();
    private Worker worker = new Worker();

    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }

    public String getProjectId() { return projectId; }
    public void setProjectId(String projectId) { this.projectId = projectId; }

    public String getToken() { return token; }
    public void setToken(String token) { this.token = token; }

    public Auth getAuth() { return auth; }
    public void setAuth(Auth auth) { this.auth = auth; }

    public Worker getWorker() { return worker; }
    public void setWorker(Worker worker) { this.worker = worker; }

    public static class Auth {
        private ClientCredentials clientCredentials = new ClientCredentials();
        public ClientCredentials getClientCredentials() { return clientCredentials; }
        public void setClientCredentials(ClientCredentials clientCredentials) { this.clientCredentials = clientCredentials; }

        /** OAuth 2.0 client credentials grant against the identity provider's token endpoint. */
        public static class ClientCredentials {
            /** The identity provider's token endpoint. */
            private String tokenUrl;
            private String clientId;
            private String clientSecret;
            /** Sent space-joined as the scope parameter. None when empty. */
            private List<String> scopes = new ArrayList<>();
            /** Sent as the audience parameter when set. Some providers, such as Auth0, require it. */
            private String audience;

            public String getTokenUrl() { return tokenUrl; }
            public void setTokenUrl(String tokenUrl) { this.tokenUrl = tokenUrl; }
            public String getClientId() { return clientId; }
            public void setClientId(String clientId) { this.clientId = clientId; }
            public String getClientSecret() { return clientSecret; }
            public void setClientSecret(String clientSecret) { this.clientSecret = clientSecret; }
            public List<String> getScopes() { return scopes; }
            public void setScopes(List<String> scopes) { this.scopes = scopes; }
            public String getAudience() { return audience; }
            public void setAudience(String audience) { this.audience = audience; }
        }
    }

    public static class Worker {
        /** Whether to scan for {@link JobWorker} beans and start a worker. Default true. */
        private boolean enabled = true;
        /** Stable client identifier. Defaults to {@code worker-<host>-<pid>}. */
        private String clientId;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public String getClientId() { return clientId; }
        public void setClientId(String clientId) { this.clientId = clientId; }
    }
}
