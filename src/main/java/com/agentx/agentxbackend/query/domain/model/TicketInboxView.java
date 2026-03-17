package com.agentx.agentxbackend.query.domain.model;

import java.time.Instant;
import java.util.List;

public record TicketInboxView(
    String sessionId,
    String appliedStatusFilter,
    int totalTickets,
    int waitingUserTickets,
    List<TicketItem> tickets
) {

    public record TicketItem(
        String ticketId,
        String type,
        String status,
        String title,
        String createdByRole,
        String assigneeRole,
        String requirementDocId,
        Integer requirementDocVer,
        String payloadJson,
        String claimedBy,
        Instant leaseUntil,
        Instant createdAt,
        Instant updatedAt,
        String latestEventType,
        String latestEventBody,
        String latestEventDataJson,
        Instant latestEventAt,
        String sourceRunId,
        String sourceTaskId,
        String requestKind,
        String question,
        boolean needsUserAction
    ) {
    }
}
