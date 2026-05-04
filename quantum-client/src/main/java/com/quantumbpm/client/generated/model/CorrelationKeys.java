package com.quantumbpm.client.generated.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Correlation keys for routing a published BPMN message.
 *
 * <p>Hand-written stub. The OpenAPI spec defines CorrelationKeys as a oneOf
 * union — primitive (string / number / boolean) or an object map. OpenAPI
 * Generator's Java oneOf template emits invalid code for unions with
 * parameterized variants, so we skip generation of this file via
 * {@code .openapi-generator-ignore} and provide this thin pass-through.</p>
 *
 * <p>Pass any JSON-serializable value: a primitive, or a {@code Map} for
 * multi-key correlation. Jackson serializes through {@link #getValue()};
 * deserialization goes through {@link #of(Object)}.</p>
 */
public class CorrelationKeys {

    private Object value;

    public CorrelationKeys() {
    }

    public CorrelationKeys(Object value) {
        this.value = value;
    }

    @JsonCreator
    public static CorrelationKeys of(Object value) {
        return new CorrelationKeys(value);
    }

    @JsonValue
    public Object getValue() {
        return value;
    }

    public void setValue(Object value) {
        this.value = value;
    }

    @Override
    public String toString() {
        return "CorrelationKeys(" + value + ")";
    }

    /**
     * No-op stub — CorrelationKeys only appears in request bodies, never in
     * URL query strings. The generated parent models call this method on
     * every field; we need the symbol to exist for the surrounding code to
     * compile.
     */
    public String toUrlQueryString(String prefix) {
        return "";
    }

    public String toUrlQueryString() {
        return "";
    }
}
