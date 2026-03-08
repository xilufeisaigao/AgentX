package com.agentx.agentxbackend.process.application;

import com.agentx.agentxbackend.execution.domain.event.RunFinishedEvent;
import com.agentx.agentxbackend.execution.domain.model.RunKind;
import com.agentx.agentxbackend.planning.application.port.in.TaskQueryUseCase;
import com.agentx.agentxbackend.planning.application.port.in.TaskStateMutationUseCase;
import com.agentx.agentxbackend.planning.domain.model.TaskStatus;
import com.agentx.agentxbackend.planning.domain.model.TaskTemplateId;
import com.agentx.agentxbackend.planning.domain.model.WorkTask;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Objects;

@Component
public class RunFinishedProcessManager {

    private final TaskStateMutationUseCase taskStateMutationUseCase;
    private final TaskQueryUseCase taskQueryUseCase;

    public RunFinishedProcessManager(
        TaskStateMutationUseCase taskStateMutationUseCase,
        TaskQueryUseCase taskQueryUseCase
    ) {
        this.taskStateMutationUseCase = taskStateMutationUseCase;
        this.taskQueryUseCase = taskQueryUseCase;
    }

    public void handle(RunFinishedEvent event) {
        Objects.requireNonNull(event, "event must not be null");
        if (event.payload() == null) {
            return;
        }
        String resultStatus = normalizeStatus(event.payload().resultStatus());
        if (event.runKind() == RunKind.IMPL) {
            handleImplRun(event.taskId(), resultStatus);
            return;
        }
        if (event.runKind() == RunKind.VERIFY) {
            handleVerifyRun(event.taskId(), resultStatus);
        }
    }

    private void handleImplRun(String taskId, String resultStatus) {
        if ("SUCCEEDED".equals(resultStatus)) {
            taskStateMutationUseCase.markDelivered(taskId);
            return;
        }
        if ("FAILED".equals(resultStatus) || "CANCELLED".equals(resultStatus)) {
            taskStateMutationUseCase.releaseAssignment(taskId);
        }
    }

    private void handleVerifyRun(String taskId, String resultStatus) {
        if (!isAssignedVerifyTask(taskId)) {
            return;
        }
        if ("SUCCEEDED".equals(resultStatus)) {
            taskStateMutationUseCase.markDone(taskId);
            return;
        }
        if ("FAILED".equals(resultStatus) || "CANCELLED".equals(resultStatus)) {
            taskStateMutationUseCase.releaseAssignment(taskId);
        }
    }

    private boolean isAssignedVerifyTask(String taskId) {
        return taskQueryUseCase.findTaskById(taskId)
            .filter(RunFinishedProcessManager::isAssignedVerifyTask)
            .isPresent();
    }

    private static boolean isAssignedVerifyTask(WorkTask task) {
        return task != null
            && task.status() == TaskStatus.ASSIGNED
            && task.taskTemplateId() == TaskTemplateId.TMPL_VERIFY_V0;
    }

    private static String normalizeStatus(String rawStatus) {
        if (rawStatus == null || rawStatus.isBlank()) {
            throw new IllegalArgumentException("resultStatus must not be blank");
        }
        return rawStatus.trim().toUpperCase(Locale.ROOT);
    }
}
