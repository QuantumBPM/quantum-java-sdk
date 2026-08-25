package com.quantumbpm.spring;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration properties for the QuantumBPM Spring Boot starter.
 *
 * <pre>
 * quantumbpm:
 *   base-url: https://api.quantumbpm.com
 *   project-id: 00000000-0000-0000-0000-000000000000
 *   token: ${MY_BEARER_TOKEN}        # optional - or supply a TokenProvider bean
 *   auth:
 *     zitadel:
 *       key-file: /path/to/service-account.json
 *       issuer: https://auth.quantumbpm.com
 *       project-id: 123456789
 *   worker:
 *     enabled: true                   # registers @JobWorker beans
 *     client-id: billing-svc          # optional; defaults to worker-{host}-{pid}
 * </pre>
 */
@ConfigurationProperties(prefix = "quantumbpm")
public class QuantumBpmProperties {

    /** API base URL (e.g. {@code https://api.quantumbpm.com}). */
    private String baseUrl;

    /** Project the client is scoped to. */
    private String projectId;

    /** Static bearer token. Mutually exclusive with {@code auth.zitadel}. */
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
        private Zitadel zitadel = new Zitadel();
        public Zitadel getZitadel() { return zitadel; }
        public void setZitadel(Zitadel zitadel) { this.zitadel = zitadel; }

        public static class Zitadel {
            private String keyFile;
            private String issuer = "https://auth.quantumbpm.com";
            private String projectId;

            public String getKeyFile() { return keyFile; }
            public void setKeyFile(String keyFile) { this.keyFile = keyFile; }
            public String getIssuer() { return issuer; }
            public void setIssuer(String issuer) { this.issuer = issuer; }
            public String getProjectId() { return projectId; }
            public void setProjectId(String projectId) { this.projectId = projectId; }
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
