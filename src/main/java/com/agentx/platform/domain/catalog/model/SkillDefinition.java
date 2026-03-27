package com.agentx.platform.domain.catalog.model;

import java.util.Objects;

public record SkillDefinition(
        String skillId,
        String displayName,
        String skillKind,
        String purpose,
        boolean enabled
) {

    public SkillDefinition {
        Objects.requireNonNull(skillId, "skillId must not be null");
        Objects.requireNonNull(displayName, "displayName must not be null");
        Objects.requireNonNull(skillKind, "skillKind must not be null");
        Objects.requireNonNull(purpose, "purpose must not be null");
    }
}
