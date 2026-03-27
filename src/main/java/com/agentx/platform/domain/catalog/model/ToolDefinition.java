package com.agentx.platform.domain.catalog.model;

import java.util.Objects;

public record ToolDefinition(
        String toolId,
        String displayName,
        String toolKind,
        String adapterKey,
        String description,
        boolean enabled
) {

    public ToolDefinition {
        Objects.requireNonNull(toolId, "toolId must not be null");
        Objects.requireNonNull(displayName, "displayName must not be null");
        Objects.requireNonNull(toolKind, "toolKind must not be null");
        Objects.requireNonNull(adapterKey, "adapterKey must not be null");
    }
}
