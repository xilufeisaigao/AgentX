package com.agentx.agentxbackend.contextpack.domain.model;

import java.util.List;

public record TaskContextSnapshotStatusView(
    String taskId,
    TaskContextSnapshot latest,
    List<TaskContextSnapshot> snapshots
) {
}
