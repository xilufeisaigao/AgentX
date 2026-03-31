package com.agentx.platform.runtime.application.workflow;

import com.agentx.platform.domain.shared.model.ActorRef;

import java.util.Objects;

public record EditRequirementDocCommand(
        String docId,
        String title,
        String content,
        ActorRef editedBy
) {

    public EditRequirementDocCommand {
        Objects.requireNonNull(docId, "docId must not be null");
        Objects.requireNonNull(title, "title must not be null");
        Objects.requireNonNull(content, "content must not be null");
        Objects.requireNonNull(editedBy, "editedBy must not be null");
        if (title.isBlank()) {
            throw new IllegalArgumentException("title must not be blank");
        }
        if (content.isBlank()) {
            throw new IllegalArgumentException("content must not be blank");
        }
    }
}
