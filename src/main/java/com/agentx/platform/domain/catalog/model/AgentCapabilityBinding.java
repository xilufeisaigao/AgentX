package com.agentx.platform.domain.catalog.model;

import java.util.Objects;

public record AgentCapabilityBinding(
        String agentId,
        String capabilityPackId,
        boolean required
) {

    public AgentCapabilityBinding {
        Objects.requireNonNull(agentId, "agentId must not be null");
        Objects.requireNonNull(capabilityPackId, "capabilityPackId must not be null");
    }
}
