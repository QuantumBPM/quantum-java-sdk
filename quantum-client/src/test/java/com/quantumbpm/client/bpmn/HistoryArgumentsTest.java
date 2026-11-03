package com.quantumbpm.client.bpmn;

import com.quantumbpm.client.generated.ApiClient;
import com.quantumbpm.client.generated.api.BpmnApi;
import com.quantumbpm.client.generated.api.DefaultApi;
import com.quantumbpm.client.generated.model.BpmnActivityState;
import com.quantumbpm.client.generated.model.BpmnHistoryPage;
import com.quantumbpm.client.generated.model.BpmnProcessVersion;

import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class HistoryArgumentsTest {

    private static final UUID PROJECT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @Test
    void historyAndVersionForwardEachArgument() throws Exception {
        List<List<Object>> calls = new ArrayList<>();
        BpmnApi bpmnApi = new BpmnApi(new ApiClient()) {
            @Override
            public BpmnHistoryPage getBpmnInstanceHistory(UUID projectId, String instanceId, Long fromSeq, Long beforeSeq, Integer limit) {
                calls.add(Arrays.asList(projectId, instanceId, fromSeq, beforeSeq, limit));
                return new BpmnHistoryPage();
            }

            @Override
            public BpmnActivityState getBpmnInstanceHistoryEntry(UUID projectId, String instanceId, Long seq) {
                calls.add(Arrays.asList(projectId, instanceId, seq));
                return new BpmnActivityState();
            }

            @Override
            public BpmnProcessVersion getBpmnProcessVersion(UUID projectId, String processId, Integer version, OffsetDateTime createdAfter) {
                calls.add(Arrays.asList(projectId, processId, version, createdAfter));
                return new BpmnProcessVersion();
            }
        };
        BpmnClient client = new BpmnClient(new DefaultApi(new ApiClient()), bpmnApi, PROJECT_ID);
        OffsetDateTime since = OffsetDateTime.parse("2026-01-01T00:00:00Z");

        client.getInstanceHistory("inst-1", 4L, 9L, 50);
        client.getInstanceHistory("inst-1", null, 9L, null);
        client.getInstanceHistoryEntry("inst-1", 7);
        client.getProcessVersion("order_flow", 3, since);

        assertEquals(Arrays.asList(PROJECT_ID, "inst-1", 4L, 9L, 50), calls.get(0));
        assertEquals(Arrays.asList(PROJECT_ID, "inst-1", null, 9L, null), calls.get(1));
        assertEquals(Arrays.asList(PROJECT_ID, "inst-1", 7L), calls.get(2));
        assertEquals(Arrays.asList(PROJECT_ID, "order_flow", 3, since), calls.get(3));
    }
}
