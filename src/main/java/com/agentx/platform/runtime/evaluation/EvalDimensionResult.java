package com.agentx.platform.runtime.evaluation;

import java.util.List;
import java.util.Map;
import java.util.Objects;

public record EvalDimensionResult(
        EvalDimensionId dimensionId,
        EvalStatus status,
        int score,
        String summary,
        List<EvalFinding> findings,
        Map<String, Object> metrics
) {

    public EvalDimensionResult {
        Objects.requireNonNull(dimensionId, "dimensionId must not be null");
        Objects.requireNonNull(status, "status must not be null");
        Objects.requireNonNull(summary, "summary must not be null");
        findings = List.copyOf(Objects.requireNonNull(findings, "findings must not be null"));
        metrics = Map.copyOf(Objects.requireNonNull(metrics, "metrics must not be null"));
    }
}
