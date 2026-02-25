package com.agentx.agentxbackend.workforce.application.port.in;

import com.agentx.agentxbackend.workforce.domain.model.Toolpack;
import com.agentx.agentxbackend.workforce.domain.model.Worker;
import com.agentx.agentxbackend.workforce.domain.model.WorkerStatus;

import java.util.List;

public interface WorkerCapabilityUseCase {

    Toolpack registerToolpack(String toolpackId, String name, String version, String kind, String description);

    List<Toolpack> listToolpacks();

    Worker registerWorker(String workerId);

    Worker updateWorkerStatus(String workerId, WorkerStatus status);

    void bindToolpacks(String workerId, List<String> toolpackIds);

    boolean hasEligibleWorker(List<String> requiredToolpacks);

    boolean isWorkerEligible(String workerId, List<String> requiredToolpacks);

    boolean workerExists(String workerId);

    int countWorkers();

    int countWorkersByStatus(WorkerStatus status);

    List<String> listToolpackIdsByWorker(String workerId);

    List<Toolpack> listToolpacksByWorker(String workerId);

    List<Worker> listWorkersByStatus(WorkerStatus status, int limit);
}
