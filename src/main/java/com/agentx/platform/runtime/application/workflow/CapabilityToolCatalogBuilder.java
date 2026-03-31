package com.agentx.platform.runtime.application.workflow;

import com.agentx.platform.runtime.tooling.CompiledToolCatalog;
import com.agentx.platform.runtime.tooling.ToolCatalogEntry;
import com.agentx.platform.runtime.tooling.ToolRegistry;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
public class CapabilityToolCatalogBuilder {

    private final ToolRegistry toolRegistry;

    public CapabilityToolCatalogBuilder(ToolRegistry toolRegistry) {
        this.toolRegistry = toolRegistry;
    }

    public CompiledToolCatalog build(Map<String, String> exposureModes) {
        List<ToolCatalogEntry> entries = exposureModes.entrySet().stream()
                .map(entry -> toolRegistry.catalogEntry(entry.getKey(), entry.getValue()))
                .toList();
        return new CompiledToolCatalog(entries);
    }
}
