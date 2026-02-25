package com.agentx.agentxbackend.execution.domain.model;

import java.util.List;

public record TaskContext(
    String requirementRef,
    List<String> architectureRefs,
    List<String> priorRunRefs,
    String repoBaselineRef
) {
}
