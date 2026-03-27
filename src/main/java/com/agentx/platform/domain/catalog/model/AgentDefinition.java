package com.agentx.platform.domain.catalog.model;

import java.util.Objects;

public record AgentDefinition(
        String agentId,
        String displayName,
        String purpose,
        String registrationSource,
        String runtimeType,
        String model,
        int maxParallelRuns,
        boolean architectSuggested,
        boolean autoPoolEligible,
        boolean manualRegistrationAllowed,
        boolean enabled
) {

    public AgentDefinition {
        Objects.requireNonNull(agentId, "agentId must not be null");
        Objects.requireNonNull(displayName, "displayName must not be null");
        Objects.requireNonNull(purpose, "purpose must not be null");
        Objects.requireNonNull(registrationSource, "registrationSource must not be null");
        Objects.requireNonNull(runtimeType, "runtimeType must not be null");
        Objects.requireNonNull(model, "model must not be null");
    }
}
