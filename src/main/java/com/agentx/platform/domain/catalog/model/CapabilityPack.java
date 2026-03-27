package com.agentx.platform.domain.catalog.model;

import com.agentx.platform.domain.shared.model.AggregateRoot;

import java.util.Objects;

public record CapabilityPack(
        String capabilityPackId,
        String displayName,
        String capabilityKind,
        String granularity,
        String purpose,
        String description,
        boolean enabled
) implements AggregateRoot<String> {

    public CapabilityPack {
        Objects.requireNonNull(capabilityPackId, "capabilityPackId must not be null");
        Objects.requireNonNull(displayName, "displayName must not be null");
        Objects.requireNonNull(capabilityKind, "capabilityKind must not be null");
        Objects.requireNonNull(granularity, "granularity must not be null");
        Objects.requireNonNull(purpose, "purpose must not be null");
    }

    @Override
    public String aggregateId() {
        return capabilityPackId;
    }
}
