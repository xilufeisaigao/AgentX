package com.agentx.platform.domain.execution.model;

import java.time.LocalDateTime;
import java.util.Objects;

public record TaskContextSnapshot(
        String snapshotId,
        String taskId,
        RunKind runKind,
        TaskContextSnapshotStatus status,
        String triggerType,
        String sourceFingerprint,
        String taskContextRef,
        String taskSkillRef,
        LocalDateTime retainedUntil
) {

    public TaskContextSnapshot {
        Objects.requireNonNull(snapshotId, "snapshotId must not be null");
        Objects.requireNonNull(taskId, "taskId must not be null");
        Objects.requireNonNull(runKind, "runKind must not be null");
        Objects.requireNonNull(status, "status must not be null");
        Objects.requireNonNull(triggerType, "triggerType must not be null");
        Objects.requireNonNull(sourceFingerprint, "sourceFingerprint must not be null");
        Objects.requireNonNull(retainedUntil, "retainedUntil must not be null");
    }
}
