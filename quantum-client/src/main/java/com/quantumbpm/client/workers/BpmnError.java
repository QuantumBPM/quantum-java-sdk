package com.quantumbpm.client.workers;

import com.quantumbpm.client.variables.Vars;

/**
 * Throw from a handler to fail the job with a BPMN error code. The runtime
 * translates it into a {@code ThrowError} call against the originating
 * service task - matching boundary error events on the task can then route
 * the exception in the BPMN model.
 */
public class BpmnError extends RuntimeException {

    private final String code;
    private final Vars variables;

    public BpmnError(String code) {
        this(code, null);
    }

    public BpmnError(String code, Vars variables) {
        super("bpmn error: " + code);
        this.code = code;
        this.variables = variables;
    }

    public String code() {
        return code;
    }

    public Vars variables() {
        return variables;
    }
}
