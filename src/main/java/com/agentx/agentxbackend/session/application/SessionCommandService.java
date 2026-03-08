package com.agentx.agentxbackend.session.application;

import com.agentx.agentxbackend.session.application.port.in.SessionCommandUseCase;
import com.agentx.agentxbackend.session.application.port.in.SessionCompletionReadinessUseCase;
import com.agentx.agentxbackend.session.application.query.SessionCompletionReadiness;
import com.agentx.agentxbackend.session.application.port.out.DomainEventPublisher;
import com.agentx.agentxbackend.session.application.port.out.SessionRepository;
import com.agentx.agentxbackend.session.domain.event.SessionCreatedEvent;
import com.agentx.agentxbackend.session.domain.model.Session;
import com.agentx.agentxbackend.session.domain.model.SessionStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.NoSuchElementException;
import java.util.UUID;

@Service
public class SessionCommandService implements SessionCommandUseCase {

    private final SessionRepository sessionRepository;
    private final DomainEventPublisher domainEventPublisher;
    private final SessionCompletionReadinessUseCase sessionCompletionReadinessUseCase;

    public SessionCommandService(
        SessionRepository sessionRepository,
        DomainEventPublisher domainEventPublisher,
        SessionCompletionReadinessUseCase sessionCompletionReadinessUseCase
    ) {
        this.sessionRepository = sessionRepository;
        this.domainEventPublisher = domainEventPublisher;
        this.sessionCompletionReadinessUseCase = sessionCompletionReadinessUseCase;
    }

    @Override
    @Transactional
    public Session createSession(String title) {
        requireNotBlank(title, "title");
        Instant now = Instant.now();
        Session session = new Session(
            generateSessionId(),
            title.trim(),
            SessionStatus.ACTIVE,
            now,
            now
        );
        Session saved = sessionRepository.save(session);
        domainEventPublisher.publish(new SessionCreatedEvent(saved.sessionId(), saved.title()));
        return saved;
    }

    @Override
    public Session pauseSession(String sessionId) {
        requireNotBlank(sessionId, "sessionId");
        Session current = sessionRepository.findById(sessionId)
            .orElseThrow(() -> new NoSuchElementException("Session not found: " + sessionId));
        if (current.status() == SessionStatus.PAUSED) {
            return current;
        }
        if (current.status() == SessionStatus.COMPLETED) {
            throw new IllegalStateException("Cannot pause completed session: " + sessionId);
        }
        return sessionRepository.updateStatus(sessionId, SessionStatus.PAUSED);
    }

    @Override
    public Session resumeSession(String sessionId) {
        requireNotBlank(sessionId, "sessionId");
        Session current = sessionRepository.findById(sessionId)
            .orElseThrow(() -> new NoSuchElementException("Session not found: " + sessionId));
        if (current.status() == SessionStatus.ACTIVE) {
            return current;
        }
        if (current.status() == SessionStatus.COMPLETED) {
            throw new IllegalStateException("Cannot resume completed session: " + sessionId);
        }
        return sessionRepository.updateStatus(sessionId, SessionStatus.ACTIVE);
    }

    @Override
    public Session completeSession(String sessionId) {
        String normalizedSessionId = requireNotBlank(sessionId, "sessionId");
        Session current = sessionRepository.findById(normalizedSessionId)
            .orElseThrow(() -> new NoSuchElementException("Session not found: " + normalizedSessionId));
        if (current.status() == SessionStatus.COMPLETED) {
            return current;
        }
        SessionCompletionReadiness readiness = sessionCompletionReadinessUseCase.getCompletionReadiness(normalizedSessionId);
        if (!readiness.canComplete()) {
            String message = readiness.blockers().isEmpty()
                ? "Session cannot be completed: " + normalizedSessionId
                : readiness.blockers().get(0).replace(".", "") + ": " + normalizedSessionId;
            throw new IllegalStateException(message);
        }
        return sessionRepository.updateStatus(normalizedSessionId, SessionStatus.COMPLETED);
    }

    private static String requireNotBlank(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }

    private static String generateSessionId() {
        return "SES-" + UUID.randomUUID().toString().replace("-", "");
    }
}
