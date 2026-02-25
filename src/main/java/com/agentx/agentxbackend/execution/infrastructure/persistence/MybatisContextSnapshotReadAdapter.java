package com.agentx.agentxbackend.execution.infrastructure.persistence;

import com.agentx.agentxbackend.execution.application.port.out.ContextSnapshotReadPort;
import com.agentx.agentxbackend.execution.domain.model.RunKind;
import org.springframework.stereotype.Component;

import java.util.Optional;

@Component
public class MybatisContextSnapshotReadAdapter implements ContextSnapshotReadPort {

    private final ContextSnapshotMapper mapper;

    public MybatisContextSnapshotReadAdapter(ContextSnapshotMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public Optional<ReadySnapshot> findLatestReadySnapshot(String taskId, RunKind runKind) {
        ContextSnapshotRow row = mapper.findLatestReadyByTaskAndRunKind(taskId, runKind.name());
        if (row == null) {
            return Optional.empty();
        }
        return Optional.of(new ReadySnapshot(
            row.getSnapshotId(),
            row.getTaskContextRef(),
            row.getTaskSkillRef()
        ));
    }
}
