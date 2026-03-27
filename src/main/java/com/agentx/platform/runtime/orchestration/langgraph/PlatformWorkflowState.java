package com.agentx.platform.runtime.orchestration.langgraph;

import org.bsc.langgraph4j.state.AgentState;

import java.util.Map;
import java.util.Objects;

public final class PlatformWorkflowState extends AgentState {

    public PlatformWorkflowState(Map<String, Object> data) {
        super(data);
    }

    public String workflowRunId() {
        Object rawValue = value("workflowRunId").orElse(null);
        return Objects.requireNonNull(rawValue, "workflowRunId must be present").toString();
    }
}
