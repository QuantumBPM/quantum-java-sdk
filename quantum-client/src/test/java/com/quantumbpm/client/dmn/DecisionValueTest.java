package com.quantumbpm.client.dmn;

import com.quantumbpm.client.generated.model.EvaluationResult;
import com.quantumbpm.client.generated.model.FeelValue;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Map;

import static com.quantumbpm.client.dmn.DmnClient.decisionValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DecisionValueTest {

    record CoverCheck(BigDecimal limit, boolean withinCover, String message) {}

    private static EvaluationResult value(Object v) {
        EvaluationResult r = new EvaluationResult();
        r.setValue(FeelValue.of(v));
        return r;
    }

    @Test
    void decodesStructureIntoRecord() {
        var result = Map.of("Cover check", value(Map.of(
                "limit", new BigDecimal("12000.10"), "withinCover", true, "message", "Fully covered")));

        CoverCheck cover = decisionValue(result, "Cover check", CoverCheck.class);

        assertEquals(new CoverCheck(new BigDecimal("12000.10"), true, "Fully covered"), cover);
    }

    @Test
    void decodesScalar() {
        var result = Map.of("Route", value("REVIEW"));

        assertEquals("REVIEW", decisionValue(result, "Route", String.class));
    }

    @Test
    void failedDecisionThrowsItsError() {
        EvaluationResult failed = new EvaluationResult();
        failed.setError("cannot perform arithmetic operation Minus with null operand");
        var result = Map.of("Cover check", failed);

        var e = assertThrows(IllegalStateException.class, () -> decisionValue(result, "Cover check", CoverCheck.class));
        assertTrue(e.getMessage().contains("null operand"));
    }

    @Test
    void missingDecisionThrows() {
        var result = Map.of("Claim triage", value(Map.of()));

        var e = assertThrows(IllegalArgumentException.class, () -> decisionValue(result, "Cover check", CoverCheck.class));
        assertTrue(e.getMessage().contains("Claim triage"));
    }
}
