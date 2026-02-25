package com.agentx.agentxbackend.mergegate.domain.model;

public record MergeGateResult(
    String taskId,
    String verifyRunId,
    boolean accepted,
    String message
) {
}
