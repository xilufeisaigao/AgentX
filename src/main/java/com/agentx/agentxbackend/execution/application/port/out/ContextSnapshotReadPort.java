package com.agentx.agentxbackend.execution.application.port.out;

import com.agentx.agentxbackend.execution.domain.model.RunKind;

import java.util.Optional;

public interface ContextSnapshotReadPort {

    Optional<ReadySnapshot> findLatestReadySnapshot(String taskId, RunKind runKind);

    record ReadySnapshot(
        String snapshotId,
        String taskContextRef,
        String taskSkillRef
    ) {
    }
}
