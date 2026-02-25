package com.agentx.agentxbackend.mergegate.infrastructure.external;

import com.agentx.agentxbackend.mergegate.application.port.out.TaskStateMutationPort;
import com.agentx.agentxbackend.planning.application.port.in.TaskStateMutationUseCase;
import org.springframework.stereotype.Component;

@Component
public class PlanningTaskStateMutationAdapter implements TaskStateMutationPort {

    private final TaskStateMutationUseCase taskStateMutationUseCase;

    public PlanningTaskStateMutationAdapter(TaskStateMutationUseCase taskStateMutationUseCase) {
        this.taskStateMutationUseCase = taskStateMutationUseCase;
    }

    @Override
    public void markDone(String taskId) {
        taskStateMutationUseCase.markDone(taskId);
    }
}

