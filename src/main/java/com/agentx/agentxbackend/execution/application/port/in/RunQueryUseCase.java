package com.agentx.agentxbackend.execution.application.port.in;

import com.agentx.agentxbackend.execution.domain.model.RunKind;
import com.agentx.agentxbackend.execution.domain.model.TaskRun;

import java.util.Optional;

public interface RunQueryUseCase {

    Optional<TaskRun> findRunById(String runId);

    Optional<TaskRun> findLatestRunByTaskAndKind(String taskId, RunKind runKind);

    boolean hasActiveRunByTaskAndKind(String taskId, RunKind runKind);

    boolean hasActiveRunsBySession(String sessionId);

    int countVerifyRunsByTaskAndBaseCommit(String taskId, String baseCommit);
}
