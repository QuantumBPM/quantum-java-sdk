package com.quantumbpm.client.dmn;

import com.quantumbpm.client.generated.ApiClient;
import com.quantumbpm.client.generated.api.DefaultApi;
import com.quantumbpm.client.generated.model.EvaluateStoredRequest;
import com.quantumbpm.client.generated.model.EvaluationResult;
import com.quantumbpm.client.variables.Vars;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static com.quantumbpm.client.dmn.DmnClient.withBusinessId;
import static org.junit.jupiter.api.Assertions.assertEquals;

class DmnBusinessIdTest {

    private static final UUID PROJECT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @Test
    void evaluateSendsBusinessId() throws Exception {
        AtomicReference<EvaluateStoredRequest> captured = new AtomicReference<>();
        DefaultApi defaultApi = new DefaultApi(new ApiClient()) {
            @Override
            public Map<String, EvaluationResult> evaluateByDefinitionsID(
                    UUID projectId,
                    String definitionsId,
                    EvaluateStoredRequest body,
                    Integer version) {
                captured.set(body);
                return Map.of();
            }
        };
        DmnClient client = new DmnClient(defaultApi, PROJECT_ID);

        client.evaluate("def-1", new Vars(), withBusinessId("ORDER-42"));

        assertEquals("ORDER-42", captured.get().getBusinessId());
    }

    @Test
    void evaluateByIdSendsBusinessId() throws Exception {
        AtomicReference<EvaluateStoredRequest> captured = new AtomicReference<>();
        DefaultApi defaultApi = new DefaultApi(new ApiClient()) {
            @Override
            public Map<String, EvaluationResult> evaluateStored(
                    UUID projectId,
                    UUID definitionId,
                    EvaluateStoredRequest body) {
                captured.set(body);
                return Map.of();
            }
        };
        DmnClient client = new DmnClient(defaultApi, PROJECT_ID);

        client.evaluateById(UUID.randomUUID(), new Vars(), withBusinessId("ORDER-42"));

        assertEquals("ORDER-42", captured.get().getBusinessId());
    }
}
