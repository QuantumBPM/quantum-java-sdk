package com.quantumbpm.client;

import com.quantumbpm.client.variables.Vars;
import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class TokenProviderTest {

    private HttpServer server;
    private final AtomicReference<String> authorization = new AtomicReference<>("unset");

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
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private QuantumBPM.Builder builder() {
        return QuantumBPM.builder()
                .baseUrl("http://127.0.0.1:" + server.getAddress().getPort())
                .projectId("00000000-0000-0000-0000-000000000001");
    }

    @Test
    void withoutTokenProviderSendsNoAuthorization() throws Exception {
        builder().build().dmn().evaluate("claims", new Vars());

        assertNull(authorization.get());
    }

    @Test
    void tokenProviderSignsRequests() throws Exception {
        builder().tokenProvider(() -> "abc").build().dmn().evaluate("claims", new Vars());

        assertEquals("Bearer abc", authorization.get());
    }
}
