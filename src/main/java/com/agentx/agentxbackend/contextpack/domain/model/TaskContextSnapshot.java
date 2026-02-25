package com.agentx.agentxbackend.contextpack.domain.model;

import java.time.Instant;

public record TaskContextSnapshot(
    String snapshotId,
    String taskId,
    String runKind,
    TaskContextSnapshotStatus status,
    String triggerType,
    String sourceFingerprint,
    String taskContextRef,
    String taskSkillRef,
    String errorCode,
    String errorMessage,
    Instant compiledAt,
    Instant retainedUntil,
    Instant createdAt,
    Instant updatedAt
) {
}
