package com.agentx.platform.runtime.application;

public record WorkflowRuntimeDescriptor(
    String engine,
    String llmToolkit,
    String checkpointStore,
    String executionModel
) {
}

