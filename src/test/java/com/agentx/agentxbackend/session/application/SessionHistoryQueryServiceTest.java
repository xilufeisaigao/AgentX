package com.agentx.agentxbackend.session.application;

import com.agentx.agentxbackend.requirement.application.port.in.RequirementCurrentDoc;
import com.agentx.agentxbackend.requirement.application.port.in.RequirementDocQueryUseCase;
import com.agentx.agentxbackend.session.application.port.out.SessionRepository;
import com.agentx.agentxbackend.session.application.query.SessionHistoryQueryService;
import com.agentx.agentxbackend.session.application.query.SessionHistoryView;
import com.agentx.agentxbackend.session.domain.model.Session;
import com.agentx.agentxbackend.session.domain.model.SessionStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SessionHistoryQueryServiceTest {

    @Mock
    private SessionRepository sessionRepository;
    @Mock
    private RequirementDocQueryUseCase requirementDocQueryUseCase;
    @InjectMocks
    private SessionHistoryQueryService service;

    @Test
    void listSessionsWithCurrentRequirementDocShouldAttachCurrentDoc() {
        Session session = new Session(
            "SES-1",
            "Session 1",
            SessionStatus.ACTIVE,
            Instant.parse("2026-02-20T00:00:00Z"),
            Instant.parse("2026-02-21T00:00:00Z")
        );
        RequirementCurrentDoc currentDoc = new RequirementCurrentDoc(
            "REQ-1",
            2,
            1,
            "IN_REVIEW",
            "Doc title",
            "markdown content",
            Instant.parse("2026-02-21T01:00:00Z")
        );
        when(sessionRepository.findAllOrderByUpdatedAtDesc()).thenReturn(List.of(session));
        when(requirementDocQueryUseCase.findCurrentBySessionId("SES-1")).thenReturn(Optional.of(currentDoc));

        List<SessionHistoryView> result = service.listSessionsWithCurrentRequirementDoc();

        assertEquals(1, result.size());
        SessionHistoryView view = result.getFirst();
        assertEquals("SES-1", view.sessionId());
        assertEquals("ACTIVE", view.status());
        assertNotNull(view.currentRequirementDoc());
        assertEquals("REQ-1", view.currentRequirementDoc().docId());
        assertEquals(2, view.currentRequirementDoc().currentVersion());
        assertEquals("markdown content", view.currentRequirementDoc().content());
    }

    @Test
    void listSessionsWithCurrentRequirementDocShouldAllowSessionsWithoutRequirementDoc() {
        Session session = new Session(
            "SES-2",
            "Session 2",
            SessionStatus.PAUSED,
            Instant.parse("2026-02-20T00:00:00Z"),
            Instant.parse("2026-02-21T00:00:00Z")
        );
        when(sessionRepository.findAllOrderByUpdatedAtDesc()).thenReturn(List.of(session));
        when(requirementDocQueryUseCase.findCurrentBySessionId("SES-2")).thenReturn(Optional.empty());

        List<SessionHistoryView> result = service.listSessionsWithCurrentRequirementDoc();

        assertEquals(1, result.size());
        SessionHistoryView view = result.getFirst();
        assertEquals("SES-2", view.sessionId());
        assertEquals("PAUSED", view.status());
        assertNull(view.currentRequirementDoc());
    }

    @Test
    void findSessionWithCurrentRequirementDocShouldReturnViewWhenFound() {
        Session session = new Session(
            "SES-3",
            "Session 3",
            SessionStatus.ACTIVE,
            Instant.parse("2026-02-20T00:00:00Z"),
            Instant.parse("2026-02-21T00:00:00Z")
        );
        RequirementCurrentDoc currentDoc = new RequirementCurrentDoc(
            "REQ-3",
            1,
            null,
            "IN_REVIEW",
            "Doc 3",
            "content",
            Instant.parse("2026-02-21T01:00:00Z")
        );
        when(sessionRepository.findById("SES-3")).thenReturn(Optional.of(session));
        when(requirementDocQueryUseCase.findCurrentBySessionId("SES-3")).thenReturn(Optional.of(currentDoc));

        SessionHistoryView result = service.findSessionWithCurrentRequirementDoc("SES-3").orElseThrow();

        assertEquals("SES-3", result.sessionId());
        assertNotNull(result.currentRequirementDoc());
        assertEquals("REQ-3", result.currentRequirementDoc().docId());
    }

    @Test
    void findSessionWithCurrentRequirementDocShouldReturnEmptyWhenNotFound() {
        when(sessionRepository.findById("SES-404")).thenReturn(Optional.empty());

        Optional<SessionHistoryView> result = service.findSessionWithCurrentRequirementDoc("SES-404");

        assertFalse(result.isPresent());
    }

    @Test
    void findSessionWithCurrentRequirementDocShouldRejectBlankSessionId() {
        IllegalArgumentException ex = assertThrows(
            IllegalArgumentException.class,
            () -> service.findSessionWithCurrentRequirementDoc(" ")
        );
        assertEquals("sessionId must not be blank", ex.getMessage());
    }
}
