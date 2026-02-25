package com.agentx.agentxbackend.session.application.query;

import java.time.Instant;

public record SessionHistoryView(
    String sessionId,
    String title,
    String status,
    Instant createdAt,
    Instant updatedAt,
    CurrentRequirementDoc currentRequirementDoc
) {

    public record CurrentRequirementDoc(
        String docId,
        int currentVersion,
        Integer confirmedVersion,
        String status,
        String title,
        String content,
        Instant updatedAt
    ) {
    }
}
