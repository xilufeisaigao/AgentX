package com.agentx.agentxbackend.execution.infrastructure.external;

import com.agentx.agentxbackend.execution.application.port.out.WorkspacePort;
import com.agentx.agentxbackend.workspace.application.port.in.WorkspaceUseCase;
import org.springframework.stereotype.Component;

@Component
public class WorkspacePortAdapter implements WorkspacePort {

    private final WorkspaceUseCase workspaceUseCase;

    public WorkspacePortAdapter(WorkspaceUseCase workspaceUseCase) {
        this.workspaceUseCase = workspaceUseCase;
    }

    @Override
    public String allocateWorkspace(
        String runId,
        String sessionId,
        String taskId,
        String baseCommit,
        String branchName
    ) {
        String worktreePath = "worktrees/" + sessionId + "/" + runId;
        workspaceUseCase.allocate(runId, sessionId, baseCommit, branchName, worktreePath);
        return worktreePath;
    }

    @Override
    public void updateTaskBranch(String sessionId, String taskId, String deliveryCommit) {
        workspaceUseCase.updateTaskBranch(sessionId, taskId, deliveryCommit);
    }

    @Override
    public void releaseWorkspace(String runId, String worktreePath) {
        workspaceUseCase.release(runId, worktreePath);
    }
}
