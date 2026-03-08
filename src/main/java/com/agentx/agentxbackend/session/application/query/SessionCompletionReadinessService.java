package com.agentx.agentxbackend.session.application.query;

import com.agentx.agentxbackend.execution.application.port.in.RunQueryUseCase;
import com.agentx.agentxbackend.planning.application.port.in.TaskQueryUseCase;
import com.agentx.agentxbackend.session.application.port.in.SessionCompletionReadinessUseCase;
import com.agentx.agentxbackend.session.application.port.out.DeliveryProofPort;
import com.agentx.agentxbackend.session.application.port.out.SessionRepository;
import com.agentx.agentxbackend.session.domain.model.Session;
import com.agentx.agentxbackend.session.domain.model.SessionStatus;
import com.agentx.agentxbackend.ticket.application.port.in.TicketQueryUseCase;
import com.agentx.agentxbackend.ticket.domain.model.TicketStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;

@Service
public class SessionCompletionReadinessService implements SessionCompletionReadinessUseCase {

    private final SessionRepository sessionRepository;
    private final DeliveryProofPort deliveryProofPort;
    private final TaskQueryUseCase taskQueryUseCase;
    private final RunQueryUseCase runQueryUseCase;
    private final TicketQueryUseCase ticketQueryUseCase;

    public SessionCompletionReadinessService(
        SessionRepository sessionRepository,
        DeliveryProofPort deliveryProofPort,
        TaskQueryUseCase taskQueryUseCase,
        RunQueryUseCase runQueryUseCase,
        TicketQueryUseCase ticketQueryUseCase
    ) {
        this.sessionRepository = sessionRepository;
        this.deliveryProofPort = deliveryProofPort;
        this.taskQueryUseCase = taskQueryUseCase;
        this.runQueryUseCase = runQueryUseCase;
        this.ticketQueryUseCase = ticketQueryUseCase;
    }

    @Override
    @Transactional(readOnly = true)
    public SessionCompletionReadiness getCompletionReadiness(String sessionId) {
        String normalizedSessionId = requireNotBlank(sessionId, "sessionId");
        Session session = sessionRepository.findById(normalizedSessionId)
            .orElseThrow(() -> new NoSuchElementException("Session not found: " + normalizedSessionId));

        boolean hasUnfinishedTasks = taskQueryUseCase.hasNonDoneTasksBySession(normalizedSessionId);
        boolean hasActiveRuns = runQueryUseCase.hasActiveRunsBySession(normalizedSessionId);
        boolean hasActionableTickets = hasActionableTickets(normalizedSessionId);
        boolean hasDeliveryTagOnMain = deliveryProofPort.hasAtLeastOneDeliveryTagOnMain(normalizedSessionId);

        List<String> blockers = new ArrayList<>();
        if (session.status() == SessionStatus.PAUSED) {
            blockers.add("Session must be ACTIVE before completion.");
        } else if (session.status() != SessionStatus.ACTIVE && session.status() != SessionStatus.COMPLETED) {
            blockers.add("Session is not in completable status: " + session.status().name());
        }
        if (hasUnfinishedTasks) {
            blockers.add("Session has unfinished tasks.");
        }
        if (hasActiveRuns) {
            blockers.add("Session has active runs.");
        }
        if (hasActionableTickets) {
            blockers.add("Session has open tickets.");
        }
        if (!hasDeliveryTagOnMain) {
            blockers.add("No delivery tag on main.");
        }

        boolean canComplete = session.status() == SessionStatus.ACTIVE && blockers.isEmpty();
        return new SessionCompletionReadiness(
            normalizedSessionId,
            session.status().name(),
            canComplete,
            hasUnfinishedTasks,
            hasActiveRuns,
            hasActionableTickets,
            hasDeliveryTagOnMain,
            List.copyOf(blockers)
        );
    }

    private boolean hasActionableTickets(String sessionId) {
        return ticketQueryUseCase.listBySession(sessionId, null, null, null)
            .stream()
            .anyMatch(ticket -> ticket != null
                && ticket.status() != null
                && ticket.status() != TicketStatus.DONE
                && ticket.status() != TicketStatus.BLOCKED);
    }

    private static String requireNotBlank(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }
}
