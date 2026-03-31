package com.agentx.platform.runtime.agentruntime;

import java.time.LocalDateTime;

public record ContainerObservation(
        ContainerState state,
        Integer exitCode,
        String logOutput,
        boolean timedOut,
        LocalDateTime startedAt,
        LocalDateTime finishedAt
) {

    public boolean isRunning() {
        return state == ContainerState.RUNNING;
    }

    public boolean isTerminal() {
        return state == ContainerState.EXITED || state == ContainerState.MISSING;
    }
}
