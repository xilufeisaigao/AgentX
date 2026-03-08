package com.agentx.agentxbackend.query.domain.model;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

import java.time.Instant;
import java.util.List;

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record TicketInboxView(
    String sessionId,
    String appliedStatusFilter,
    int totalTickets,
    int waitingUserTickets,
    List<TicketItem> tickets
) {

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
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
