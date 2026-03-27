package com.agentx.platform.domain.execution.model;

import java.util.Objects;

public record TaskRun(
        String runId,
        String taskId,
        String agentInstanceId,
        TaskRunStatus status,
        RunKind runKind,
        String contextSnapshotId
) {

    public TaskRun {
        Objects.requireNonNull(runId, "runId must not be null");
        Objects.requireNonNull(taskId, "taskId must not be null");
        Objects.requireNonNull(agentInstanceId, "agentInstanceId must not be null");
        Objects.requireNonNull(status, "status must not be null");
        Objects.requireNonNull(runKind, "runKind must not be null");
        Objects.requireNonNull(contextSnapshotId, "contextSnapshotId must not be null");
    }
}
