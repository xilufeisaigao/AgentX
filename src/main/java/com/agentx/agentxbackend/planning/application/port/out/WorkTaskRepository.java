package com.agentx.agentxbackend.planning.application.port.out;

import com.agentx.agentxbackend.planning.domain.model.WorkTask;
import com.agentx.agentxbackend.planning.domain.model.TaskStatus;

import java.util.List;
import java.util.Optional;
import java.time.Instant;

public interface WorkTaskRepository {

    WorkTask save(WorkTask task);

    Optional<WorkTask> findById(String taskId);

    WorkTask update(WorkTask task);

    List<WorkTask> findByStatus(TaskStatus status, int limit);

    List<WorkTask> findByStatus(TaskStatus status, int limit, int offset);

    boolean claimIfReady(String taskId, String runId, Instant updatedAt);

    int countNonDoneByTemplateId(String taskTemplateId);
}
