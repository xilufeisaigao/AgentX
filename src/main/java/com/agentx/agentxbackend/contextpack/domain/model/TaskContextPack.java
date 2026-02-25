package com.agentx.agentxbackend.contextpack.domain.model;

import java.util.List;

public record TaskContextPack(
    String snapshotId,
    String taskId,
    String runKind,
    String requirementRef,
    List<String> architectureRefs,
    String moduleRef,
    List<String> priorRunRefs,
    String repoBaselineRef,
    List<String> decisionRefs
) {
}
