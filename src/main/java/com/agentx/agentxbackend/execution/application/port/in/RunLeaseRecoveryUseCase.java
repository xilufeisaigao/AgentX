package com.agentx.agentxbackend.execution.application.port.in;

public interface RunLeaseRecoveryUseCase {

    int recoverExpiredRuns(int limit);
}
