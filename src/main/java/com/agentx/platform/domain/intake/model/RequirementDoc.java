package com.agentx.platform.domain.intake.model;

import java.util.Objects;

public record RequirementDoc(
        String docId,
        String workflowRunId,
        int currentVersion,
        Integer confirmedVersion,
        RequirementStatus status,
        String title
) {

    public RequirementDoc {
        Objects.requireNonNull(docId, "docId must not be null");
        Objects.requireNonNull(workflowRunId, "workflowRunId must not be null");
        Objects.requireNonNull(status, "status must not be null");
        Objects.requireNonNull(title, "title must not be null");
    }
}
