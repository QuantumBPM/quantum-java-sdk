package com.quantumbpm.client.workers;

import com.quantumbpm.client.generated.model.ExternalJob;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanBuilder;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.propagation.TextMapGetter;
import java.util.Map;

/**
 * OpenTelemetry integration for the worker runtime. Uses the global
 * OpenTelemetry instance, which is a no-op unless the application has
 * configured and registered an OTel SDK - so workers that don't opt into
 * tracing pay nothing and a missing trace context degrades to a fresh
 * (unrecorded) span.
 */
final class JobTracing {

    private static final Tracer TRACER = GlobalOpenTelemetry.getTracer("quantumbpm/java-sdk");

    private static final TextMapGetter<Map<String, String>> CARRIER_GETTER =
            new TextMapGetter<>() {
                @Override
                public Iterable<String> keys(Map<String, String> carrier) {
                    return carrier.keySet();
                }

                @Override
                public String get(Map<String, String> carrier, String key) {
                    return carrier == null ? null : carrier.get(key);
                }
            };

    private JobTracing() {}

    /**
     * Continues the originating process instance's trace (when the job carries
     * trace context) and starts a worker span. Make it current with
     * {@code span.makeCurrent()} for the duration of the handler.
     */
    static Span startJobSpan(ExternalJob raw, String taskType) {
        Context parent = Context.current();
        Map<String, String> traceContext = raw.getTraceContext();
        if (traceContext != null && !traceContext.isEmpty()) {
            parent =
                    GlobalOpenTelemetry.getPropagators()
                            .getTextMapPropagator()
                            .extract(parent, traceContext, CARRIER_GETTER);
        }
        SpanBuilder builder =
                TRACER.spanBuilder("bpmn.external-task.execute")
                        .setSpanKind(SpanKind.CONSUMER)
                        .setParent(parent)
                        .setAttribute("bpmn.task_type", taskType)
                        .setAttribute("bpmn.node_id", raw.getNodeID())
                        .setAttribute("bpmn.process_instance_id", raw.getWorkflowID())
                        .setAttribute("bpmn.execution_key", raw.getExecutionKey());
        if (raw.getBusinessId() != null && !raw.getBusinessId().isEmpty()) {
            builder.setAttribute("bpmn.business_id", raw.getBusinessId());
        }
        return builder.startSpan();
    }
}
