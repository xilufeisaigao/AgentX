package com.agentx.agentxbackend.execution.infrastructure.external;

import com.agentx.agentxbackend.execution.application.port.out.TaskAllocationPort;
import com.agentx.agentxbackend.planning.application.port.in.TaskAllocationUseCase;
import com.agentx.agentxbackend.planning.application.port.in.TaskStateMutationUseCase;
import com.agentx.agentxbackend.planning.domain.model.WorkTask;
import org.springframework.stereotype.Component;

import java.util.Optional;

@Component
public class PlanningTaskAllocationAdapter implements TaskAllocationPort {

    private final TaskAllocationUseCase taskAllocationUseCase;
    private final TaskStateMutationUseCase taskStateMutationUseCase;

    public PlanningTaskAllocationAdapter(
        TaskAllocationUseCase taskAllocationUseCase,
        TaskStateMutationUseCase taskStateMutationUseCase
    ) {
        this.taskAllocationUseCase = taskAllocationUseCase;
        this.taskStateMutationUseCase = taskStateMutationUseCase;
    }

    @Override
    public Optional<ClaimedTask> claimReadyTaskForWorker(String workerId, String runId) {
        Optional<WorkTask> claimed = taskAllocationUseCase.claimReadyTaskForWorker(workerId, runId);
        return claimed.map(task -> new ClaimedTask(
            task.taskId(),
            task.moduleId(),
            task.title(),
            task.taskTemplateId().value(),
            task.requiredToolpacksJson()
        ));
    }

    @Override
    public boolean isInitGateActive() {
        return taskAllocationUseCase.isInitGateActive();
    }

    @Override
    public void releaseTaskAssignment(String taskId) {
        taskStateMutationUseCase.releaseAssignment(taskId);
    }
}
