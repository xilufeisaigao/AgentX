package com.agentx.platform.runtime.agentruntime;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public record ToolExecutionSpec(
        String workingDirectory,
        List<String> command,
        Map<String, String> environment,
        Duration timeout
) {

    public ToolExecutionSpec {
        Objects.requireNonNull(workingDirectory, "workingDirectory must not be null");
        command = List.copyOf(Objects.requireNonNull(command, "command must not be null"));
        environment = Map.copyOf(Objects.requireNonNull(environment, "environment must not be null"));
        Objects.requireNonNull(timeout, "timeout must not be null");
    }
}
