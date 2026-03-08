package com.agentx.agentxbackend.workspace.application.port.in;

import com.agentx.agentxbackend.workspace.domain.model.GitWorkspace;

public interface WorkspaceUseCase {

    GitWorkspace allocate(
        String runId,
        String sessionId,
        String baseCommit,
        String branchName,
        String worktreePath
    );

    void updateTaskBranch(String sessionId, String taskId, String deliveryCommit);

    GitWorkspace release(String runId, String worktreePath);

    GitWorkspace markBroken(String runId);
}
