package com.agentx.agentxbackend.execution.domain.model;

public record RunFinishedPayload(
    String resultStatus,
    String workReport,
    String deliveryCommit,
    String artifactRefsJson
) {
}
