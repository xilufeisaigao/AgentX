package com.agentx.agentxbackend.execution.application.port.out;

import java.util.Optional;

public interface TaskAllocationPort {

    Optional<ClaimedTask> claimReadyTaskForWorker(String workerId, String runId);

    boolean isInitGateActive();

    void releaseTaskAssignment(String taskId);

    record ClaimedTask(
        String taskId,
        String moduleId,
        String taskTitle,
        String taskTemplateId,
        String requiredToolpacksJson
    ) {
    }
}
