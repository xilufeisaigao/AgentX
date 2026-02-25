package com.agentx.agentxbackend.execution.application.port.in;

import com.agentx.agentxbackend.execution.domain.model.TaskRun;

public interface RunInternalUseCase {

    TaskRun createVerifyRun(String taskId, String mergeCandidateCommit);

    TaskRun failRun(String runId, String reason);

    TaskRun failWaitingRunForUserResponse(String runId, String reason);
}
