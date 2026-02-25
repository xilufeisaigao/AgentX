package com.agentx.agentxbackend.requirement.domain.event;

public record RequirementConfirmedEvent(
    String sessionId,
    String docId,
    int confirmedVersion,
    Integer previousConfirmedVersion
) {
}
