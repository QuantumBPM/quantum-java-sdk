package com.quantumbpm.client.workers;

import com.quantumbpm.client.generated.model.ExternalJob;
import com.quantumbpm.client.variables.Vars;

import java.util.Map;

/**
 * Job is the work unit handed to a {@link Handler}. It wraps the generated
 * {@link ExternalJob} and exposes the input variables decoded into a Vars.
 *
 * @param <T> the typed shape of the job's input variables; defaults to
 *            {@code Map<String, Object>} when the handler doesn't request
 *            typed dispatch.
 */
public final class Job<T> {

    private final ExternalJob raw;
    private final Vars vars;
    private final T typed;

    public Job(ExternalJob raw, Vars vars, T typed) {
        this.raw = raw;
        this.vars = vars;
        this.typed = typed;
    }

    /** Stable key for the activity execution. Path parameter for completion. */
    public String executionKey() {
        return raw.getExecutionKey();
    }

    /** Workflow ID of the originating instance. */
    public String workflowId() {
        return raw.getWorkflowID();
    }

    /** Worker selector — the task type the handler was registered for. */
    public String taskType() {
        return raw.getTaskType();
    }

    /** Input variables resolved by the service task at activity entry. */
    public Vars vars() {
        return vars;
    }

    /**
     * Vars decoded into {@code T}. When the handler didn't request typed
     * dispatch, this returns a {@code Map<String, Object>} cast to T.
     */
    public T typed() {
        return typed;
    }

    /** Static metadata attached at design time on the service task. */
    public Map<String, String> headers() {
        return raw.getHeaders() == null ? Map.of() : raw.getHeaders();
    }

    /** Underlying generated record for low-level access. */
    public ExternalJob raw() {
        return raw;
    }
}
