package com.labex.labexagent.evaluation;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.stereotype.Component;

class ReleaseRegressionGateTest {
    private final ReleaseRegressionGate gate = new ReleaseRegressionGate();

    @Test
    void blocksSafetyAndMeaningfulQualityRegressions() {
        var baseline = new EvaluationMetrics(100, 90, 80, 1_000, 100, 0);
        assertFalse(gate.evaluate(baseline, new EvaluationMetrics(100, 89, 80, 1_000, 100, 1)).allowed());
        assertFalse(gate.evaluate(baseline, new EvaluationMetrics(100, 86, 80, 1_000, 100, 0)).allowed());
        assertTrue(gate.evaluate(baseline, new EvaluationMetrics(100, 88, 78, 1_200, 125, 0)).allowed());
    }

    @Test
    void isRegisteredForEvaluationRunnerInjection() {
        assertTrue(ReleaseRegressionGate.class.isAnnotationPresent(Component.class));
    }
}