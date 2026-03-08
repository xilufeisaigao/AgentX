package com.agentx.agentxbackend.session.application;

import com.agentx.agentxbackend.session.application.port.in.SessionCompletionReadinessUseCase;
import com.agentx.agentxbackend.session.application.query.SessionCompletionReadiness;
import com.agentx.agentxbackend.session.application.port.out.DomainEventPublisher;
import com.agentx.agentxbackend.session.application.port.out.SessionRepository;
import com.agentx.agentxbackend.session.domain.model.Session;
import com.agentx.agentxbackend.session.domain.model.SessionStatus;
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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SessionCommandServiceTest {

    @Mock
    private SessionRepository sessionRepository;
    @Mock
    private DomainEventPublisher domainEventPublisher;
    @Mock
    private SessionCompletionReadinessUseCase sessionCompletionReadinessUseCase;
    @InjectMocks
    private SessionCommandService service;

    @Test
    void createSessionShouldPersistActiveSession() {
        when(sessionRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        Session session = service.createSession("My session");

        assertTrue(session.sessionId().startsWith("SES-"));
        assertEquals("My session", session.title());
        assertEquals(SessionStatus.ACTIVE, session.status());
        verify(sessionRepository).save(any());
        verify(domainEventPublisher).publish(any());
    }

    @Test
    void createSessionShouldRejectBlankTitle() {
        IllegalArgumentException ex = assertThrows(
            IllegalArgumentException.class,
            () -> service.createSession(" ")
        );
        assertTrue(ex.getMessage().contains("title"));
    }

    @Test
    void pauseSessionShouldMoveActiveToPaused() {
        Session current = new Session(
            "SES-1",
            "x",
            SessionStatus.ACTIVE,
            Instant.now(),
            Instant.now()
        );
        Session paused = new Session(
            "SES-1",
            "x",
            SessionStatus.PAUSED,
            current.createdAt(),
            Instant.now()
        );
        when(sessionRepository.findById("SES-1")).thenReturn(Optional.of(current));
        when(sessionRepository.updateStatus("SES-1", SessionStatus.PAUSED)).thenReturn(paused);

        Session result = service.pauseSession("SES-1");

        assertEquals(SessionStatus.PAUSED, result.status());
    }

    @Test
    void pauseSessionShouldRejectCompleted() {
        Session current = new Session(
            "SES-1",
            "x",
            SessionStatus.COMPLETED,
            Instant.now(),
            Instant.now()
        );
        when(sessionRepository.findById("SES-1")).thenReturn(Optional.of(current));

        assertThrows(IllegalStateException.class, () -> service.pauseSession("SES-1"));
        verify(sessionRepository, never()).updateStatus(any(), any());
    }

    @Test
    void resumeSessionShouldMovePausedToActive() {
        Session current = new Session(
            "SES-1",
            "x",
            SessionStatus.PAUSED,
            Instant.now(),
            Instant.now()
        );
        Session active = new Session(
            "SES-1",
            "x",
            SessionStatus.ACTIVE,
            current.createdAt(),
            Instant.now()
        );
        when(sessionRepository.findById("SES-1")).thenReturn(Optional.of(current));
        when(sessionRepository.updateStatus("SES-1", SessionStatus.ACTIVE)).thenReturn(active);

        Session result = service.resumeSession("SES-1");
        assertEquals(SessionStatus.ACTIVE, result.status());
    }

    @Test
    void completeSessionShouldRejectWhenReadinessHasBlocker() {
        Session current = new Session(
            "SES-1",
            "x",
            SessionStatus.ACTIVE,
            Instant.now(),
            Instant.now()
        );
        when(sessionRepository.findById("SES-1")).thenReturn(Optional.of(current));
        when(sessionCompletionReadinessUseCase.getCompletionReadiness("SES-1"))
            .thenReturn(new SessionCompletionReadiness(
                "SES-1",
                "ACTIVE",
                false,
                true,
                false,
                false,
                false,
                List.of("Session has unfinished tasks.")
            ));

        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> service.completeSession("SES-1"));

        assertTrue(ex.getMessage().contains("Session has unfinished tasks"));
        verify(sessionRepository, never()).updateStatus(any(), any());
    }

    @Test
    void completeSessionShouldMoveToCompletedWhenReadinessAllows() {
        Session current = new Session(
            "SES-1",
            "x",
            SessionStatus.ACTIVE,
            Instant.now(),
            Instant.now()
        );
        Session completed = new Session(
            "SES-1",
            "x",
            SessionStatus.COMPLETED,
            current.createdAt(),
            Instant.now()
        );
        when(sessionRepository.findById("SES-1")).thenReturn(Optional.of(current));
        when(sessionCompletionReadinessUseCase.getCompletionReadiness("SES-1"))
            .thenReturn(new SessionCompletionReadiness(
                "SES-1",
                "ACTIVE",
                true,
                false,
                false,
                false,
                true,
                List.of()
            ));
        when(sessionRepository.updateStatus("SES-1", SessionStatus.COMPLETED)).thenReturn(completed);

        Session result = service.completeSession("SES-1");

        assertEquals(SessionStatus.COMPLETED, result.status());
    }

    @Test
    void sessionOperationsShouldFailWhenSessionMissing() {
        when(sessionRepository.findById("SES-404")).thenReturn(Optional.empty());

        assertThrows(NoSuchElementException.class, () -> service.pauseSession("SES-404"));
        assertThrows(NoSuchElementException.class, () -> service.resumeSession("SES-404"));
        assertThrows(NoSuchElementException.class, () -> service.completeSession("SES-404"));
    }
}
