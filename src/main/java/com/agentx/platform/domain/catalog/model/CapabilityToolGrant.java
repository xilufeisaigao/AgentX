package com.agentx.platform.domain.catalog.model;

import java.util.Objects;

public record CapabilityToolGrant(
        String capabilityPackId,
        String toolId,
        boolean required,
        String exposureMode
) {

    public CapabilityToolGrant {
        Objects.requireNonNull(capabilityPackId, "capabilityPackId must not be null");
        Objects.requireNonNull(toolId, "toolId must not be null");
        Objects.requireNonNull(exposureMode, "exposureMode must not be null");
    }
}
