package com.agentx.agentxbackend.query.domain.model;

public record SessionProgressView(
    String sessionId,
    String sessionStatus,
    String summary
) {
}
