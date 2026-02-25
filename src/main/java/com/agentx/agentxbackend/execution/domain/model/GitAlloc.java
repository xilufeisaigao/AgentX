package com.agentx.agentxbackend.execution.domain.model;

public record GitAlloc(
    String baseCommit,
    String branchName,
    String worktreePath
) {
}
