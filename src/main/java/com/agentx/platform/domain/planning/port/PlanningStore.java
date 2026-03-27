package com.agentx.platform.domain.planning.port;

import com.agentx.platform.domain.planning.model.TaskCapabilityRequirement;
import com.agentx.platform.domain.planning.model.TaskDependency;
import com.agentx.platform.domain.planning.model.WorkModule;
import com.agentx.platform.domain.planning.model.WorkTask;

import java.util.List;

public interface PlanningStore {

    List<WorkModule> listModules(String workflowRunId);

    List<WorkTask> listTasksByWorkflow(String workflowRunId);

    List<TaskDependency> listDependencies(String workflowRunId);

    List<TaskCapabilityRequirement> listCapabilityRequirements(String taskId);

    void saveModule(WorkModule workModule);

    void saveTask(WorkTask workTask);

    void saveDependency(TaskDependency dependency);

    void saveCapabilityRequirement(TaskCapabilityRequirement requirement);
}
