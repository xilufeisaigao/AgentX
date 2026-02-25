package com.agentx.agentxbackend.session.application.query;

import com.agentx.agentxbackend.requirement.application.port.in.RequirementCurrentDoc;
import com.agentx.agentxbackend.requirement.application.port.in.RequirementDocQueryUseCase;
import com.agentx.agentxbackend.session.application.port.in.SessionHistoryQueryUseCase;
import com.agentx.agentxbackend.session.application.port.out.SessionRepository;
import com.agentx.agentxbackend.session.domain.model.Session;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

@Service
public class SessionHistoryQueryService implements SessionHistoryQueryUseCase {

    private final SessionRepository sessionRepository;
    private final RequirementDocQueryUseCase requirementDocQueryUseCase;

    public SessionHistoryQueryService(
        SessionRepository sessionRepository,
        RequirementDocQueryUseCase requirementDocQueryUseCase
    ) {
        this.sessionRepository = sessionRepository;
        this.requirementDocQueryUseCase = requirementDocQueryUseCase;
    }

    @Override
    public List<SessionHistoryView> listSessionsWithCurrentRequirementDoc() {
        return sessionRepository.findAllOrderByUpdatedAtDesc()
            .stream()
            .map(this::toView)
            .toList();
    }

    @Override
    public Optional<SessionHistoryView> findSessionWithCurrentRequirementDoc(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("sessionId must not be blank");
        }
        return sessionRepository.findById(sessionId).map(this::toView);
    }

    private SessionHistoryView toView(Session session) {
        SessionHistoryView.CurrentRequirementDoc currentRequirement = requirementDocQueryUseCase
            .findCurrentBySessionId(session.sessionId())
            .map(this::toRequirementDocView)
            .orElse(null);

        return new SessionHistoryView(
            session.sessionId(),
            session.title(),
            session.status().name(),
            session.createdAt(),
            session.updatedAt(),
            currentRequirement
        );
    }

    private SessionHistoryView.CurrentRequirementDoc toRequirementDocView(RequirementCurrentDoc currentDoc) {
        return new SessionHistoryView.CurrentRequirementDoc(
            currentDoc.docId(),
            currentDoc.currentVersion(),
            currentDoc.confirmedVersion(),
            currentDoc.status(),
            currentDoc.title(),
            currentDoc.content(),
            currentDoc.updatedAt()
        );
    }
}
