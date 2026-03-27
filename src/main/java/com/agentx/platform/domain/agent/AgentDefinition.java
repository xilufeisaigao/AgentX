package com.agentx.platform.domain.agent;

import java.util.Set;

public record AgentDefinition(
    String agentId,
    String displayName,
    String purpose,
    Set<String> capabilities,
    Set<String> allowedTools,
    Set<String> allowedSkills,
    AgentRuntimeProfile runtimeProfile,
    AgentControlPolicy controlPolicy,
    boolean enabled
) {

    public AgentDefinition {
        agentId = requireText(agentId, "agentId");
        displayName = requireText(displayName, "displayName");
        purpose = requireText(purpose, "purpose");
        capabilities = copy(capabilities);
        allowedTools = copy(allowedTools);
        allowedSkills = copy(allowedSkills);
        if (runtimeProfile == null) {
            throw new IllegalArgumentException("runtimeProfile must not be null");
        }
        if (controlPolicy == null) {
            throw new IllegalArgumentException("controlPolicy must not be null");
        }
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }

    private static Set<String> copy(Set<String> value) {
        return value == null ? Set.of() : Set.copyOf(value);
    }
}

