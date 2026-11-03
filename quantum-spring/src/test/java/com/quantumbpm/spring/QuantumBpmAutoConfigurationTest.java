package com.quantumbpm.spring;

import com.quantumbpm.client.QuantumBPM;
import com.quantumbpm.client.auth.ClientCredentialsTokenProvider;
import com.quantumbpm.client.auth.StaticTokenProvider;
import com.quantumbpm.client.auth.TokenProvider;
import com.quantumbpm.client.variables.Vars;
import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QuantumBpmAutoConfigurationTest {

    private HttpServer server;
    private final AtomicReference<String> authorization = new AtomicReference<>("unset");
    private final AtomicReference<String> tokenForm = new AtomicReference<>();
    private ApplicationContextRunner runner;

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.createContext("/token", exchange -> {
            tokenForm.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] body = "{\"access_token\":\"cc-token\",\"expires_in\":3600}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();

        runner = new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(QuantumBpmAutoConfiguration.class))
                .withPropertyValues(
                        "quantumbpm.base-url=http://127.0.0.1:" + server.getAddress().getPort(),
                        "quantumbpm.project-id=00000000-0000-0000-0000-000000000001",
                        "quantumbpm.worker.enabled=false");
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    // The devserver needs no auth, so neither property set must still start.
    @Test
    void withoutAuthStartsAndSendsNoAuthorization() {
        runner.run(context -> {
            assertNull(context.getStartupFailure());
            assertTrue(context.getBeansOfType(TokenProvider.class).isEmpty());

            context.getBean(QuantumBPM.class).dmn().evaluate("claims", new Vars());
            assertNull(authorization.get());
        });
    }

    @Test
    void tokenPropertySignsRequests() {
        runner.withPropertyValues("quantumbpm.token=abc").run(context -> {
            assertInstanceOf(StaticTokenProvider.class, context.getBean(TokenProvider.class));

            context.getBean(QuantumBPM.class).dmn().evaluate("claims", new Vars());
            assertEquals("Bearer abc", authorization.get());
        });
    }

    @Test
    void clientCredentialsPropertiesSignRequests() {
        runner.withPropertyValues(
                "quantumbpm.auth.client-credentials.token-url=http://127.0.0.1:" + server.getAddress().getPort() + "/token",
                "quantumbpm.auth.client-credentials.client-id=my-client",
                "quantumbpm.auth.client-credentials.client-secret=my-secret",
                "quantumbpm.auth.client-credentials.scopes=openid,profile",
                "quantumbpm.auth.client-credentials.audience=quantumbpm-api").run(context -> {
            assertInstanceOf(ClientCredentialsTokenProvider.class, context.getBean(TokenProvider.class));

            context.getBean(QuantumBPM.class).dmn().evaluate("claims", new Vars());
            assertEquals("Bearer cc-token", authorization.get());
            assertEquals("grant_type=client_credentials&client_id=my-client&client_secret=my-secret"
                    + "&scope=openid+profile&audience=quantumbpm-api", tokenForm.get());
        });
    }

    @Test
    void clientCredentialsWinOverToken() {
        runner.withPropertyValues(
                "quantumbpm.token=abc",
                "quantumbpm.auth.client-credentials.token-url=http://127.0.0.1:1/token",
                "quantumbpm.auth.client-credentials.client-id=my-client",
                "quantumbpm.auth.client-credentials.client-secret=my-secret").run(context ->
                assertInstanceOf(ClientCredentialsTokenProvider.class, context.getBean(TokenProvider.class)));
    }

    @Test
    void blankTokenCountsAsNoAuth() {
        runner.withPropertyValues("quantumbpm.token=").run(context -> {
            assertNull(context.getStartupFailure());
            assertTrue(context.getBeansOfType(TokenProvider.class).isEmpty());
        });
    }

    @Test
    void userTokenProviderBeanWins() {
        runner.withBean(TokenProvider.class, () -> () -> "own").run(context -> {
            context.getBean(QuantumBPM.class).dmn().evaluate("claims", new Vars());
            assertEquals("Bearer own", authorization.get());
        });
    }
}
