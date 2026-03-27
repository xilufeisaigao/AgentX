package com.agentx.platform.domain.catalog.model;

import java.util.Objects;

public record SkillToolBinding(
        String skillId,
        String toolId,
        boolean required,
        String invocationMode,
        int sortOrder
) {

    public SkillToolBinding {
        Objects.requireNonNull(skillId, "skillId must not be null");
        Objects.requireNonNull(toolId, "toolId must not be null");
        Objects.requireNonNull(invocationMode, "invocationMode must not be null");
    }
}
