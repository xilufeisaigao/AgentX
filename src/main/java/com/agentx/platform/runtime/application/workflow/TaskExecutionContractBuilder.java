package com.agentx.platform.runtime.application.workflow;

import com.agentx.platform.domain.planning.model.TaskCapabilityRequirement;
import com.agentx.platform.domain.planning.model.WorkTask;
import com.agentx.platform.domain.shared.model.JsonPayload;

import java.util.List;

public interface TaskExecutionContractBuilder {

    TaskExecutionContract build(
            String workflowRunId,
            WorkTask task,
            List<TaskCapabilityRequirement> capabilityRequirements,
            int attemptNumber,
            WorkflowScenario scenario
    );

    JsonPayload toPayload(TaskExecutionContract taskExecutionContract);

    TaskExecutionContract fromPayload(JsonPayload payload);
}
