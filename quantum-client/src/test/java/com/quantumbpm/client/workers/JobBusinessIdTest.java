package com.quantumbpm.client.workers;

import com.quantumbpm.client.generated.model.ExternalJob;
import com.quantumbpm.client.variables.Vars;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class JobBusinessIdTest {

    @Test
    void exposesBusinessIdWhenSetOnRaw() {
        ExternalJob raw = new ExternalJob();
        raw.setBusinessId("ORDER-42");

        Job<Object> job = new Job<>(raw, new Vars(), null);

        assertEquals("ORDER-42", job.businessId());
        assertEquals("ORDER-42", job.raw().getBusinessId());
    }

    @Test
    void businessIdIsNullWhenAbsent() {
        ExternalJob raw = new ExternalJob();

        Job<Object> job = new Job<>(raw, new Vars(), null);

        assertNull(job.businessId());
    }
}
