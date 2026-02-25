package com.agentx.agentxbackend.execution.application.port.out;

import java.util.List;

public interface WorkerRuntimePort {

    boolean workerExists(String workerId);

    boolean isWorkerReady(String workerId);

    List<String> listWorkerToolpackIds(String workerId);
}
