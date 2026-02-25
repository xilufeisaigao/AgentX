package com.agentx.agentxbackend.workspace.domain.model;

import java.time.Instant;

public record GitWorkspace(
    String runId,
    GitWorkspaceStatus status,
    Instant createdAt,
    Instant updatedAt
) {
}
