package com.agentx.agentxbackend.query.application;

import com.agentx.agentxbackend.query.application.port.out.ProgressReadRepository;
import com.agentx.agentxbackend.query.application.query.SessionProgressSnapshot;
import com.agentx.agentxbackend.query.domain.model.RunTimelineView;
import com.agentx.agentxbackend.query.domain.model.SessionProgressView;
import com.agentx.agentxbackend.query.domain.model.TaskBoardView;
import com.agentx.agentxbackend.query.domain.model.TicketInboxView;
import com.agentx.agentxbackend.session.application.port.in.SessionCompletionReadinessUseCase;
import com.agentx.agentxbackend.session.application.port.in.SessionHistoryQueryUseCase;
import com.agentx.agentxbackend.session.application.query.SessionCompletionReadiness;
import com.agentx.agentxbackend.session.application.query.SessionHistoryView;
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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProgressQueryServiceTest {

    @Mock
    private ProgressReadRepository progressReadRepository;
    @Mock
    private SessionHistoryQueryUseCase sessionHistoryQueryUseCase;
    @Mock
    private SessionCompletionReadinessUseCase sessionCompletionReadinessUseCase;
    @InjectMocks
    private ProgressQueryService service;

    @Test
    void getSessionProgressShouldMergeReadinessAndReturnWaitingUserPhase() {
        SessionHistoryView history = new SessionHistoryView(
            "SES-1",
            "Session 1",
            "ACTIVE",
            Instant.parse("2026-03-08T00:00:00Z"),
            Instant.parse("2026-03-08T01:00:00Z"),
            new SessionHistoryView.CurrentRequirementDoc(
                "REQ-1",
                2,
                2,
                "CONFIRMED",
                "Requirement",
                "markdown",
                Instant.parse("2026-03-08T00:30:00Z")
            )
        );
        SessionProgressSnapshot snapshot = new SessionProgressSnapshot(
            new SessionProgressView.TaskCounts(3, 0, 0, 1, 0, 1, 1, 0),
            new SessionProgressView.TicketCounts(2, 0, 1, 1, 0, 0),
            new SessionProgressView.RunCounts(1, 0, 1, 0, 0, 0),
            new SessionProgressView.LatestRun(
                "RUN-1",
                "TASK-1",
                "Need clarification",
                "MOD-1",
                "bootstrap",
                "WRK-1",
                "IMPL",
                "WAITING_FOREMAN",
                "NEED_CLARIFICATION",
                "Need more details",
                Instant.parse("2026-03-08T00:55:00Z"),
                Instant.parse("2026-03-08T00:45:00Z"),
                null,
                Instant.parse("2026-03-08T00:55:00Z")
            ),
            new SessionProgressView.DeliverySummary(false, 0, 0, null, null, null, null)
        );
        SessionCompletionReadiness readiness = new SessionCompletionReadiness(
            "SES-1",
            "ACTIVE",
            false,
            true,
            true,
            true,
            false,
            List.of("Session has active runs.")
        );

        when(sessionHistoryQueryUseCase.findSessionWithCurrentRequirementDoc("SES-1")).thenReturn(Optional.of(history));
        when(progressReadRepository.getSessionProgressSnapshot("SES-1")).thenReturn(snapshot);
        when(sessionCompletionReadinessUseCase.getCompletionReadiness("SES-1")).thenReturn(readiness);

        SessionProgressView view = service.getSessionProgress("SES-1");

        assertEquals("WAITING_USER", view.phase());
        assertEquals("Waiting for user response on 1 ticket(s).", view.blockerSummary());
        assertEquals("Respond to ticket", view.primaryAction());
        assertEquals("REQ-1", view.requirement().docId());
        assertEquals(1, view.ticketCounts().waitingUser());
        assertEquals(false, view.canCompleteSession());
    }

    @Test
    void getRunTimelineShouldCapLimitAndRejectMissingSession() {
        when(sessionHistoryQueryUseCase.findSessionWithCurrentRequirementDoc("SES-404")).thenReturn(Optional.empty());

        assertThrows(NoSuchElementException.class, () -> service.getRunTimeline("SES-404", 500));
    }

    @Test
    void getTicketInboxShouldNormalizeFilterBeforeRepositoryCall() {
        when(sessionHistoryQueryUseCase.findSessionWithCurrentRequirementDoc("SES-2"))
            .thenReturn(Optional.of(new SessionHistoryView(
                "SES-2",
                "Session 2",
                "ACTIVE",
                Instant.now(),
                Instant.now(),
                null
            )));
        when(progressReadRepository.getTicketInbox("SES-2", "WAITING_USER"))
            .thenReturn(new TicketInboxView("SES-2", "WAITING_USER", 0, 0, List.of()));

        TicketInboxView view = service.getTicketInbox("SES-2", "waiting_user");

        assertEquals("WAITING_USER", view.appliedStatusFilter());
        verify(progressReadRepository).getTicketInbox("SES-2", "WAITING_USER");
    }

    @Test
    void getTaskBoardShouldReturnRepositoryView() {
        TaskBoardView board = new TaskBoardView("SES-3", 0, 0, List.of());
        when(sessionHistoryQueryUseCase.findSessionWithCurrentRequirementDoc("SES-3"))
            .thenReturn(Optional.of(new SessionHistoryView("SES-3", "Session 3", "ACTIVE", Instant.now(), Instant.now(), null)));
        when(progressReadRepository.getTaskBoard("SES-3")).thenReturn(board);

        TaskBoardView result = service.getTaskBoard("SES-3");

        assertEquals(board, result);
    }

    @Test
    void getRunTimelineShouldReturnRepositoryViewWithCappedLimit() {
        RunTimelineView timeline = new RunTimelineView("SES-4", 0, List.of());
        when(sessionHistoryQueryUseCase.findSessionWithCurrentRequirementDoc("SES-4"))
            .thenReturn(Optional.of(new SessionHistoryView("SES-4", "Session 4", "ACTIVE", Instant.now(), Instant.now(), null)));
        when(progressReadRepository.getRunTimeline("SES-4", 200)).thenReturn(timeline);

        RunTimelineView result = service.getRunTimeline("SES-4", 500);

        assertEquals(timeline, result);
        verify(progressReadRepository).getRunTimeline("SES-4", 200);
    }

    @Test
    void getSessionProgressShouldNotTreatPartialDoneTasksAsDeliveryReady() {
        SessionHistoryView history = new SessionHistoryView(
            "SES-5",
            "Session 5",
            "ACTIVE",
            Instant.parse("2026-03-08T00:00:00Z"),
            Instant.parse("2026-03-08T01:00:00Z"),
            new SessionHistoryView.CurrentRequirementDoc(
                "REQ-5",
                1,
                1,
                "CONFIRMED",
                "Requirement",
                "markdown",
                Instant.parse("2026-03-08T00:30:00Z")
            )
        );
        SessionProgressSnapshot snapshot = new SessionProgressSnapshot(
            new SessionProgressView.TaskCounts(4, 0, 0, 1, 0, 0, 0, 1),
            new SessionProgressView.TicketCounts(0, 0, 0, 0, 0, 0),
            new SessionProgressView.RunCounts(0, 0, 0, 0, 0, 0),
            null,
            new SessionProgressView.DeliverySummary(false, 0, 1, null, null, null, null)
        );
        SessionCompletionReadiness readiness = new SessionCompletionReadiness(
            "SES-5",
            "ACTIVE",
            false,
            true,
            false,
            false,
            false,
            List.of("Session has unfinished tasks.")
        );

        when(sessionHistoryQueryUseCase.findSessionWithCurrentRequirementDoc("SES-5")).thenReturn(Optional.of(history));
        when(progressReadRepository.getSessionProgressSnapshot("SES-5")).thenReturn(snapshot);
        when(sessionCompletionReadinessUseCase.getCompletionReadiness("SES-5")).thenReturn(readiness);

        SessionProgressView view = service.getSessionProgress("SES-5");

        assertEquals("EXECUTING", view.phase());
        assertEquals("Tasks are waiting for eligible workers.", view.blockerSummary());
        assertEquals("Review overview", view.primaryAction());
    }
}
