package com.agentx.platform.runtime.application.workflow;

import com.agentx.platform.domain.shared.model.ActorRef;

import java.util.Objects;

public record AnswerTicketCommand(
        String ticketId,
        String answer,
        ActorRef answeredBy
) {

    public AnswerTicketCommand {
        Objects.requireNonNull(ticketId, "ticketId must not be null");
        Objects.requireNonNull(answer, "answer must not be null");
        Objects.requireNonNull(answeredBy, "answeredBy must not be null");
    }
}
