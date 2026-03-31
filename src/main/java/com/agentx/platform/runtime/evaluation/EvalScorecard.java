package com.agentx.platform.runtime.evaluation;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public record EvalScorecard(
        String scenarioId,
        String workflowRunId,
        EvalStatus overallStatus,
        LocalDateTime generatedAt,
        List<EvalDimensionResult> dimensions,
        List<EvalFinding> hardGates,
        List<EvalFinding> findings,
        Map<String, String> artifactRefs,
        Map<String, Object> comparison
) {

    public EvalScorecard {
        Objects.requireNonNull(scenarioId, "scenarioId must not be null");
        Objects.requireNonNull(workflowRunId, "workflowRunId must not be null");
        Objects.requireNonNull(overallStatus, "overallStatus must not be null");
        Objects.requireNonNull(generatedAt, "generatedAt must not be null");
        dimensions = List.copyOf(Objects.requireNonNull(dimensions, "dimensions must not be null"));
        hardGates = List.copyOf(Objects.requireNonNull(hardGates, "hardGates must not be null"));
        findings = List.copyOf(Objects.requireNonNull(findings, "findings must not be null"));
        artifactRefs = Map.copyOf(Objects.requireNonNull(artifactRefs, "artifactRefs must not be null"));
        comparison = Map.copyOf(Objects.requireNonNull(comparison, "comparison must not be null"));
    }
}
