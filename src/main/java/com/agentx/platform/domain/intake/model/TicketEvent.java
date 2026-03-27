package com.agentx.platform.domain.intake.model;

import com.agentx.platform.domain.shared.model.ActorRef;
import com.agentx.platform.domain.shared.model.JsonPayload;

import java.util.Objects;

public record TicketEvent(
        String eventId,
        String ticketId,
        String eventType,
        ActorRef actor,
        String body,
        JsonPayload dataJson
) {

    public TicketEvent {
        Objects.requireNonNull(eventId, "eventId must not be null");
        Objects.requireNonNull(ticketId, "ticketId must not be null");
        Objects.requireNonNull(eventType, "eventType must not be null");
        Objects.requireNonNull(actor, "actor must not be null");
        Objects.requireNonNull(body, "body must not be null");
    }
}
