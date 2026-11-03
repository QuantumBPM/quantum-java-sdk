package com.quantumbpm.client.auth;

import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClientCredentialsTokenProviderTest {

    private HttpServer server;
    private final List<Map<String, String>> forms = new CopyOnWriteArrayList<>();
    private final List<String> contentTypes = new CopyOnWriteArrayList<>();
    private volatile int status = 200;
    private volatile String expiresIn = ",\"expires_in\":3600";

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/token", exchange -> {
            String raw = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            forms.add(parseForm(raw));
            contentTypes.add(exchange.getRequestHeaders().getFirst("Content-Type"));
            String body = status == 200
                    ? "{\"access_token\":\"token-" + forms.size() + "\",\"token_type\":\"Bearer\"" + expiresIn + "}"
                    : "{\"error\":\"invalid_client\"}";
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private String tokenUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/token";
    }

    private ClientCredentialsTokenProvider provider() {
        return new ClientCredentialsTokenProvider(tokenUrl(), "my-client", "my-secret");
    }

    private static Map<String, String> parseForm(String raw) {
        return Arrays.stream(raw.split("&"))
                .map(pair -> pair.split("=", 2))
                .collect(Collectors.toMap(
                        kv -> URLDecoder.decode(kv[0], StandardCharsets.UTF_8),
                        kv -> URLDecoder.decode(kv[1], StandardCharsets.UTF_8)));
    }

    @Test
    void sendsScopesAndAudience() {
        new ClientCredentialsTokenProvider(tokenUrl(), "my-client", "my-secret",
                List.of("openid", "bpm.api"), "quantumbpm-api").getToken();

        assertEquals(List.of("application/x-www-form-urlencoded"), contentTypes);
        assertEquals(Map.of(
                "grant_type", "client_credentials",
                "client_id", "my-client",
                "client_secret", "my-secret",
                "scope", "openid bpm.api",
                "audience", "quantumbpm-api"), forms.get(0));
    }

    @Test
    void omitsScopeAndAudienceWhenNotSet() {
        provider().getToken();

        assertEquals(Map.of(
                "grant_type", "client_credentials",
                "client_id", "my-client",
                "client_secret", "my-secret"), forms.get(0));
    }

    @ParameterizedTest
    @ValueSource(strings = {",\"expires_in\":3600", ",\"expires_in\":\"3600\"", ""})
    void reusesCachedToken(String expiresInField) {
        expiresIn = expiresInField;
        ClientCredentialsTokenProvider provider = provider();

        assertEquals("token-1", provider.getToken());
        assertEquals("token-1", provider.getToken());
        assertEquals(1, forms.size());
    }

    @Test
    void refreshesTokenNearExpiry() {
        // 30 seconds is inside the 60 second refresh margin.
        expiresIn = ",\"expires_in\":30";
        ClientCredentialsTokenProvider provider = provider();

        assertEquals("token-1", provider.getToken());
        assertEquals("token-2", provider.getToken());
        assertEquals(2, forms.size());
    }

    @Test
    void concurrentCallersShareOneRequest() throws Exception {
        ClientCredentialsTokenProvider provider = provider();
        ExecutorService pool = Executors.newFixedThreadPool(5);
        try {
            Callable<String> call = provider::getToken;
            for (Future<String> token : pool.invokeAll(Collections.nCopies(5, call))) {
                assertEquals("token-1", token.get());
            }
        } finally {
            pool.shutdown();
        }
        assertEquals(1, forms.size());
    }

    @Test
    void throwsTokenExceptionOn401() {
        status = 401;

        TokenException e = assertThrows(TokenException.class, () -> provider().getToken());
        assertTrue(e.getMessage().contains("401"), e.getMessage());
        assertTrue(e.getMessage().contains("invalid_client"), e.getMessage());
    }

    @Test
    void requiresCredentials() {
        assertThrows(IllegalArgumentException.class,
                () -> new ClientCredentialsTokenProvider(tokenUrl(), "my-client", ""));
    }
}
