package com.quantumbpm.client.dmn;

import com.quantumbpm.client.generated.ApiClient;
import com.quantumbpm.client.generated.api.DefaultApi;
import com.quantumbpm.client.generated.model.CreateDefinitionRequest;
import com.quantumbpm.client.generated.model.Definition;

import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class DmnDefinitionsTest {

    private static final UUID PROJECT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID DEFINITION_ID = UUID.fromString("7f6c4a2e-0b1d-4c3e-9f8a-1b2c3d4e5f60");

    @Test
    void createAndDeployCallTheProjectEndpoints() throws Exception {
        AtomicReference<CreateDefinitionRequest> created = new AtomicReference<>();
        AtomicReference<String> deployed = new AtomicReference<>();
        Definition draft = new Definition().id(DEFINITION_ID).isDeployed(false);
        Definition live = new Definition().id(DEFINITION_ID).isDeployed(true).version(3);
        DefaultApi defaultApi = new DefaultApi(new ApiClient()) {
            @Override
            public Definition createDefinition(UUID projectId, CreateDefinitionRequest body) {
                assertEquals(PROJECT_ID, projectId);
                created.set(body);
                return draft;
            }

            @Override
            public Definition deployDefinition(UUID projectId, UUID definitionId) {
                deployed.set(projectId + "/" + definitionId);
                return live;
            }
        };
        DmnClient client = new DmnClient(defaultApi, PROJECT_ID);

        assertSame(draft, client.createDefinition("rating.dmn", "<definitions/>"));
        assertEquals("rating.dmn", created.get().getName());
        assertEquals("<definitions/>", created.get().getXml());

        assertSame(live, client.deployDefinition(DEFINITION_ID));
        assertEquals(PROJECT_ID + "/" + DEFINITION_ID, deployed.get());
    }
}
