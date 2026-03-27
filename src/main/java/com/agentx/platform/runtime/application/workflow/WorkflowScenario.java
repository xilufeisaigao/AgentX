package com.agentx.platform.runtime.application.workflow;

public record WorkflowScenario(
        boolean requireHumanClarification,
        boolean architectCanAutoResolveClarification,
        boolean verifyNeedsRework
) {

    public static WorkflowScenario defaultScenario() {
        return new WorkflowScenario(false, false, false);
    }
}
