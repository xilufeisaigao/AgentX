package com.agentx.platform.runtime.application.workflow;

import com.agentx.platform.domain.shared.model.ActorRef;

import java.util.Objects;

public record ConfirmRequirementDocCommand(
        String docId,
        int version,
        ActorRef confirmedBy
) {

    public ConfirmRequirementDocCommand {
        Objects.requireNonNull(docId, "docId must not be null");
        if (version <= 0) {
            throw new IllegalArgumentException("version must be greater than zero");
        }
        Objects.requireNonNull(confirmedBy, "confirmedBy must not be null");
    }
}
