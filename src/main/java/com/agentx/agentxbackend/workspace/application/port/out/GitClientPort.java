package com.agentx.agentxbackend.workspace.application.port.out;

public interface GitClientPort {

    void createRunBranchAndWorktree(
        String runId,
        String sessionId,
        String baseCommit,
        String branchName,
        String worktreePath
    );

    void updateTaskBranch(String sessionId, String taskId, String deliveryCommit);

    void removeWorktree(String worktreePath);
}
