package com.agentx.agentxbackend.planning.application.port.in;

import com.agentx.agentxbackend.planning.domain.model.WorkTask;

public interface TaskStateMutationUseCase {

    WorkTask markAssigned(String taskId, String runId);

    WorkTask markDelivered(String taskId);

    WorkTask markDone(String taskId);

    WorkTask releaseAssignment(String taskId);

    WorkTask reopenDelivered(String taskId);
}
