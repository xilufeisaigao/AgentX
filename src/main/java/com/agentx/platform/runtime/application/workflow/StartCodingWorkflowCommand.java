package com.agentx.platform.runtime.application.workflow;

import com.agentx.platform.domain.shared.model.ActorRef;

import java.util.Objects;

public record StartCodingWorkflowCommand(
        String title,
        String requirementTitle,
        String requirementContent,
        String profileId,
        ActorRef createdBy,
        boolean autoAgentMode,
        WorkflowScenario scenario
) {

    public StartCodingWorkflowCommand {
        Objects.requireNonNull(title, "title must not be null");
        Objects.requireNonNull(requirementTitle, "requirementTitle must not be null");
        Objects.requireNonNull(requirementContent, "requirementContent must not be null");
        Objects.requireNonNull(profileId, "profileId must not be null");
        Objects.requireNonNull(createdBy, "createdBy must not be null");
        scenario = scenario == null ? WorkflowScenario.defaultScenario() : scenario;
    }
}
