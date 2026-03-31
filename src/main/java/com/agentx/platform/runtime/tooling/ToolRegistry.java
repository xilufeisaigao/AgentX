package com.agentx.platform.runtime.tooling;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
public class ToolRegistry {

    private final Map<String, ToolRegistration> registrations = Map.of(
            "tool-filesystem",
            new ToolRegistration(
                    "tool-filesystem",
                    "Filesystem",
                    List.of("read_file", "list_directory", "search_text", "write_file", "delete_file"),
                    "schema://tool-filesystem",
                    "Read and modify files inside the assigned workspace and write scopes."
            ),
            "tool-shell",
            new ToolRegistration(
                    "tool-shell",
                    "Shell",
                    List.of("run_command"),
                    "schema://tool-shell",
                    "Run only allowlisted commandIds from the execution contract."
            ),
            "tool-git",
            new ToolRegistration(
                    "tool-git",
                    "Git",
                    List.of("git_status", "git_diff_stat", "git_head"),
                    "schema://tool-git",
                    "Inspect repository status, diff stats and current HEAD without bypassing runtime guardrails."
            ),
            "tool-http-client",
            new ToolRegistration(
                    "tool-http-client",
                    "HTTP Client",
                    List.of("http_request"),
                    "schema://tool-http-client",
                    "Call only contract-allowlisted HTTP endpoints for deterministic API smoke or verification."
            )
    );

    public ToolCatalogEntry catalogEntry(String toolId, String exposureMode) {
        ToolRegistration registration = registration(toolId);
        return new ToolCatalogEntry(
                registration.toolId(),
                registration.displayName(),
                exposureMode,
                registration.allowedOperations(),
                registration.operationSchemaRef(),
                registration.notes()
        );
    }

    public void validate(CompiledToolCatalog catalog, ToolCall toolCall) {
        ToolRegistration registration = registration(toolCall.toolId());
        ToolCatalogEntry visibleEntry = catalog.find(toolCall.toolId())
                .orElseThrow(() -> new IllegalArgumentException("tool is not visible in this execution contract: " + toolCall.toolId()));
        if (!visibleEntry.allowedOperations().contains(toolCall.operation())
                || !registration.allowedOperations().contains(toolCall.operation())) {
            throw new IllegalArgumentException("operation is not allowed for tool " + toolCall.toolId() + ": " + toolCall.operation());
        }
    }

    private ToolRegistration registration(String toolId) {
        ToolRegistration registration = registrations.get(toolId);
        if (registration == null) {
            throw new IllegalArgumentException("tool is not registered: " + toolId);
        }
        return registration;
    }

    private record ToolRegistration(
            String toolId,
            String displayName,
            List<String> allowedOperations,
            String operationSchemaRef,
            String notes
    ) {
    }
}
