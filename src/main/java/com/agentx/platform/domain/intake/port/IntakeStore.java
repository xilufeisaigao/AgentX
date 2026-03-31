package com.agentx.platform.domain.intake.port;

import com.agentx.platform.domain.intake.model.RequirementDoc;
import com.agentx.platform.domain.intake.model.RequirementVersion;
import com.agentx.platform.domain.intake.model.Ticket;
import com.agentx.platform.domain.intake.model.TicketEvent;

import java.util.List;
import java.util.Optional;

public interface IntakeStore {

    Optional<RequirementDoc> findRequirement(String docId);

    Optional<RequirementDoc> findRequirementByWorkflow(String workflowRunId);

    List<RequirementVersion> listRequirementVersions(String docId);

    Optional<Ticket> findTicket(String ticketId);

    List<Ticket> listTicketsForWorkflow(String workflowRunId);

    List<TicketEvent> listTicketEvents(String ticketId);

    List<Ticket> listOpenTickets(String workflowRunId);

    boolean hasOpenTaskBlocker(String taskId);

    void saveRequirement(RequirementDoc requirementDoc);

    void appendRequirementVersion(RequirementVersion version);

    void saveTicket(Ticket ticket);

    void appendTicketEvent(TicketEvent event);
}
