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
                resp.setInstanceID("wf-1");
                return resp;
            }
        };
        BpmnClient client = new BpmnClient(defaultApi, new BpmnApi(new ApiClient()), PROJECT_ID);

        client.startInstance("order", new Vars(), "ORDER-42");

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
                resp.setInstanceID("wf-1");
                return resp;
            }
        };
        BpmnClient client = new BpmnClient(defaultApi, new BpmnApi(new ApiClient()), PROJECT_ID);

        client.startInstance("order", new Vars());

        assertNull(captured.get().getBusinessId());
    }

    @Test
    void listInstancesPassesBusinessIdFilterAndCursor() throws Exception {
        AtomicReference<String> capturedBusinessId = new AtomicReference<>();
        AtomicReference<Boolean> capturedDraft = new AtomicReference<>();
        AtomicReference<String> capturedCursor = new AtomicReference<>();
        AtomicReference<Integer> capturedLimit = new AtomicReference<>();
        DefaultApi defaultApi = new DefaultApi(new ApiClient()) {
            @Override
            public BpmnInstancePaginatedResponse listBpmnInstances(
                    UUID projectId,
                    String status,
                    Boolean hasIncident,
                    Boolean suspended,
                    OffsetDateTime createdAfter,
                    String businessId,
                    String processId,
                    Integer version,
                    Boolean draft,
                    String cursor,
                    Integer limit) throws ApiException {
                capturedBusinessId.set(businessId);
                capturedDraft.set(draft);
                capturedCursor.set(cursor);
                capturedLimit.set(limit);
                return new BpmnInstancePaginatedResponse();
            }
        };
        BpmnClient client = new BpmnClient(defaultApi, new BpmnApi(new ApiClient()), PROJECT_ID);

        client.listInstances(null, null, null, null, null, null, "ORDER-42", false, "next-page", 7);

        assertEquals("ORDER-42", capturedBusinessId.get());
        assertEquals(false, capturedDraft.get());
        assertEquals("next-page", capturedCursor.get());
        assertEquals(7, capturedLimit.get());
    }

    @Test
    void listUserTasksPassesBusinessIdFilterAndCursor() throws Exception {
        AtomicReference<String> capturedBusinessId = new AtomicReference<>();
        AtomicReference<String> capturedCursor = new AtomicReference<>();
        AtomicReference<String> capturedCallerCursor = new AtomicReference<>();
        BpmnApi bpmnApi = new BpmnApi(new ApiClient()) {
            @Override
            public BpmnUserTaskPaginatedResponse listBpmnUserTasks(
                    UUID projectId,
                    String instanceId,
                    String status,
                    String assignee,
                    String candidateUser,
                    String candidateGroup,
                    String businessId,
                    OffsetDateTime dueAfter,
                    OffsetDateTime dueBefore,
                    String sort,
                    String cursor,
                    Integer limit) throws ApiException {
                capturedBusinessId.set(businessId);
                capturedCursor.set(cursor);
                return new BpmnUserTaskPaginatedResponse();
            }

            @Override
            public BpmnUserTaskPaginatedResponse listBpmnUserTasksForCaller(
                    UUID projectId,
                    OffsetDateTime dueAfter,
                    OffsetDateTime dueBefore,
                    String sort,
                    String cursor,
                    Integer limit) throws ApiException {
                capturedCallerCursor.set(cursor);
                return new BpmnUserTaskPaginatedResponse();
            }
        };
        BpmnClient client = new BpmnClient(new DefaultApi(new ApiClient()), bpmnApi, PROJECT_ID);

        client.listUserTasks(null, null, null, null, null, "ORDER-42", "next-page", null);
        client.listUserTasksForCaller("caller-page", null);

        assertEquals("ORDER-42", capturedBusinessId.get());
        assertEquals("next-page", capturedCursor.get());
        assertEquals("caller-page", capturedCallerCursor.get());
    }

    @Test
    void listUserTasksPassesDueRangeAndSort() throws Exception {
        OffsetDateTime now = OffsetDateTime.parse("2026-11-10T12:00:00Z");
        AtomicReference<String> captured = new AtomicReference<>();
        AtomicReference<String> capturedCaller = new AtomicReference<>();
        BpmnApi bpmnApi = new BpmnApi(new ApiClient()) {
            @Override
            public BpmnUserTaskPaginatedResponse listBpmnUserTasks(
                    UUID projectId, String instanceId, String status, String assignee, String candidateUser,
                    String candidateGroup, String businessId, OffsetDateTime dueAfter, OffsetDateTime dueBefore,
                    String sort, String cursor, Integer limit) throws ApiException {
                captured.set(dueAfter + " " + dueBefore + " " + sort);
                return new BpmnUserTaskPaginatedResponse();
            }

            @Override
            public BpmnUserTaskPaginatedResponse listBpmnUserTasksForCaller(
                    UUID projectId, OffsetDateTime dueAfter, OffsetDateTime dueBefore,
                    String sort, String cursor, Integer limit) throws ApiException {
                capturedCaller.set(dueAfter + " " + dueBefore + " " + sort);
                return new BpmnUserTaskPaginatedResponse();
            }
        };
        BpmnClient client = new BpmnClient(new DefaultApi(new ApiClient()), bpmnApi, PROJECT_ID);

        client.listUserTasks(null, "CREATED", null, null, null, null, null, now, "dueDate", null, null);
        client.listUserTasksForCaller(now, null, "priority", null, null);

        assertEquals("null 2026-11-10T12:00Z dueDate", captured.get());
        assertEquals("2026-11-10T12:00Z null priority", capturedCaller.get());
    }
}
