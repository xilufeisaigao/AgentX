package com.agentx.agentxbackend.session.application.port.out;

import com.agentx.agentxbackend.session.domain.model.Session;
import com.agentx.agentxbackend.session.domain.model.SessionStatus;

import java.util.List;
import java.util.Optional;

public interface SessionRepository {

    Session save(Session session);

    Optional<Session> findById(String sessionId);

    List<Session> findAllOrderByUpdatedAtDesc();

    Session updateStatus(String sessionId, SessionStatus status);
}
