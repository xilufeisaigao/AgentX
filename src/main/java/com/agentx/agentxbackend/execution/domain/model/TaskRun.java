package com.agentx.agentxbackend.execution.domain.model;

import java.time.Instant;

public record TaskRun(
    String runId,
    String taskId,
    String workerId,
    RunStatus status,
    RunKind runKind,
    String contextSnapshotId,
    Instant leaseUntil,
    Instant lastHeartbeatAt,
    Instant startedAt,
    Instant finishedAt,
    String taskSkillRef,
    String toolpacksSnapshotJson,
    String baseCommit,
    String branchName,
    String worktreePath,
    Instant createdAt,
    Instant updatedAt
) {
}
