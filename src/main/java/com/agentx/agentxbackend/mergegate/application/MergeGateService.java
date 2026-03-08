package com.agentx.agentxbackend.mergegate.application;

import com.agentx.agentxbackend.mergegate.application.port.in.MergeGateUseCase;
import com.agentx.agentxbackend.mergegate.application.port.out.GitClientPort;
import com.agentx.agentxbackend.mergegate.application.port.out.IntegrationLaneLockPort;
import com.agentx.agentxbackend.mergegate.application.port.out.RunCreationPort;
import com.agentx.agentxbackend.mergegate.application.port.out.TaskStateMutationPort;
import com.agentx.agentxbackend.mergegate.domain.model.MergeCandidate;
import com.agentx.agentxbackend.mergegate.domain.model.MergeGateResult;
import org.springframework.stereotype.Service;

@Service
public class MergeGateService implements MergeGateUseCase {

    private static final String INTEGRATION_LANE_LOCK_KEY = "integration-lane";

    private final TaskStateMutationPort taskStateMutationPort;
    private final RunCreationPort runCreationPort;
    private final GitClientPort gitClientPort;
    private final IntegrationLaneLockPort integrationLaneLockPort;

    public MergeGateService(
        TaskStateMutationPort taskStateMutationPort,
        RunCreationPort runCreationPort,
        GitClientPort gitClientPort,
        IntegrationLaneLockPort integrationLaneLockPort
    ) {
        this.taskStateMutationPort = taskStateMutationPort;
        this.runCreationPort = runCreationPort;
        this.gitClientPort = gitClientPort;
        this.integrationLaneLockPort = integrationLaneLockPort;
    }

    @Override
    public MergeGateResult start(String taskId) {
        String normalizedTaskId = requireNotBlank(taskId, "taskId");
        boolean locked = integrationLaneLockPort.tryAcquire(INTEGRATION_LANE_LOCK_KEY);
        if (!locked) {
            return new MergeGateResult(
                normalizedTaskId,
                null,
                false,
                "Integration lane is busy. Retry later."
            );
        }
        try {
            String sessionId = taskStateMutationPort.resolveSessionIdByTaskId(normalizedTaskId);
            String mainHeadBefore = gitClientPort.readMainHead(sessionId);
            MergeCandidate candidate = gitClientPort.rebaseTaskBranch(sessionId, normalizedTaskId, mainHeadBefore);
            String mergeCandidateCommit = candidate.mergeCandidateCommit();
            String verifyRunId = runCreationPort.createVerifyRun(normalizedTaskId, mergeCandidateCommit);
            String evidenceRef = candidate.evidenceRef();
            String message = evidenceRef == null || evidenceRef.isBlank()
                ? "VERIFY run created for merge candidate " + mergeCandidateCommit
                : "VERIFY run created for merge candidate " + mergeCandidateCommit + " (" + evidenceRef + ")";
            return new MergeGateResult(
                normalizedTaskId,
                verifyRunId,
                true,
                message
            );
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
}
