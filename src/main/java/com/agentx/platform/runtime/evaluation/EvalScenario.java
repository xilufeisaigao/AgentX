package com.agentx.platform.runtime.evaluation;

import java.util.List;
import java.util.Objects;

public record EvalScenario(
        String scenarioId,
        String title,
        String requirementSeed,
        String description,
        List<String> expectedFacts,
        List<String> expectedSnippetRefs,
        List<String> expectedToolPathPrefixes,
        List<String> expectedNodeOrder,
        boolean repoContextRequired
) {

    private static final List<String> DEFAULT_NODE_ORDER = List.of(
            "requirement",
            "ticket-gate",
            "architect",
            "task-graph",
            "worker-manager",
            "coding",
            "merge-gate",
            "verify"
    );

    public EvalScenario {
        Objects.requireNonNull(scenarioId, "scenarioId must not be null");
        Objects.requireNonNull(title, "title must not be null");
        requirementSeed = requirementSeed == null ? "" : requirementSeed;
        description = description == null ? "" : description;
        expectedFacts = List.copyOf(Objects.requireNonNull(expectedFacts, "expectedFacts must not be null"));
        expectedSnippetRefs = List.copyOf(Objects.requireNonNull(expectedSnippetRefs, "expectedSnippetRefs must not be null"));
        expectedToolPathPrefixes = List.copyOf(Objects.requireNonNull(expectedToolPathPrefixes, "expectedToolPathPrefixes must not be null"));
        expectedNodeOrder = expectedNodeOrder == null || expectedNodeOrder.isEmpty()
                ? DEFAULT_NODE_ORDER
                : List.copyOf(expectedNodeOrder);
    }
}
