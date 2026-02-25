package com.agentx.agentxbackend.workforce.infrastructure.persistence;

import com.agentx.agentxbackend.workforce.application.port.out.WorkerToolpackRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public class MybatisWorkerToolpackRepository implements WorkerToolpackRepository {

    private final WorkerToolpackMapper mapper;

    public MybatisWorkerToolpackRepository(WorkerToolpackMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public void bind(String workerId, String toolpackId) {
        mapper.insertIgnore(workerId, toolpackId);
    }

    @Override
    public List<String> findToolpackIdsByWorkerId(String workerId) {
        return mapper.findToolpackIdsByWorkerId(workerId);
    }

    @Override
    public boolean existsReadyWorkerCoveringAll(List<String> toolpackIds) {
        if (toolpackIds == null || toolpackIds.isEmpty()) {
            return false;
        }
        return mapper.existsReadyWorkerCoveringAll(toolpackIds, toolpackIds.size()) == 1;
    }
}

