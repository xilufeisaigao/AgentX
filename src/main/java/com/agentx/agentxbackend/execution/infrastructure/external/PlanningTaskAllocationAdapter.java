package com.agentx.agentxbackend.execution.infrastructure.external;

import com.agentx.agentxbackend.execution.application.port.out.TaskAllocationPort;
import com.agentx.agentxbackend.planning.application.port.in.TaskAllocationUseCase;
import com.agentx.agentxbackend.planning.application.port.in.TaskQueryUseCase;
import com.agentx.agentxbackend.planning.application.port.in.TaskStateMutationUseCase;
import com.agentx.agentxbackend.planning.application.port.in.WaitingTaskQueryUseCase;
import com.agentx.agentxbackend.planning.domain.model.WorkTask;
import com.agentx.agentxbackend.session.application.port.in.SessionHistoryQueryUseCase;
import org.springframework.stereotype.Component;

import java.util.Optional;

@Component
public class PlanningTaskAllocationAdapter implements TaskAllocationPort {

    private final TaskAllocationUseCase taskAllocationUseCase;
    private final TaskQueryUseCase taskQueryUseCase;
    private final WaitingTaskQueryUseCase waitingTaskQueryUseCase;
    private final TaskStateMutationUseCase taskStateMutationUseCase;
    private final SessionHistoryQueryUseCase sessionHistoryQueryUseCase;

    public PlanningTaskAllocationAdapter(
        TaskAllocationUseCase taskAllocationUseCase,
        TaskQueryUseCase taskQueryUseCase,
        WaitingTaskQueryUseCase waitingTaskQueryUseCase,
        TaskStateMutationUseCase taskStateMutationUseCase,
        SessionHistoryQueryUseCase sessionHistoryQueryUseCase
    ) {
        this.taskAllocationUseCase = taskAllocationUseCase;
        this.taskQueryUseCase = taskQueryUseCase;
        this.waitingTaskQueryUseCase = waitingTaskQueryUseCase;
        this.taskStateMutationUseCase = taskStateMutationUseCase;
        this.sessionHistoryQueryUseCase = sessionHistoryQueryUseCase;
    }

    @Override
    public Optional<ClaimedTask> claimReadyTaskForWorker(String workerId, String runId) {
        Optional<WorkTask> claimed = taskAllocationUseCase.claimReadyTaskForWorker(workerId, runId);
        return claimed.map(task -> {
            String sessionId = waitingTaskQueryUseCase.findSessionIdByTaskId(task.taskId())
                .orElseThrow(() -> new IllegalStateException("Session not found for claimed task: " + task.taskId()));
            return new ClaimedTask(
                task.taskId(),
                sessionId,
                task.moduleId(),
                task.title(),
                task.taskTemplateId().value(),
                task.requiredToolpacksJson()
            );
        });
    }

    @Override
    public Optional<String> findSessionIdByTaskId(String taskId) {
        return waitingTaskQueryUseCase.findSessionIdByTaskId(taskId);
    }

    @Override
    public boolean isSessionActive(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return false;
        }
        return sessionHistoryQueryUseCase.findSessionWithCurrentRequirementDoc(sessionId.trim())
            .map(view -> "ACTIVE".equalsIgnoreCase(view.status()))
            .orElse(false);
    }

    @Override
    public boolean isInitGateActive(String sessionId) {
        return taskAllocationUseCase.isInitGateActive(sessionId);
    }

    @Override
    public boolean hasNonDoneDependentTaskByTemplate(String taskId, String taskTemplateId) {
        return taskQueryUseCase.hasNonDoneDependentTaskByTemplate(taskId, taskTemplateId);
    }

    @Override
    public void releaseTaskAssignment(String taskId) {
        taskStateMutationUseCase.releaseAssignment(taskId);
    }
}
