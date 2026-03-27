package com.agentx.platform.domain.intake.policy;

import com.agentx.platform.domain.intake.model.RequirementDoc;
import com.agentx.platform.domain.intake.model.RequirementStatus;
import com.agentx.platform.domain.intake.model.Ticket;
import com.agentx.platform.domain.intake.model.TicketStatus;
import com.agentx.platform.domain.shared.error.DomainRuleViolation;

import java.util.Collection;

public final class IntakePolicy {

    private IntakePolicy() {
    }

    public static boolean isPlanningReady(RequirementDoc requirementDoc, Collection<Ticket> tickets) {
        return requirementDoc.status() == RequirementStatus.CONFIRMED
                && tickets.stream().noneMatch(ticket -> ticket.status() == TicketStatus.OPEN
                || ticket.status() == TicketStatus.CLAIMED);
    }

    public static void assertRequirementConfirmed(RequirementDoc requirementDoc) {
        if (requirementDoc.status() != RequirementStatus.CONFIRMED) {
            throw new DomainRuleViolation("requirement must be confirmed before planning");
        }
    }

    public static void assertTicketCanBeAnswered(Ticket ticket) {
        if (ticket.status() != TicketStatus.OPEN && ticket.status() != TicketStatus.CLAIMED) {
            throw new DomainRuleViolation("ticket is not answerable in current state");
        }
    }
}
