package com.agentx.agentxbackend.planning.application.port.in;

import com.agentx.agentxbackend.planning.domain.model.WorkTask;
import com.agentx.agentxbackend.planning.domain.model.TaskStatus;

import java.util.List;
import java.util.Optional;

public interface TaskQueryUseCase {

    Optional<WorkTask> findTaskById(String taskId);

    Optional<String> findSessionIdByTaskId(String taskId);

    List<WorkTask> listTasksByStatus(TaskStatus status, int limit);

    boolean hasNonDoneTasksBySession(String sessionId);

    boolean hasNonDoneDependentTaskByTemplate(String taskId, String taskTemplateId);
}
