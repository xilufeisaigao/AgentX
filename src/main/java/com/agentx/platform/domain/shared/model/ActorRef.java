package com.agentx.platform.domain.shared.model;

import java.util.Objects;

public record ActorRef(ActorType type, String actorId) {

    public ActorRef {
        Objects.requireNonNull(type, "type must not be null");
        Objects.requireNonNull(actorId, "actorId must not be null");
    }
}
