package com.quantumbpm.client.variables;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Vars holds a set of named variables. The same type is used for DMN
 * evaluation contexts, BPMN process variables, and external job payloads.
 *
 * <p>Vars is a thin wrapper around {@code Map<String, Object>} with helpers
 * for typed access, chainable construction, and conversion to/from the wire
 * shapes the generated client uses.</p>
 */
public final class Vars {

    // USE_BIG_DECIMAL_FOR_FLOATS: FEEL numbers are exact decimals; double
    // parsing would silently narrow anything beyond ~15 significant digits.
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);

    private final Map<String, Object> data;

    public Vars() {
        this.data = new LinkedHashMap<>();
    }

    public Vars(Map<String, Object> data) {
        this.data = new LinkedHashMap<>(data == null ? Map.of() : data);
    }

    /** Convenience constructor for chained {@code .set(...)} calls. */
    public static Vars of() {
        return new Vars();
    }

    /** Build a Vars from a plain map. {@code null} produces an empty Vars. */
    public static Vars from(Map<String, Object> data) {
        return new Vars(data);
    }

    /** Lift a wire-shape variables map into a Vars value. */
    public static Vars fromWireMap(Map<String, Object> data) {
        return new Vars(data);
    }

    /** Assign {@code name=value} and return this Vars for chaining. */
    public Vars set(String name, Object value) {
        data.put(name, value);
        return this;
    }

    /** Return the raw value at {@code name}, or {@code null} when not set. */
    public Object lookup(String name) {
        return data.get(name);
    }

    /**
     * Return the value at {@code name} decoded as {@code type}. Throws when
     * the variable is not set or the value cannot be coerced.
     */
    public <T> T get(String name, Class<T> type) {
        if (!data.containsKey(name)) {
            throw new IllegalArgumentException("variable '" + name + "' not set");
        }
        return MAPPER.convertValue(data.get(name), type);
    }

    /** Decode the entire Vars into a value of {@code type} (typically a POJO). */
    public <T> T as(Class<T> type) {
        return MAPPER.convertValue(data, type);
    }

    /** Number of variables. */
    public int size() {
        return data.size();
    }

    /** Whether this Vars is empty. */
    public boolean isEmpty() {
        return data.isEmpty();
    }

    /** Return a defensive copy of the underlying map. */
    public Map<String, Object> toMap() {
        return new LinkedHashMap<>(data);
    }

    /**
     * Convert to the FeelContext shape DMN evaluate endpoints accept. Uses a
     * Jackson value round-trip so nested Java records, dates, and enums
     * collapse to JSON-friendly primitives.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public Map toFeelContext() {
        return MAPPER.convertValue(data, Map.class);
    }

    /**
     * Convert to the optional {@code variables} map BPMN endpoints accept.
     * Returns {@code null} for an empty Vars so the optional field can be
     * omitted from the request body.
     */
    public Map<String, Object> toWireMap() {
        if (data.isEmpty()) {
            return null;
        }
        return MAPPER.convertValue(data, MAPPER.getTypeFactory().constructMapType(LinkedHashMap.class, String.class, Object.class));
    }

    @Override
    public String toString() {
        return "Vars" + data;
    }
}
