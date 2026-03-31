package com.agentx.platform.runtime.tooling;

import java.util.List;
import java.util.Objects;

public record ToolCatalogEntry(
        String toolId,
        String displayName,
        String exposureMode,
        List<String> allowedOperations,
        String operationSchemaRef,
        String notes
) {

    public ToolCatalogEntry {
        Objects.requireNonNull(toolId, "toolId must not be null");
        Objects.requireNonNull(displayName, "displayName must not be null");
        Objects.requireNonNull(exposureMode, "exposureMode must not be null");
        allowedOperations = List.copyOf(Objects.requireNonNull(allowedOperations, "allowedOperations must not be null"));
        Objects.requireNonNull(operationSchemaRef, "operationSchemaRef must not be null");
        notes = notes == null ? "" : notes;
    }
}
