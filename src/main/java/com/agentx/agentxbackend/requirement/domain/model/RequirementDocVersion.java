package com.agentx.agentxbackend.requirement.domain.model;

import java.time.Instant;

public record RequirementDocVersion(
    String docId,
    int version,
    String content,
    String createdByRole,
    Instant createdAt
) {
}
