package com.quantumbpm.spring;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModelDeployerTest {

    private static final String DRAFT_ID = "7f6c4a2e-0b1d-4c3e-9f8a-1b2c3d4e5f60";
    private static final ObjectMapper JSON = new ObjectMapper();

    private HttpServer server;
    private final List<String> calls = new CopyOnWriteArrayList<>();
    private final Map<String, String> xmlByName = new ConcurrentHashMap<>();
    private final Map<String, Integer> failures = new ConcurrentHashMap<>();
    private ApplicationContextRunner runner;

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::answer);
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

    @Test
    void deploysDecisionsBeforeProcesses() {
        runner.withUserConfiguration(Models.class).run(context -> {
            assertNull(context.getStartupFailure());
            assertEquals(List.of("create dmn rating.dmn", "deploy dmn", "create bpmn order.bpmn", "deploy bpmn"), calls);
            assertTrue(xmlByName.get("rating.dmn").contains("id=\"rating\""));
            assertTrue(xmlByName.get("order.bpmn").contains("id=\"order\""));
        });
    }

    @Test
    void unchangedFilesAreSkipped() {
        failures.put("create dmn", 409);
        failures.put("create bpmn", 409);
        runner.withUserConfiguration(Models.class).run(context -> {
            assertNull(context.getStartupFailure());
            assertEquals(List.of("create dmn rating.dmn", "create bpmn order.bpmn"), calls);
        });
    }

    @Test
    void deployRaceDeletesTheDraft() {
        failures.put("deploy dmn", 409);
        failures.put("deploy bpmn", 409);
        runner.withUserConfiguration(Models.class).run(context -> {
            assertNull(context.getStartupFailure());
            assertEquals(List.of(
                    "create dmn rating.dmn", "deploy dmn", "delete dmn",
                    "create bpmn order.bpmn", "deploy bpmn", "delete bpmn"), calls);
        });
    }

    @Test
    void rejectedModelFailsStartupAndDeletesTheDraft() {
        failures.put("deploy bpmn", 400);
        runner.withUserConfiguration(Models.class).run(context -> {
            String message = failureMessage(context.getStartupFailure());
            assertTrue(message.contains("cannot deploy order.bpmn: HTTP 400"), message);
            assertEquals(List.of(
                    "create dmn rating.dmn", "deploy dmn",
                    "create bpmn order.bpmn", "deploy bpmn", "delete bpmn"), calls);
        });
    }

    @Test
    void rejectedCreateFailsStartup() {
        failures.put("create dmn", 400);
        runner.withUserConfiguration(Models.class).run(context -> {
            String message = failureMessage(context.getStartupFailure());
            assertTrue(message.contains("cannot save rating.dmn: HTTP 400"), message);
            assertEquals(List.of("create dmn rating.dmn"), calls);
        });
    }

    @Test
    void fileThatIsNoModelFailsStartup() {
        runner.withUserConfiguration(NotAModel.class).run(context -> {
            String message = failureMessage(context.getStartupFailure());
            assertTrue(message.contains("notes.txt is neither .bpmn nor .dmn"), message);
            assertTrue(calls.isEmpty());
        });
    }

    @Test
    void patternWithoutFilesFailsStartup() {
        runner.withUserConfiguration(NoMatch.class).run(context -> {
            String message = failureMessage(context.getStartupFailure());
            assertTrue(message.contains("classpath*:missing/*.dmn matches no files"), message);
        });
    }

    @Test
    void disabledPropertyDeploysNothing() {
        runner.withUserConfiguration(Models.class)
                .withPropertyValues("quantumbpm.deployment.enabled=false")
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    assertTrue(context.getBeansOfType(ModelDeployer.class).isEmpty());
                    assertTrue(calls.isEmpty());
                });
    }

    @Test
    void withoutAnnotationNothingIsDeployed() {
        runner.run(context -> {
            assertNull(context.getStartupFailure());
            assertTrue(calls.isEmpty());
        });
    }

    @Configuration(proxyBeanMethods = false)
    @Deployment(resources = {"classpath:models/order.bpmn", "classpath:models/rating.dmn"})
    static class Models {
    }

    @Configuration(proxyBeanMethods = false)
    @Deployment(resources = "classpath:models/notes.txt")
    static class NotAModel {
    }

    @Configuration(proxyBeanMethods = false)
    @Deployment(resources = "classpath*:missing/*.dmn")
    static class NoMatch {
    }

    private void answer(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        String kind = path.contains("/dmn/") ? "dmn" : "bpmn";
        String op = path.endsWith("/deploy") ? "deploy"
                : exchange.getRequestMethod().equals("DELETE") ? "delete" : "create";
        String call = op + " " + kind;
        if (op.equals("create")) {
            JsonNode body = JSON.readTree(exchange.getRequestBody());
            xmlByName.put(body.path("name").asText(), body.path("xml").asText());
            calls.add(call + " " + body.path("name").asText());
        } else {
            calls.add(call);
        }

        Integer failure = failures.get(call);
        if (failure != null) {
            send(exchange, failure, "{\"code\":\"REFUSED\",\"message\":\"refused by the test\"}");
        } else if (op.equals("create")) {
            send(exchange, 201, "{\"id\":\"" + DRAFT_ID + "\",\"name\":\"draft\",\"xml\":\"\",\"definitionsID\":\"d\","
                    + "\"isDeployed\":false,\"createdAt\":\"2026-11-18T10:00:00Z\"}");
        } else if (op.equals("deploy") && kind.equals("dmn")) {
            send(exchange, 200, "{\"id\":\"" + DRAFT_ID + "\",\"name\":\"draft\",\"xml\":\"\",\"definitionsID\":\"d\","
                    + "\"isDeployed\":true,\"version\":1,\"createdAt\":\"2026-11-18T10:00:00Z\"}");
        } else {
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        }
    }

    private static void send(HttpExchange exchange, int status, String json) throws IOException {
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }

    private static String failureMessage(Throwable failure) {
        assertNotNull(failure, "startup should fail");
        StringBuilder messages = new StringBuilder();
        for (Throwable t = failure; t != null; t = t.getCause()) {
            messages.append(t.getMessage()).append('\n');
        }
        return messages.toString();
    }
}
