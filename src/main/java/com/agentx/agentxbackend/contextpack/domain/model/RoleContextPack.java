package com.agentx.agentxbackend.contextpack.domain.model;

import java.time.Instant;
import java.util.List;

public record RoleContextPack(
    String packId,
    String sessionId,
    String role,
    Instant generatedAt,
    List<String> sourceRefs,
    Summary summary,
    List<String> nextActions
) {
    public record Summary(
        String goal,
        List<String> hardConstraints,
        List<String> currentState,
        List<String> openQuestions
    ) {
    }
}
