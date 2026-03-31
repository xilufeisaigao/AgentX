package com.agentx.platform.runtime.support;

import java.time.Duration;
import java.util.Objects;

public record CommandResult(
        int exitCode,
        String stdout,
        String stderr,
        boolean timedOut,
        Duration elapsed
) {

    public CommandResult {
        Objects.requireNonNull(stdout, "stdout must not be null");
        Objects.requireNonNull(stderr, "stderr must not be null");
        Objects.requireNonNull(elapsed, "elapsed must not be null");
    }
}
