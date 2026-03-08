package com.agentx.agentxbackend.execution.application.port.out;

import java.util.Optional;

public interface TaskAllocationPort {

    Optional<ClaimedTask> claimReadyTaskForWorker(String workerId, String runId);

    Optional<String> findSessionIdByTaskId(String taskId);

    boolean isSessionActive(String sessionId);

    boolean isInitGateActive(String sessionId);

    boolean hasNonDoneDependentTaskByTemplate(String taskId, String taskTemplateId);

    void releaseTaskAssignment(String taskId);

    record ClaimedTask(
        String taskId,
        String sessionId,
        String moduleId,
        String taskTitle,
        String taskTemplateId,
        String requiredToolpacksJson
    ) {
    }
}
