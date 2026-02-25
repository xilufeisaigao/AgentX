package com.agentx.agentxbackend.execution.infrastructure.external;

import com.agentx.agentxbackend.execution.application.port.out.WorkerRuntimePort;
import com.agentx.agentxbackend.workforce.application.port.in.WorkerCapabilityUseCase;
import com.agentx.agentxbackend.workforce.domain.model.Toolpack;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class WorkforceWorkerRuntimeAdapter implements WorkerRuntimePort {

    private final WorkerCapabilityUseCase workerCapabilityUseCase;

    public WorkforceWorkerRuntimeAdapter(WorkerCapabilityUseCase workerCapabilityUseCase) {
        this.workerCapabilityUseCase = workerCapabilityUseCase;
    }

    @Override
    public boolean workerExists(String workerId) {
        return workerCapabilityUseCase.workerExists(workerId);
    }

    @Override
    public boolean isWorkerReady(String workerId) {
        return workerCapabilityUseCase.isWorkerEligible(workerId, List.of());
    }

    @Override
    public List<String> listWorkerToolpackIds(String workerId) {
        List<Toolpack> toolpacks = workerCapabilityUseCase.listToolpacksByWorker(workerId);
        List<String> ids = new ArrayList<>(toolpacks.size());
        for (Toolpack toolpack : toolpacks) {
            ids.add(toolpack.toolpackId());
        }
        return ids;
    }
}
