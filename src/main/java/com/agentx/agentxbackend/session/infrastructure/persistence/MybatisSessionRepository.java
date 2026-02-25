package com.agentx.agentxbackend.session.infrastructure.persistence;

import com.agentx.agentxbackend.session.application.port.out.SessionRepository;
import com.agentx.agentxbackend.session.domain.model.Session;
import com.agentx.agentxbackend.session.domain.model.SessionStatus;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;

@Repository
public class MybatisSessionRepository implements SessionRepository {

    private final SessionMapper mapper;

    public MybatisSessionRepository(SessionMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public Session save(Session session) {
        int inserted = mapper.insert(toRow(session));
        if (inserted != 1) {
            throw new IllegalStateException("Failed to insert session: " + session.sessionId());
        }
        return session;
    }

    @Override
    public Optional<Session> findById(String sessionId) {
        SessionRow row = mapper.findById(sessionId);
        return Optional.ofNullable(row).map(this::toDomain);
    }

    @Override
    public List<Session> findAllOrderByUpdatedAtDesc() {
        return mapper.findAllOrderByUpdatedAtDesc()
            .stream()
            .map(this::toDomain)
            .toList();
    }

    @Override
    public Session updateStatus(String sessionId, SessionStatus status) {
        int updated = mapper.updateStatus(sessionId, status.name(), Timestamp.from(java.time.Instant.now()));
        if (updated != 1) {
            throw new IllegalStateException("Failed to update session status: " + sessionId);
        }
        return findById(sessionId)
            .orElseThrow(() -> new IllegalStateException("Session not found after update: " + sessionId));
    }

    private SessionRow toRow(Session session) {
        SessionRow row = new SessionRow();
        row.setSessionId(session.sessionId());
        row.setTitle(session.title());
        row.setStatus(session.status().name());
        row.setCreatedAt(Timestamp.from(session.createdAt()));
        row.setUpdatedAt(Timestamp.from(session.updatedAt()));
        return row;
    }

    private Session toDomain(SessionRow row) {
        return new Session(
            row.getSessionId(),
            row.getTitle(),
            SessionStatus.valueOf(row.getStatus()),
            row.getCreatedAt().toInstant(),
            row.getUpdatedAt().toInstant()
        );
    }
}
