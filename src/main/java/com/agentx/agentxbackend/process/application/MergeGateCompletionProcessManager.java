package com.agentx.agentxbackend.process.application;

import com.agentx.agentxbackend.mergegate.application.MergeGateReplanRequiredException;
import com.agentx.agentxbackend.mergegate.application.port.in.MergeGateUseCase;
import com.agentx.agentxbackend.mergegate.application.port.in.MergeGateCompletionUseCase;
import com.agentx.agentxbackend.mergegate.domain.model.MergeGateResult;
import com.agentx.agentxbackend.planning.application.port.in.TaskQueryUseCase;
import com.agentx.agentxbackend.planning.domain.model.TaskStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class MergeGateCompletionProcessManager {

    private static final Logger log = LoggerFactory.getLogger(MergeGateCompletionProcessManager.class);

    private final MergeGateCompletionUseCase mergeGateCompletionUseCase;
    private final MergeGateUseCase mergeGateUseCase;
    private final TaskQueryUseCase taskQueryUseCase;

    public MergeGateCompletionProcessManager(
        MergeGateCompletionUseCase mergeGateCompletionUseCase,
        MergeGateUseCase mergeGateUseCase,
        TaskQueryUseCase taskQueryUseCase
    ) {
        this.mergeGateCompletionUseCase = mergeGateCompletionUseCase;
        this.mergeGateUseCase = mergeGateUseCase;
        this.taskQueryUseCase = taskQueryUseCase;
    }

    public void onVerifySucceeded(String taskId, String runId, String mergeCandidateCommit) {
        if (taskId == null || taskId.isBlank()) {
            throw new IllegalArgumentException("taskId must not be blank");
        }
        if (taskQueryUseCase.findTaskById(taskId.trim())
            .map(task -> task.status() == TaskStatus.DELIVERED)
            .orElse(false)) {
            try {
                mergeGateCompletionUseCase.completeVerifySuccess(
                    taskId.trim(),
                    runId,
                    mergeCandidateCommit
                );
            } catch (MergeGateReplanRequiredException ex) {
                restartMergeGate(taskId.trim(), runId, ex);
            }
        }
    }

    private void restartMergeGate(String taskId, String runId, MergeGateReplanRequiredException ex) {
        try {
            MergeGateResult result = mergeGateUseCase.start(taskId);
            if (result.accepted()) {
                log.info(
                    "Restarted merge gate after stale verify success, taskId={}, staleRunId={}, retryRunId={}",
                    taskId,
                    runId,
                    result.verifyRunId()
                );
                return;
            }
            log.warn(
                "Merge gate restart was not accepted after stale verify success, taskId={}, staleRunId={}, reason={}",
                taskId,
                runId,
                result.message()
            );
        } catch (RuntimeException retryEx) {
            log.warn(
                "Merge gate restart failed after stale verify success, taskId={}, staleRunId={}, reason={}",
                taskId,
                runId,
                retryEx.getMessage()
            );
        }
        throw ex;
    }
}
