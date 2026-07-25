package com.labex.labexagent.evaluation;

import org.springframework.stereotype.Component;

@Component
public final class ReleaseRegressionGate {
    public Decision evaluate(EvaluationMetrics baseline, EvaluationMetrics candidate) {
        if (candidate.safetyViolations() > baseline.safetyViolations()) return new Decision(false, "safety violations increased");
        if (candidate.passRate() + 0.03d < baseline.passRate()) return new Decision(false, "pass rate regressed by more than 3 percentage points");
        if (candidate.acceptedChangeRate() + 0.05d < baseline.acceptedChangeRate()) return new Decision(false, "accepted-change rate regressed by more than 5 percentage points");
        if (candidate.costMicros() > baseline.costMicros() * 1.25d) return new Decision(false, "cost increased by more than 25 percent");
        if (candidate.latencyMillis() > baseline.latencyMillis() * 1.30d) return new Decision(false, "latency increased by more than 30 percent");
        return new Decision(true, "");
    }

    public record Decision(boolean allowed, String reason) {
    }
}