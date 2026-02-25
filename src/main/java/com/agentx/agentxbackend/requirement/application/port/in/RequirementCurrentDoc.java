package com.agentx.agentxbackend.requirement.application.port.in;

import java.time.Instant;

public record RequirementCurrentDoc(
    String docId,
    int currentVersion,
    Integer confirmedVersion,
    String status,
    String title,
    String content,
    Instant updatedAt
) {
}
