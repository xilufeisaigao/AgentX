package com.agentx.platform.controlplane.api.ticket;

import com.agentx.platform.controlplane.application.WorkflowCommandFacade;
import com.agentx.platform.controlplane.application.WorkflowCommandResult;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Validated
@RequestMapping("/api/v1/controlplane/tickets")
public class TicketCommandController {

    private final WorkflowCommandFacade workflowCommandFacade;

    public TicketCommandController(WorkflowCommandFacade workflowCommandFacade) {
        this.workflowCommandFacade = workflowCommandFacade;
    }

    @PostMapping("/{ticketId}/answer")
    public WorkflowCommandResult answerTicket(
            @PathVariable String ticketId,
            @Valid @RequestBody AnswerTicketRequest request
    ) {
        return workflowCommandFacade.answerTicket(ticketId, request.answer(), request.answeredByActorId());
    }

    public record AnswerTicketRequest(
            @NotBlank String answer,
            @NotBlank String answeredByActorId
    ) {
    }
}
