package com.agentx.agentxbackend.planning.application.port.out;

import com.agentx.agentxbackend.planning.domain.model.WorkTaskDependency;

import java.util.List;

public interface WorkTaskDependencyRepository {

    WorkTaskDependency save(WorkTaskDependency dependency);

    boolean exists(String taskId, String dependsOnTaskId);

    List<WorkTaskDependency> findByTaskId(String taskId);

    List<WorkTaskDependency> findByDependsOnTaskId(String dependsOnTaskId);
}
