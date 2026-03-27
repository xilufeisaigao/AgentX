package com.agentx.platform.domain.execution.model;

import java.util.Objects;

public record GitWorkspace(
        String workspaceId,
        String runId,
        String taskId,
        GitWorkspaceStatus status,
        String repoRoot,
        String worktreePath,
        String branchName,
        String baseCommit,
        String headCommit,
        String mergeCommit,
        CleanupStatus cleanupStatus
) {

    public GitWorkspace {
        Objects.requireNonNull(workspaceId, "workspaceId must not be null");
        Objects.requireNonNull(runId, "runId must not be null");
        Objects.requireNonNull(taskId, "taskId must not be null");
        Objects.requireNonNull(status, "status must not be null");
        Objects.requireNonNull(repoRoot, "repoRoot must not be null");
        Objects.requireNonNull(worktreePath, "worktreePath must not be null");
        Objects.requireNonNull(branchName, "branchName must not be null");
        Objects.requireNonNull(baseCommit, "baseCommit must not be null");
        Objects.requireNonNull(cleanupStatus, "cleanupStatus must not be null");
    }
}
