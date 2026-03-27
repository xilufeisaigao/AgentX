package com.agentx.platform.domain.catalog.model;

import java.util.Objects;

public record CapabilitySkillGrant(
        String capabilityPackId,
        String skillId,
        boolean required,
        String roleInPack
) {

    public CapabilitySkillGrant {
        Objects.requireNonNull(capabilityPackId, "capabilityPackId must not be null");
        Objects.requireNonNull(skillId, "skillId must not be null");
        Objects.requireNonNull(roleInPack, "roleInPack must not be null");
    }
}
