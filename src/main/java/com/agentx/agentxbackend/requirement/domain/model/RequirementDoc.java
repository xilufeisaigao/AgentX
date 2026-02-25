package com.agentx.agentxbackend.requirement.domain.model;

import java.time.Instant;

public record RequirementDoc(
    String docId,
    String sessionId,
    int currentVersion,
    Integer confirmedVersion,
    RequirementDocStatus status,
    String title,
    Instant createdAt,
    Instant updatedAt
) {
}
