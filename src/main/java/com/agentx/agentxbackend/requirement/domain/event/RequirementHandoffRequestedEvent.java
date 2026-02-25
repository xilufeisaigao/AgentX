package com.agentx.agentxbackend.requirement.domain.event;

public record RequirementHandoffRequestedEvent(
    String sessionId,
    String requirementDocId,
    Integer requirementDocVersion,
    String userInput,
    String reason
) {
}
