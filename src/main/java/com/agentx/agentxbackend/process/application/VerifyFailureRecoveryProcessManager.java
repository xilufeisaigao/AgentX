package com.agentx.agentxbackend.process.application;

import com.agentx.agentxbackend.contextpack.application.port.in.ContextCompileUseCase;
import com.agentx.agentxbackend.execution.application.port.in.RunQueryUseCase;
import com.agentx.agentxbackend.execution.domain.event.RunFinishedEvent;
import com.agentx.agentxbackend.execution.domain.model.RunKind;
import com.agentx.agentxbackend.execution.domain.model.RunStatus;
import com.agentx.agentxbackend.mergegate.application.port.in.MergeGateUseCase;
import com.agentx.agentxbackend.mergegate.domain.model.MergeGateResult;
import com.agentx.agentxbackend.planning.application.port.in.TaskQueryUseCase;
import com.agentx.agentxbackend.planning.application.port.in.TaskStateMutationUseCase;
import com.agentx.agentxbackend.planning.domain.model.TaskStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Objects;

@Component
public class VerifyFailureRecoveryProcessManager {

    private static final Logger log = LoggerFactory.getLogger(VerifyFailureRecoveryProcessManager.class);
    private static final String RECOVERY_RUN_KIND = "IMPL";
    private static final String RECOVERY_TRIGGER = "VERIFY_FAILED_REOPEN";
    private static final String VERIFY_COMMAND_FAILED_PREFIX = "verify command failed:";
    private static final String VERIFY_REASON_MARKER = ", reason=";
    private static final String[] INFRASTRUCTURE_FAILURE_MARKERS = {
        "worker runtime exception",
        "lease expiration",
        "lease expired",
        "worktree does not exist",
        "timed out",
        "timeout",
        "connection refused",
        "failed to connect",
        "cannot connect",
        "i/o error",
        "io error",
        "no such file or directory",
        "no space left on device",
        "broken pipe",
        "context deadline exceeded",
        "docker daemon",
        "docker socket",
        "container not found",
        "container is not running",
        "oci runtime",
        "worktree became dirty"
    };

    private final RunQueryUseCase runQueryUseCase;
    private final MergeGateUseCase mergeGateUseCase;
    private final TaskQueryUseCase taskQueryUseCase;
    private final TaskStateMutationUseCase taskStateMutationUseCase;
    private final ContextCompileUseCase contextCompileUseCase;
    private final int maxInfraRetriesPerCandidate;

    public VerifyFailureRecoveryProcessManager(
        RunQueryUseCase runQueryUseCase,
        MergeGateUseCase mergeGateUseCase,
        TaskQueryUseCase taskQueryUseCase,
        TaskStateMutationUseCase taskStateMutationUseCase,
        ContextCompileUseCase contextCompileUseCase,
        @Value("${agentx.process.verify-failure.max-infra-retries-per-candidate:2}") int maxInfraRetriesPerCandidate
    ) {
        this.runQueryUseCase = runQueryUseCase;
        this.mergeGateUseCase = mergeGateUseCase;
        this.taskQueryUseCase = taskQueryUseCase;
        this.taskStateMutationUseCase = taskStateMutationUseCase;
        this.contextCompileUseCase = contextCompileUseCase;
        this.maxInfraRetriesPerCandidate = Math.max(0, maxInfraRetriesPerCandidate);
    }

    public void onVerifyFailed(RunFinishedEvent event) {
        Objects.requireNonNull(event, "event must not be null");
        if (!isVerifyFailed(event)) {
            return;
        }
        String taskId = normalize(event.taskId());
        String runId = normalize(event.runId());
        String mergeCandidateCommit = normalize(event.baseCommit());
        String failureSummary = summarizeFailure(event);
        if (taskId == null || runId == null) {
            return;
        }
        if (!isDeliveredTask(taskId)) {
            return;
        }

        boolean infrastructureFailure = isInfrastructureFailure(failureSummary);
        if (infrastructureFailure && mergeCandidateCommit != null && canRetryInfrastructureFailure(taskId, mergeCandidateCommit)) {
            try {
                MergeGateResult retryResult = mergeGateUseCase.start(taskId);
                if (retryResult.accepted()) {
                    log.info(
                        "Retried VERIFY after infrastructure failure, taskId={}, failedRunId={}, retryRunId={}",
                        taskId,
                        runId,
                        retryResult.verifyRunId()
                    );
                    return;
                }
                log.warn(
                    "VERIFY infrastructure retry was not accepted, taskId={}, failedRunId={}, reason={}",
                    taskId,
                    runId,
                    retryResult.message()
                );
                return;
            } catch (RuntimeException ex) {
                log.warn(
                    "VERIFY infrastructure retry failed to start, taskId={}, failedRunId={}, reason={}",
                    taskId,
                    runId,
                    ex.getMessage()
                );
            }
        }

        reopenTaskForDebug(taskId, runId, failureSummary, infrastructureFailure);
    }

