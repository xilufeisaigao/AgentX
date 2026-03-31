package com.agentx.platform.runtime.support;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public record CommandSpec(
        List<String> command,
        Path workingDirectory,
        Duration timeout,
        Map<String, String> environment
) {

    public CommandSpec {
        command = List.copyOf(Objects.requireNonNull(command, "command must not be null"));
        if (command.isEmpty()) {
            throw new IllegalArgumentException("command must not be empty");
        }
        Objects.requireNonNull(timeout, "timeout must not be null");
        environment = Map.copyOf(Objects.requireNonNull(environment, "environment must not be null"));
    }
}
