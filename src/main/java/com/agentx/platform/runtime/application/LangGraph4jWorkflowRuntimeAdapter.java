package com.agentx.platform.runtime.application;

import org.springframework.stereotype.Component;

@Component
public class LangGraph4jWorkflowRuntimeAdapter implements WorkflowRuntimePort {

    @Override
    public WorkflowRuntimeDescriptor describeRuntime() {
        return new WorkflowRuntimeDescriptor(
            "LangGraph4j",
            "LangChain4j",
            "Postgres checkpoint saver",
            "Fixed built-in workflow template with agent-tunable nodes"
        );
    }
}

