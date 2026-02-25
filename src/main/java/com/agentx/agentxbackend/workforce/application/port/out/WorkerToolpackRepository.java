package com.agentx.agentxbackend.workforce.application.port.out;

import java.util.List;

public interface WorkerToolpackRepository {

    void bind(String workerId, String toolpackId);

    List<String> findToolpackIdsByWorkerId(String workerId);

    boolean existsReadyWorkerCoveringAll(List<String> toolpackIds);
}
