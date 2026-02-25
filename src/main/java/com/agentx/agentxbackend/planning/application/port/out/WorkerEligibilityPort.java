package com.agentx.agentxbackend.planning.application.port.out;

public interface WorkerEligibilityPort {

    boolean hasEligibleWorker(String requiredToolpacksJson);

    boolean isWorkerEligible(String workerId, String requiredToolpacksJson);
}
