package com.agentx.agentxbackend.planning.application.port.in;

import com.agentx.agentxbackend.planning.domain.model.WorkModule;
import com.agentx.agentxbackend.planning.domain.model.WorkTask;
import com.agentx.agentxbackend.planning.domain.model.WorkTaskDependency;

import java.util.List;

public interface PlanningCommandUseCase {

    WorkModule createModule(String sessionId, String name, String description);

    WorkTask createTask(String moduleId, String title, String taskTemplateId, String requiredToolpacksJson);

    WorkTask createTask(
        String moduleId,
        String title,
        String taskTemplateId,
        String requiredToolpacksJson,
        List<String> dependsOnTaskIds
    );

    WorkTaskDependency addTaskDependency(String taskId, String dependsOnTaskId, String requiredUpstreamStatus);
}
