package com.agentx.agentxbackend.execution.application.port.in;

import com.agentx.agentxbackend.execution.domain.model.RunFinishedPayload;
import com.agentx.agentxbackend.execution.domain.model.TaskPackage;
import com.agentx.agentxbackend.execution.domain.model.TaskRun;
import com.agentx.agentxbackend.execution.domain.model.TaskRunEvent;

import java.util.Optional;

public interface RunCommandUseCase {

    Optional<TaskPackage> claimTask(String workerId);

    Optional<TaskPackage> pickupRunningVerifyRun(String workerId);

    TaskRun heartbeat(String runId);

    TaskRunEvent appendEvent(String runId, String eventType, String body, String dataJson);

    TaskRun finishRun(String runId, RunFinishedPayload payload);
}
