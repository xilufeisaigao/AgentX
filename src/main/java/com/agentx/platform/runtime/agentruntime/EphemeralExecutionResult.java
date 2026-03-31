package com.agentx.platform.runtime.agentruntime;

import java.time.Duration;
import java.util.Objects;

public record EphemeralExecutionResult(
        int exitCode,
        String stdout,
        String stderr,
        boolean timedOut,
        Duration elapsed
) {

    public EphemeralExecutionResult {
        Objects.requireNonNull(stdout, "stdout must not be null");
        Objects.requireNonNull(stderr, "stderr must not be null");
        Objects.requireNonNull(elapsed, "elapsed must not be null");
    }

    public boolean succeeded() {
        return exitCode == 0 && !timedOut;
    }
}
