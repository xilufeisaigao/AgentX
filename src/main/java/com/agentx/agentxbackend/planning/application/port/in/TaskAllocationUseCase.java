package com.agentx.agentxbackend.planning.application.port.in;

import com.agentx.agentxbackend.planning.domain.model.WorkTask;

import java.util.Optional;

public interface TaskAllocationUseCase {

    Optional<WorkTask> claimReadyTaskForWorker(String workerId, String runId);

    boolean isInitGateActive(String sessionId);
}
