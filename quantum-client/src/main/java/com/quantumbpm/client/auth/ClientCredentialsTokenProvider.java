package com.quantumbpm.client.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;

/**
 * Fetches access tokens from any OIDC provider with the OAuth 2.0 client credentials
 * grant, caching each until less than 60 seconds of it remain. Safe for concurrent use.
 */
public final class ClientCredentialsTokenProvider implements TokenProvider {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final long REFRESH_MARGIN_SECONDS = 60;
    private static final double DEFAULT_LIFETIME_SECONDS = 300;

    private final URI tokenUrl;
    private final String form;
    private final HttpClient httpClient;

    private final ReentrantLock lock = new ReentrantLock();
    private String cachedToken;
    private Instant cachedExpiry = Instant.EPOCH;

    /** Requests tokens without a scope or audience parameter. */
    public ClientCredentialsTokenProvider(String tokenUrl, String clientId, String clientSecret) {
        this(tokenUrl, clientId, clientSecret, List.of(), null);
    }

    /**
     * Sends {@code scopes} space-joined as the scope parameter and {@code audience} as the
     * audience parameter, each only when non-empty. Some providers, such as Auth0, require an audience.
     */
    public ClientCredentialsTokenProvider(String tokenUrl, String clientId, String clientSecret,
                                          List<String> scopes, String audience) {
        if (isBlank(tokenUrl) || isBlank(clientId) || isBlank(clientSecret)) {
            throw new IllegalArgumentException("quantumbpm: tokenUrl, clientId and clientSecret are required");
        }

        Map<String, String> params = new LinkedHashMap<>();
        params.put("grant_type", "client_credentials");
        params.put("client_id", clientId);
        params.put("client_secret", clientSecret);
        if (scopes != null && !scopes.isEmpty()) {
            params.put("scope", String.join(" ", scopes));
        }
        if (!isBlank(audience)) {
            params.put("audience", audience);
        }

        this.tokenUrl = URI.create(tokenUrl);
        this.form = params.entrySet().stream()
                .map(e -> URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8) + "="
                        + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8))
                .collect(Collectors.joining("&"));
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    }

    @Override
    public String getToken() {
        lock.lock();
        try {
            if (cachedToken != null && Instant.now().isBefore(cachedExpiry.minusSeconds(REFRESH_MARGIN_SECONDS))) {
                return cachedToken;
            }
            return requestToken();
        } finally {
            lock.unlock();
        }
    }

    private String requestToken() {
        HttpRequest request = HttpRequest.newBuilder(tokenUrl)
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(form))
                .build();

        Instant requestedAt = Instant.now();
        JsonNode body;
        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() > 299) {
                throw new TokenException("token request failed (" + response.statusCode() + "): " + response.body());
            }
            body = MAPPER.readTree(response.body());
        } catch (IOException e) {
            throw new TokenException("token request failed: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new TokenException("token request interrupted", e);
        }

        JsonNode accessToken = body.get("access_token");
        if (accessToken == null || !accessToken.isTextual() || accessToken.asText().isEmpty()) {
            throw new TokenException("token response has no access_token");
        }
        JsonNode expiresIn = body.get("expires_in");
        double lifetime = expiresIn == null || expiresIn.isNull()
                ? DEFAULT_LIFETIME_SECONDS
                : expiresIn.asDouble(Double.NaN);
        if (Double.isNaN(lifetime)) {
            throw new TokenException("token response has an invalid expires_in: " + expiresIn);
        }

        cachedToken = accessToken.asText();
        cachedExpiry = requestedAt.plusMillis((long) (lifetime * 1000));
        return cachedToken;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
