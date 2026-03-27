package com.agentx.platform.domain.catalog.model;

import java.util.Objects;

public record CapabilityRuntimeRequirement(
        String capabilityPackId,
        String runtimePackId,
        boolean required,
        String purpose
) {

    public CapabilityRuntimeRequirement {
        Objects.requireNonNull(capabilityPackId, "capabilityPackId must not be null");
        Objects.requireNonNull(runtimePackId, "runtimePackId must not be null");
    }
}
