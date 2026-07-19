package com.quantumbpm.client.variables;

import com.quantumbpm.client.QuantumBPM;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/**
 * Pins exact-decimal round-trips through the SDK. FEEL numbers are exact
 * decimals server-side; the literal below is unrepresentable as a double,
 * so any hop that narrows through double changes the digits and fails here.
 */
class VarsDecimalTest {

    private static final String EXACT = "1234567890.123456789012345678";

    @Test
    void apiClientMapperParsesFloatsAsBigDecimal() throws Exception {
        QuantumBPM client = QuantumBPM.builder()
                .baseUrl("http://localhost:8080")
                .projectId(UUID.fromString("00000000-0000-0000-0000-000000000001"))
                .tokenProvider(() -> "test-token")
                .build();

        Map<?, ?> parsed = client.raw().getObjectMapper()
                .readValue("{\"amount\": " + EXACT + "}", Map.class);

        Object amount = parsed.get("amount");
        assertInstanceOf(BigDecimal.class, amount,
                "response deserialization must use BigDecimal, not double");
        assertEquals(new BigDecimal(EXACT), amount,
                "the exact literal must survive parsing digit-for-digit");
    }

    @Test
    void varsPreservesBigDecimalThroughWireMapAndTypedGet() {
        Vars vars = Vars.fromWireMap(Map.of("amount", new BigDecimal(EXACT)));

        assertEquals(new BigDecimal(EXACT), vars.get("amount", BigDecimal.class),
                "typed get must not narrow the decimal");
        assertEquals(new BigDecimal(EXACT), vars.toWireMap().get("amount"),
                "wire-map round-trip must keep the BigDecimal untouched");
    }
}
