package com.agentx.agentxbackend.mergegate.application;

import com.agentx.agentxbackend.mergegate.application.port.in.MergeGateCompletionUseCase;
import com.agentx.agentxbackend.mergegate.application.port.out.GitClientPort;
import com.agentx.agentxbackend.mergegate.application.port.out.IntegrationLaneLockPort;
import com.agentx.agentxbackend.mergegate.application.port.out.TaskStateMutationPort;
import org.springframework.stereotype.Service;

@Service
public class MergeGateCompletionService implements MergeGateCompletionUseCase {

    private static final String INTEGRATION_LANE_LOCK_KEY = "integration-lane";

    private final TaskStateMutationPort taskStateMutationPort;
    private final GitClientPort gitClientPort;
    private final IntegrationLaneLockPort integrationLaneLockPort;

    public MergeGateCompletionService(
        TaskStateMutationPort taskStateMutationPort,
        GitClientPort gitClientPort,
        IntegrationLaneLockPort integrationLaneLockPort
    ) {
        this.taskStateMutationPort = taskStateMutationPort;
        this.gitClientPort = gitClientPort;
        this.integrationLaneLockPort = integrationLaneLockPort;
    }

    @Override
    public void completeVerifySuccess(String taskId, String verifyRunId, String mergeCandidateCommit) {
        String normalizedTaskId = requireNotBlank(taskId, "taskId");
        String normalizedVerifyRunId = requireNotBlank(verifyRunId, "verifyRunId");
        String normalizedMergeCandidateCommit = requireNotBlank(mergeCandidateCommit, "mergeCandidateCommit");

        if (!integrationLaneLockPort.tryAcquire(INTEGRATION_LANE_LOCK_KEY)) {
            throw new IllegalStateException("Integration lane is busy while completing verify run: " + normalizedVerifyRunId);
        }
        try {
            String sessionId = taskStateMutationPort.resolveSessionIdByTaskId(normalizedTaskId);
            fastForwardMainOrRequestReplan(sessionId, normalizedTaskId, normalizedVerifyRunId, normalizedMergeCandidateCommit);
            gitClientPort.ensureDeliveryTagOnMain(sessionId, normalizedMergeCandidateCommit);
            taskStateMutationPort.markDone(normalizedTaskId);
        } finally {
            integrationLaneLockPort.release(INTEGRATION_LANE_LOCK_KEY);
        }
    }

    private static String requireNotBlank(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value.trim();
    }

    private void fastForwardMainOrRequestReplan(
        String sessionId,
        String taskId,
        String verifyRunId,
        String mergeCandidateCommit
    ) {
        try {
            gitClientPort.fastForwardMain(sessionId, mergeCandidateCommit);
        } catch (IllegalStateException ex) {
            if (!isFastForwardRace(ex.getMessage())) {
                throw ex;
            }
            throw new MergeGateReplanRequiredException(
                "Verify run " + verifyRunId + " became stale because main moved before completion. taskId=" + taskId,
                ex
            );
        }
    }

    private static boolean isFastForwardRace(String message) {
        if (message == null || message.isBlank()) {
            return false;
        }
        String normalized = message.trim().toLowerCase(java.util.Locale.ROOT);
        return normalized.contains("not possible to fast-forward")
            || normalized.contains("ff-only")
            || normalized.contains("fast-forward, aborting");
    }
}

