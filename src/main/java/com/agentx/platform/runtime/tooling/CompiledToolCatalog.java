package com.agentx.platform.runtime.tooling;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

public record CompiledToolCatalog(
        List<ToolCatalogEntry> entries
) {

    public CompiledToolCatalog {
        entries = List.copyOf(Objects.requireNonNull(entries, "entries must not be null"));
    }

    public Optional<ToolCatalogEntry> find(String toolId) {
        return entries.stream()
                .filter(entry -> entry.toolId().equals(toolId))
                .findFirst();
    }
}
