package com.agentx.platform.domain.execution.model;

import com.agentx.platform.domain.shared.model.AggregateRoot;
import com.agentx.platform.domain.shared.model.JsonPayload;

import java.time.LocalDateTime;
import java.util.Objects;

public record TaskRun(
        String runId,
        String taskId,
        String agentInstanceId,
        TaskRunStatus status,
        RunKind runKind,
        String contextSnapshotId,
        LocalDateTime leaseUntil,
        LocalDateTime lastHeartbeatAt,
        LocalDateTime startedAt,
        LocalDateTime finishedAt,
        JsonPayload executionContractJson
) implements AggregateRoot<String> {

    public TaskRun {
        Objects.requireNonNull(runId, "runId must not be null");
        Objects.requireNonNull(taskId, "taskId must not be null");
        Objects.requireNonNull(agentInstanceId, "agentInstanceId must not be null");
        Objects.requireNonNull(status, "status must not be null");
        Objects.requireNonNull(runKind, "runKind must not be null");
        Objects.requireNonNull(contextSnapshotId, "contextSnapshotId must not be null");
        Objects.requireNonNull(leaseUntil, "leaseUntil must not be null");
        Objects.requireNonNull(lastHeartbeatAt, "lastHeartbeatAt must not be null");
        Objects.requireNonNull(startedAt, "startedAt must not be null");
        Objects.requireNonNull(executionContractJson, "executionContractJson must not be null");
    }

    @Override
    public String aggregateId() {
        return runId;
    }
}
