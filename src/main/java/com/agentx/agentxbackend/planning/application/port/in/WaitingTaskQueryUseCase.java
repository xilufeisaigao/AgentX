package com.agentx.agentxbackend.planning.application.port.in;

import com.agentx.agentxbackend.planning.domain.model.WorkTask;

import java.util.List;
import java.util.Optional;

public interface WaitingTaskQueryUseCase {

    List<WorkTask> listWaitingWorkerTasks(int limit);

    Optional<String> findSessionIdByModuleId(String moduleId);

    Optional<String> findSessionIdByTaskId(String taskId);
}
