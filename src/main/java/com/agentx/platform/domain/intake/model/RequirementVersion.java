package com.agentx.platform.domain.intake.model;

import com.agentx.platform.domain.shared.model.ActorRef;

import java.util.Objects;

public record RequirementVersion(
        String docId,
        int version,
        String content,
        ActorRef createdBy
) {

    public RequirementVersion {
        Objects.requireNonNull(docId, "docId must not be null");
        Objects.requireNonNull(content, "content must not be null");
        Objects.requireNonNull(createdBy, "createdBy must not be null");
    }
}
