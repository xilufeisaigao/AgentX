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
        // taskId is only populated for TASK_BLOCKING tickets.
        // GLOBAL_BLOCKING intentionally keeps this null so workflow-level tickets do not masquerade as task truth.
        String taskId,
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

    public Ticket(
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
    ) {
        this(
                ticketId,
                workflowRunId,
                type,
                blockingScope,
                status,
                title,
                createdBy,
                assignee,
                originNodeId,
                requirementDocId,
                requirementDocVersion,
                null,
                payloadJson
        );
    }

    @Override
    public String aggregateId() {
        return ticketId;
    }
}
