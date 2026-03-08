package com.agentx.agentxbackend.execution.application.port.out;

public interface WorkspacePort {

    String allocateWorkspace(
        String runId,
        String sessionId,
        String taskId,
        String baseCommit,
        String branchName
    );

    void updateTaskBranch(String sessionId, String taskId, String deliveryCommit);

    void releaseWorkspace(String runId, String worktreePath);
}
