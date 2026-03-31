package com.agentx.platform.runtime.tooling;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public record ToolCall(
        String callId,
        String toolId,
        String operation,
        Map<String, Object> arguments,
        String summary
) {

    public ToolCall {
        Objects.requireNonNull(toolId, "toolId must not be null");
        Objects.requireNonNull(operation, "operation must not be null");
        arguments = Map.copyOf(new LinkedHashMap<>(Objects.requireNonNull(arguments, "arguments must not be null")));
        summary = summary == null || summary.isBlank()
                ? toolId + "." + operation
                : summary;
        callId = callId == null || callId.isBlank() ? null : callId.trim();
    }

    public ToolCall(
            String toolId,
            String operation,
            Map<String, Object> arguments,
            String summary
    ) {
        this(null, toolId, operation, arguments, summary);
    }
}
