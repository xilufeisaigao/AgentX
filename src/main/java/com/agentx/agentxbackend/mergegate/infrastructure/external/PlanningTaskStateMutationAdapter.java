package com.agentx.agentxbackend.mergegate.infrastructure.external;

import com.agentx.agentxbackend.mergegate.application.port.out.TaskStateMutationPort;
import com.agentx.agentxbackend.planning.application.port.in.TaskStateMutationUseCase;
import com.agentx.agentxbackend.planning.application.port.in.WaitingTaskQueryUseCase;
import org.springframework.stereotype.Component;

@Component
public class PlanningTaskStateMutationAdapter implements TaskStateMutationPort {

    private final TaskStateMutationUseCase taskStateMutationUseCase;
    private final WaitingTaskQueryUseCase waitingTaskQueryUseCase;

    public PlanningTaskStateMutationAdapter(
        TaskStateMutationUseCase taskStateMutationUseCase,
        WaitingTaskQueryUseCase waitingTaskQueryUseCase
    ) {
        this.taskStateMutationUseCase = taskStateMutationUseCase;
        this.waitingTaskQueryUseCase = waitingTaskQueryUseCase;
    }

    @Override
    public void markDone(String taskId) {
        taskStateMutationUseCase.markDone(taskId);
    }

    @Override
    public String resolveSessionIdByTaskId(String taskId) {
        return waitingTaskQueryUseCase.findSessionIdByTaskId(taskId)
            .orElseThrow(() -> new IllegalStateException("Session not found for task: " + taskId));
    }
}

