package com.quantumbpm.client.workers;

import com.quantumbpm.client.variables.Vars;

/**
 * Processes a single job. Return value semantics:
 * <ul>
 *   <li>returns Vars (or null) → SDK calls {@code Complete}.
 *   <li>throws {@link BpmnError}  → SDK calls {@code ThrowError} with the supplied code.
 *   <li>throws any other exception → SDK calls {@code ThrowError} with
 *       {@code WORKER_ERROR}; retry budget decrements.
 * </ul>
 */
@FunctionalInterface
public interface Handler<T> {
    Vars handle(Job<T> job) throws Exception;
}
