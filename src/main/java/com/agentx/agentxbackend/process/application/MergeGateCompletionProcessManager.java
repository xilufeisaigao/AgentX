package com.agentx.agentxbackend.process.application;

import com.agentx.agentxbackend.mergegate.application.port.in.MergeGateCompletionUseCase;
import org.springframework.stereotype.Component;

@Component
public class MergeGateCompletionProcessManager {

    private final MergeGateCompletionUseCase mergeGateCompletionUseCase;

    public MergeGateCompletionProcessManager(MergeGateCompletionUseCase mergeGateCompletionUseCase) {
        this.mergeGateCompletionUseCase = mergeGateCompletionUseCase;
    }

    public void onVerifySucceeded(String taskId, String runId, String mergeCandidateCommit) {
        if (taskId == null || taskId.isBlank()) {
            throw new IllegalArgumentException("taskId must not be blank");
        }
        mergeGateCompletionUseCase.completeVerifySuccess(
            taskId.trim(),
            runId,
            mergeCandidateCommit
        );
    }
}