    private boolean canRetryInfrastructureFailure(String taskId, String mergeCandidateCommit) {
        int attempts = runQueryUseCase.countVerifyRunsByTaskAndBaseCommit(taskId, mergeCandidateCommit);
        int totalAllowedAttempts = 1 + maxInfraRetriesPerCandidate;
        return attempts < totalAllowedAttempts;
    }

    private void reopenTaskForDebug(String taskId, String runId, String failureSummary, boolean infrastructureFailure) {
        try {
            taskStateMutationUseCase.reopenDelivered(taskId);
            contextCompileUseCase.compileTaskContextPack(taskId, RECOVERY_RUN_KIND, RECOVERY_TRIGGER);
            log.info(
                "Reopened task after VERIFY failure, taskId={}, failedRunId={}, infraFailure={}, summary={}",
                taskId,
                runId,
                infrastructureFailure,
                trimForLog(failureSummary)
            );
        } catch (RuntimeException ex) {
            log.error(
                "Failed to reopen task after VERIFY failure, taskId={}, failedRunId={}, reason={}",
                taskId,
                runId,
                ex.getMessage(),
                ex
            );
        }
    }

    private boolean isDeliveredTask(String taskId) {
        return taskQueryUseCase.findTaskById(taskId)
            .map(task -> task.status() == TaskStatus.DELIVERED)
            .orElse(false);
    }

    private static boolean isVerifyFailed(RunFinishedEvent event) {
        if (event.runKind() != RunKind.VERIFY || event.payload() == null) {
            return false;
        }
        String resultStatus = normalize(event.payload().resultStatus());
        if (resultStatus != null) {
            resultStatus = resultStatus.toUpperCase(Locale.ROOT);
        }
        return RunStatus.FAILED.name().equals(resultStatus);
    }

    private static String summarizeFailure(RunFinishedEvent event) {
        if (event.payload() == null) {
            return null;
        }
        return normalize(event.payload().workReport());
    }

    static boolean isInfrastructureFailure(String failureSummary) {
        String normalized = normalize(failureSummary);
        if (normalized == null) {
            return false;
        }
        String lower = normalized.toLowerCase(Locale.ROOT);
        if (containsInfrastructureMarker(lower)) {
            return true;
        }
        if (!lower.contains(VERIFY_COMMAND_FAILED_PREFIX)) {
            return false;
        }
        String reason = extractVerifyFailureReason(lower);
        return containsInfrastructureMarker(reason);
    }

    private static String extractVerifyFailureReason(String failureSummary) {
        String normalized = normalize(failureSummary);
        if (normalized == null) {
            return null;
        }
        int reasonIndex = normalized.indexOf(VERIFY_REASON_MARKER);
        if (reasonIndex < 0) {
            return null;
        }
        return normalized.substring(reasonIndex + VERIFY_REASON_MARKER.length()).trim();
    }

    private static boolean containsInfrastructureMarker(String value) {
        String normalized = normalize(value);
        if (normalized == null) {
            return false;
        }
        String lower = normalized.toLowerCase(Locale.ROOT);
        for (String marker : INFRASTRUCTURE_FAILURE_MARKERS) {
            if (lower.contains(marker)) {
                return true;
            }
        }
        return false;
    }

    private static String trimForLog(String value) {
        String normalized = normalize(value);
        if (normalized == null) {
            return "n/a";
        }
        return normalized.length() <= 280 ? normalized : normalized.substring(0, 280);
    }

    private static String normalize(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }
}
