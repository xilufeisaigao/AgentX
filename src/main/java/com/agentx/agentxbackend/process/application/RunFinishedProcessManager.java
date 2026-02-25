package com.agentx.agentxbackend.process.application;

import com.agentx.agentxbackend.execution.domain.event.RunFinishedEvent;
import com.agentx.agentxbackend.execution.domain.model.RunKind;
import com.agentx.agentxbackend.planning.application.port.in.TaskStateMutationUseCase;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Objects;

@Component
public class RunFinishedProcessManager {

    private final TaskStateMutationUseCase taskStateMutationUseCase;

    public RunFinishedProcessManager(TaskStateMutationUseCase taskStateMutationUseCase) {
        this.taskStateMutationUseCase = taskStateMutationUseCase;
    }

    public void handle(RunFinishedEvent event) {
        Objects.requireNonNull(event, "event must not be null");
        if (event.runKind() != RunKind.IMPL || event.payload() == null) {
            return;
        }
        String resultStatus = normalizeStatus(event.payload().resultStatus());
        if ("SUCCEEDED".equals(resultStatus)) {
            taskStateMutationUseCase.markDelivered(event.taskId());
            return;
        }
        if ("FAILED".equals(resultStatus) || "CANCELLED".equals(resultStatus)) {
            taskStateMutationUseCase.releaseAssignment(event.taskId());
        }
    }

    private static String normalizeStatus(String rawStatus) {
        if (rawStatus == null || rawStatus.isBlank()) {
            throw new IllegalArgumentException("resultStatus must not be blank");
        }
        return rawStatus.trim().toUpperCase(Locale.ROOT);
    }
}
