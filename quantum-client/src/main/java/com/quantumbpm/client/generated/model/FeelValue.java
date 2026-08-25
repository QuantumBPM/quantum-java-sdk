package com.quantumbpm.client.generated.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * A FEEL-typed value as it appears in DMN inputs and outputs.
 *
 * <p>Hand-written stub. The OpenAPI spec defines FeelValue as a oneOf union
 * of FEEL primitives (number / string / boolean / list / context).
 * OpenAPI Generator's Java oneOf template emits invalid code for unions whose
 * variants are parameterized types (e.g. {@code List<FeelValue>}), so we
 * skip generation of this file via {@code .openapi-generator-ignore} and
 * provide this thin pass-through.</p>
 *
 * <p>The class holds the raw JSON-decoded value as an {@link Object}.
 * Jackson serializes it transparently through {@link #getValue()}, and
 * deserializes any JSON node into a FeelValue via {@link #of(Object)}.
 * Construct via {@link #of(Object)} or use the empty constructor + setter.</p>
 */
public class FeelValue {

    private Object value;

    public FeelValue() {
    }

    public FeelValue(Object value) {
        this.value = value;
    }

    @JsonCreator
    public static FeelValue of(Object value) {
        return new FeelValue(value);
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
        return "FeelValue(" + value + ")";
    }

    /**
     * No-op stub - FeelValue only appears in request bodies, never in URL
     * query strings. The generated parent models call this method on every
     * field; we need the symbol to exist for the surrounding code to
     * compile.
     */
    public String toUrlQueryString(String prefix) {
        return "";
    }

    public String toUrlQueryString() {
        return "";
    }
}
