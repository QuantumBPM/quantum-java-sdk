package com.quantumbpm.spring;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a method as a BPMN external-job handler. The Spring autoconfig
 * scans annotated methods on Spring-managed beans and registers them with a
 * managed {@link com.quantumbpm.client.workers.Worker} that's started after
 * application context refresh and stopped on shutdown.
 *
 * <p>Method signature must be:</p>
 * <pre>{@code
 * @JobWorker(type = "send-email")
 * public Vars handle(Job<EmailJob> job) { ... }
 * }</pre>
 *
 * <p>The single parameter must be a {@link com.quantumbpm.client.workers.Job}.
 * Its type parameter, if any, drives typed deserialization of the job's
 * input variables. The return type must be
 * {@link com.quantumbpm.client.variables.Vars} or {@code void}; {@code void}
 * completes the job with no variables.</p>
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface JobWorker {

    /** BPMN service-task {@code taskType} this handler processes. */
    String type();

    /** Maximum jobs in flight for this task type. Default 1. */
    int maxJobs() default 1;

    /** Long-poll wait — duration string ({@code 30s}, {@code 2m}). Default {@code 30s}. */
    String pollTimeout() default "30s";

    /** Exclusive lock duration on each acquired job. Default {@code 30s}. */
    String lockDuration() default "30s";
}
