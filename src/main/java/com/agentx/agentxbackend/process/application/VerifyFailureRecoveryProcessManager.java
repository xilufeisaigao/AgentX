package com.agentx.agentxbackend.process.application;

import com.agentx.agentxbackend.contextpack.application.port.in.ContextCompileUseCase;
import com.agentx.agentxbackend.execution.domain.event.RunFinishedEvent;
import com.agentx.agentxbackend.execution.domain.model.RunKind;
import com.agentx.agentxbackend.planning.application.port.in.PlanningCommandUseCase;
import com.agentx.agentxbackend.planning.application.port.in.TaskQueryUseCase;
import com.agentx.agentxbackend.planning.domain.model.WorkTask;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

@Component
public class VerifyFailureRecoveryProcessManager {

    private static final Logger log = LoggerFactory.getLogger(VerifyFailureRecoveryProcessManager.class);
    private static final String RECOVERY_TEMPLATE_ID = "tmpl.bugfix.v0";
    private static final String RECOVERY_RUN_KIND = "IMPL";
    private static final String RECOVERY_TRIGGER = "VERIFY_FAILED_RECOVERY";

    private final TaskQueryUseCase taskQueryUseCase;
    private final PlanningCommandUseCase planningCommandUseCase;
    private final ContextCompileUseCase contextCompileUseCase;

    public VerifyFailureRecoveryProcessManager(
        TaskQueryUseCase taskQueryUseCase,
        PlanningCommandUseCase planningCommandUseCase,
        ContextCompileUseCase contextCompileUseCase
    ) {
        this.taskQueryUseCase = taskQueryUseCase;
        this.planningCommandUseCase = planningCommandUseCase;
        this.contextCompileUseCase = contextCompileUseCase;
    }

    public void onVerifyFailed(RunFinishedEvent event) {
        Objects.requireNonNull(event, "event must not be null");
        if (!isVerifyFailed(event)) {
            return;
        }
        String taskId = normalize(event.taskId());
        String runId = normalize(event.runId());
        if (taskId == null || runId == null) {
            return;
        }
        Optional<WorkTask> failedTaskOptional = taskQueryUseCase.findTaskById(taskId);
        if (failedTaskOptional.isEmpty()) {
            log.warn(
                "Skip verify-failure recovery task creation because source task is missing, taskId={}, runId={}",
                taskId,
                runId
            );
            return;
        }
        WorkTask failedTask = failedTaskOptional.get();
        String recoveryTitle = buildRecoveryTitle(failedTask, event);
        try {
            WorkTask recoveryTask = planningCommandUseCase.createTask(
                failedTask.moduleId(),
                recoveryTitle,
                RECOVERY_TEMPLATE_ID,
                failedTask.requiredToolpacksJson(),
                List.of()
            );
            contextCompileUseCase.compileTaskContextPack(
                recoveryTask.taskId(),
                RECOVERY_RUN_KIND,
                RECOVERY_TRIGGER
            );
            log.info(
                "Created verify-failure recovery task, sourceTaskId={}, verifyRunId={}, recoveryTaskId={}",
                failedTask.taskId(),
                runId,
                recoveryTask.taskId()
            );
        } catch (RuntimeException ex) {
            log.error(
                "Failed to create verify-failure recovery task, sourceTaskId={}, verifyRunId={}, reason={}",
                failedTask.taskId(),
                runId,
                ex.getMessage(),
                ex
            );
        }
    }

    private static boolean isVerifyFailed(RunFinishedEvent event) {
        if (event.runKind() != RunKind.VERIFY || event.payload() == null) {
            return false;
        }
        String resultStatus = normalize(event.payload().resultStatus());
        return "FAILED".equals(resultStatus);
    }

    private static String buildRecoveryTitle(WorkTask sourceTask, RunFinishedEvent event) {
        String mergeCandidateCommit = shortRef(event.baseCommit(), 12);
        return "Repair verify failure for " + sourceTask.taskId()
            + " (verify_run=" + event.runId()
            + ", candidate=" + mergeCandidateCommit + ")";
    }

    private static String shortRef(String value, int maxLength) {
        String normalized = normalize(value);
        if (normalized == null) {
            return "unknown";
        }
        return normalized.length() <= maxLength
            ? normalized
            : normalized.substring(0, maxLength);
    }

    private static String normalize(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }
}
