package com.agentx.agentxbackend.session.application;

import com.agentx.agentxbackend.execution.application.port.in.RunQueryUseCase;
import com.agentx.agentxbackend.planning.application.port.in.TaskQueryUseCase;
import com.agentx.agentxbackend.session.application.port.out.DeliveryProofPort;
import com.agentx.agentxbackend.session.application.port.out.SessionRepository;
import com.agentx.agentxbackend.session.application.query.SessionCompletionReadiness;
import com.agentx.agentxbackend.session.application.query.SessionCompletionReadinessService;
import com.agentx.agentxbackend.session.domain.model.Session;
import com.agentx.agentxbackend.session.domain.model.SessionStatus;
import com.agentx.agentxbackend.ticket.application.port.in.TicketQueryUseCase;
import com.agentx.agentxbackend.ticket.domain.model.Ticket;
import com.agentx.agentxbackend.ticket.domain.model.TicketStatus;
import com.agentx.agentxbackend.ticket.domain.model.TicketType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SessionCompletionReadinessServiceTest {

    @Mock
    private SessionRepository sessionRepository;
    @Mock
    private DeliveryProofPort deliveryProofPort;
    @Mock
    private TaskQueryUseCase taskQueryUseCase;
    @Mock
    private RunQueryUseCase runQueryUseCase;
    @Mock
    private TicketQueryUseCase ticketQueryUseCase;
    @InjectMocks
    private SessionCompletionReadinessService service;

    @Test
    void getCompletionReadinessShouldReturnReadyWhenAllGatesPass() {
        Session session = new Session("SES-1", "ready", SessionStatus.ACTIVE, Instant.now(), Instant.now());
        when(sessionRepository.findById("SES-1")).thenReturn(Optional.of(session));
        when(taskQueryUseCase.hasNonDoneTasksBySession("SES-1")).thenReturn(false);
        when(runQueryUseCase.hasActiveRunsBySession("SES-1")).thenReturn(false);
        when(ticketQueryUseCase.listBySession("SES-1", null, null, null)).thenReturn(List.of());
        when(deliveryProofPort.hasAtLeastOneDeliveryTagOnMain("SES-1")).thenReturn(true);

        SessionCompletionReadiness readiness = service.getCompletionReadiness("SES-1");

        assertTrue(readiness.canComplete());
        assertTrue(readiness.blockers().isEmpty());
        assertTrue(readiness.hasDeliveryTagOnMain());
    }

    @Test
    void getCompletionReadinessShouldAccumulateBlockers() {
        Session session = new Session("SES-2", "blocked", SessionStatus.PAUSED, Instant.now(), Instant.now());
        when(sessionRepository.findById("SES-2")).thenReturn(Optional.of(session));
        when(taskQueryUseCase.hasNonDoneTasksBySession("SES-2")).thenReturn(true);
        when(runQueryUseCase.hasActiveRunsBySession("SES-2")).thenReturn(true);
        when(ticketQueryUseCase.listBySession("SES-2", null, null, null)).thenReturn(List.of(new Ticket(
            "TCK-1",
            "SES-2",
            TicketType.CLARIFICATION,
            TicketStatus.WAITING_USER,
            "Need answer",
            "architect_agent",
            "architect_agent",
            null,
            null,
            "{}",
            null,
            null,
            Instant.now(),
            Instant.now()
        )));
        when(deliveryProofPort.hasAtLeastOneDeliveryTagOnMain("SES-2")).thenReturn(false);

        SessionCompletionReadiness readiness = service.getCompletionReadiness("SES-2");

        assertFalse(readiness.canComplete());
        assertEquals("PAUSED", readiness.sessionStatus());
        assertTrue(readiness.hasUnfinishedTasks());
        assertTrue(readiness.hasActiveRuns());
        assertTrue(readiness.hasActionableTickets());
        assertFalse(readiness.hasDeliveryTagOnMain());
        assertEquals(5, readiness.blockers().size());
    }

    @Test
    void getCompletionReadinessShouldRejectMissingSession() {
        when(sessionRepository.findById("SES-404")).thenReturn(Optional.empty());

        assertThrows(NoSuchElementException.class, () -> service.getCompletionReadiness("SES-404"));
    }
}
