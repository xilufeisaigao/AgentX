package com.agentx.agentxbackend.execution.domain.event;

import com.agentx.agentxbackend.execution.domain.model.RunFinishedPayload;
import com.agentx.agentxbackend.execution.domain.model.RunKind;

public record RunFinishedEvent(
    String runId,
    String taskId,
    RunKind runKind,
    String baseCommit,
    RunFinishedPayload payload
) {

    public RunFinishedEvent(String runId, String taskId, RunFinishedPayload payload) {
        this(runId, taskId, RunKind.IMPL, null, payload);
    }

    public RunFinishedEvent(String runId, String taskId, RunKind runKind, RunFinishedPayload payload) {
        this(runId, taskId, runKind, null, payload);
    }
}
