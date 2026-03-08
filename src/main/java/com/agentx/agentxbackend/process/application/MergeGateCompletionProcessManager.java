package com.agentx.agentxbackend.process.application;

import com.agentx.agentxbackend.mergegate.application.port.in.MergeGateCompletionUseCase;
import com.agentx.agentxbackend.planning.application.port.in.TaskQueryUseCase;
import com.agentx.agentxbackend.planning.domain.model.TaskStatus;
import org.springframework.stereotype.Component;

@Component
public class MergeGateCompletionProcessManager {

    private final MergeGateCompletionUseCase mergeGateCompletionUseCase;
    private final TaskQueryUseCase taskQueryUseCase;

    public MergeGateCompletionProcessManager(
        MergeGateCompletionUseCase mergeGateCompletionUseCase,
        TaskQueryUseCase taskQueryUseCase
    ) {
        this.mergeGateCompletionUseCase = mergeGateCompletionUseCase;
        this.taskQueryUseCase = taskQueryUseCase;
    }

    public void onVerifySucceeded(String taskId, String runId, String mergeCandidateCommit) {
        if (taskId == null || taskId.isBlank()) {
            throw new IllegalArgumentException("taskId must not be blank");
        }
        if (taskQueryUseCase.findTaskById(taskId.trim())
            .map(task -> task.status() == TaskStatus.DELIVERED)
            .orElse(false)) {
            mergeGateCompletionUseCase.completeVerifySuccess(
                taskId.trim(),
                runId,
                mergeCandidateCommit
            );
        }
    }
}
