package com.agentx.agentxbackend.contextpack.domain.model;

import java.time.Instant;
import java.util.List;

public record TaskSkill(
    String snapshotId,
    String skillId,
    String taskId,
    Instant generatedAt,
    List<String> sourceFragments,
    List<String> toolpackAssumptions,
    List<String> conventions,
    List<String> recommendedCommands,
    List<String> pitfalls,
    List<String> stopRules,
    List<String> expectedOutputs
) {
}
