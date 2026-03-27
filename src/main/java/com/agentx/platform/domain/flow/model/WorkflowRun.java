package com.agentx.platform.domain.flow.model;

import com.agentx.platform.domain.shared.model.ActorRef;

import java.util.Objects;

public record WorkflowRun(
        String workflowRunId,
        String workflowTemplateId,
        String title,
        WorkflowRunStatus status,
        EntryMode entryMode,
        boolean autoAgentMode,
        ActorRef createdBy
) {

    public WorkflowRun {
        Objects.requireNonNull(workflowRunId, "workflowRunId must not be null");
        Objects.requireNonNull(workflowTemplateId, "workflowTemplateId must not be null");
        Objects.requireNonNull(title, "title must not be null");
        Objects.requireNonNull(status, "status must not be null");
        Objects.requireNonNull(entryMode, "entryMode must not be null");
        Objects.requireNonNull(createdBy, "createdBy must not be null");
    }
}
