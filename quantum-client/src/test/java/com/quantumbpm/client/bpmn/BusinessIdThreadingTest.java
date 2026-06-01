package com.quantumbpm.client.bpmn;

import com.quantumbpm.client.generated.ApiClient;
import com.quantumbpm.client.generated.ApiException;
import com.quantumbpm.client.generated.api.BpmnApi;
import com.quantumbpm.client.generated.api.DefaultApi;
import com.quantumbpm.client.generated.model.BpmnInstancePaginatedResponse;
import com.quantumbpm.client.generated.model.BpmnUserTaskPaginatedResponse;
import com.quantumbpm.client.generated.model.StartBpmnInstance201Response;
import com.quantumbpm.client.generated.model.StartBpmnInstanceRequest;
import com.quantumbpm.client.variables.Vars;

import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class BusinessIdThreadingTest {

    private static final UUID PROJECT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @Test
    void startInstanceSendsBusinessId() throws Exception {
        AtomicReference<StartBpmnInstanceRequest> captured = new AtomicReference<>();
        DefaultApi defaultApi = new DefaultApi(new ApiClient()) {
            @Override
            public StartBpmnInstance201Response startBpmnInstance(UUID projectId, StartBpmnInstanceRequest body) {
                captured.set(body);
                StartBpmnInstance201Response resp = new StartBpmnInstance201Response();
                resp.setWorkflowID("wf-1");
                return resp;
            }
        };
        BpmnClient client = new BpmnClient(defaultApi, new BpmnApi(new ApiClient()), PROJECT_ID);

        client.startInstance(UUID.randomUUID(), new Vars(), "ORDER-42");

        assertEquals("ORDER-42", captured.get().getBusinessId());
    }

    @Test
    void startInstanceOmitsBusinessIdInLegacyOverload() throws Exception {
        AtomicReference<StartBpmnInstanceRequest> captured = new AtomicReference<>();
        DefaultApi defaultApi = new DefaultApi(new ApiClient()) {
            @Override
            public StartBpmnInstance201Response startBpmnInstance(UUID projectId, StartBpmnInstanceRequest body) {
                captured.set(body);
                StartBpmnInstance201Response resp = new StartBpmnInstance201Response();
                resp.setWorkflowID("wf-1");
                return resp;
            }
        };
        BpmnClient client = new BpmnClient(defaultApi, new BpmnApi(new ApiClient()), PROJECT_ID);

        client.startInstance(UUID.randomUUID(), new Vars());

        assertNull(captured.get().getBusinessId());
    }

    @Test
    void listInstancesPassesBusinessIdFilter() throws Exception {
        AtomicReference<String> capturedBusinessId = new AtomicReference<>();
        DefaultApi defaultApi = new DefaultApi(new ApiClient()) {
            @Override
            public BpmnInstancePaginatedResponse listBpmnInstances(
                    UUID projectId,
                    UUID definitionId,
                    String status,
                    Boolean hasIncident,
                    Boolean suspended,
                    OffsetDateTime createdAfter,
                    String businessId,
                    Integer page,
                    Integer pageSize) throws ApiException {
                capturedBusinessId.set(businessId);
                return new BpmnInstancePaginatedResponse();
            }
        };
        BpmnClient client = new BpmnClient(defaultApi, new BpmnApi(new ApiClient()), PROJECT_ID);

        client.listInstances(null, null, null, null, null, "ORDER-42", null, null);

        assertEquals("ORDER-42", capturedBusinessId.get());
    }

    @Test
    void listUserTasksPassesBusinessIdFilter() throws Exception {
        AtomicReference<String> capturedBusinessId = new AtomicReference<>();
        BpmnApi bpmnApi = new BpmnApi(new ApiClient()) {
            @Override
            public BpmnUserTaskPaginatedResponse listBpmnUserTasks(
                    UUID projectId,
                    String workflowId,
                    String status,
                    String assignee,
                    String candidateUser,
                    String candidateGroup,
                    String businessId,
                    Integer page,
                    Integer pageSize) throws ApiException {
                capturedBusinessId.set(businessId);
                return new BpmnUserTaskPaginatedResponse();
            }
        };
        BpmnClient client = new BpmnClient(new DefaultApi(new ApiClient()), bpmnApi, PROJECT_ID);

        client.listUserTasks(null, null, null, null, null, "ORDER-42", null, null);

        assertEquals("ORDER-42", capturedBusinessId.get());
    }
}
