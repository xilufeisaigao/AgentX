package com.agentx.platform.domain.intake.model;

import com.agentx.platform.domain.shared.model.ActorRef;
import com.agentx.platform.domain.shared.model.AggregateRoot;
import com.agentx.platform.domain.shared.model.JsonPayload;

import java.util.Objects;

public record Ticket(
        String ticketId,
        String workflowRunId,
        TicketType type,
        TicketBlockingScope blockingScope,
        TicketStatus status,
        String title,
        ActorRef createdBy,
        ActorRef assignee,
        String originNodeId,
        String requirementDocId,
        Integer requirementDocVersion,
        JsonPayload payloadJson
) implements AggregateRoot<String> {

    public Ticket {
        Objects.requireNonNull(ticketId, "ticketId must not be null");
        Objects.requireNonNull(workflowRunId, "workflowRunId must not be null");
        Objects.requireNonNull(type, "type must not be null");
        Objects.requireNonNull(blockingScope, "blockingScope must not be null");
        Objects.requireNonNull(status, "status must not be null");
        Objects.requireNonNull(title, "title must not be null");
        Objects.requireNonNull(createdBy, "createdBy must not be null");
        Objects.requireNonNull(assignee, "assignee must not be null");
        Objects.requireNonNull(payloadJson, "payloadJson must not be null");
    }

    @Override
    public String aggregateId() {
        return ticketId;
    }
}
