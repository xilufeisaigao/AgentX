package com.agentx.platform.runtime.agentkernel.architect;

import java.util.List;
import java.util.Objects;

public record ArchitectDecision(
        ArchitectDecisionType decision,
        List<String> gaps,
        List<String> questions,
        String summary,
        PlanningGraphSpec planningGraph
) {

    public ArchitectDecision {
        Objects.requireNonNull(decision, "decision must not be null");
        gaps = List.copyOf(Objects.requireNonNull(gaps, "gaps must not be null"));
        questions = List.copyOf(Objects.requireNonNull(questions, "questions must not be null"));
        summary = summary == null || summary.isBlank() ? decision.name() : summary;
    }
}
