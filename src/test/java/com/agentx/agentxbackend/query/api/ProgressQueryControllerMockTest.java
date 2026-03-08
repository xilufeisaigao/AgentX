package com.agentx.agentxbackend.query.api;

import com.agentx.agentxbackend.query.application.port.in.ProgressQueryUseCase;
import com.agentx.agentxbackend.query.domain.model.RunTimelineView;
import com.agentx.agentxbackend.query.domain.model.SessionProgressView;
import com.agentx.agentxbackend.query.domain.model.TaskBoardView;
import com.agentx.agentxbackend.query.domain.model.TicketInboxView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class ProgressQueryControllerMockTest {

    @Mock
    private ProgressQueryUseCase useCase;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        ProgressQueryController controller = new ProgressQueryController(useCase);
        this.mockMvc = MockMvcBuilders.standaloneSetup(controller)
            .setControllerAdvice(new ProgressQueryExceptionHandler())
            .build();
    }

    @Test
    void getSessionProgressShouldReturnProgressPayload() throws Exception {
        when(useCase.getSessionProgress("SES-1")).thenReturn(new SessionProgressView(
            "SES-1",
            "Session 1",
            "ACTIVE",
            "EXECUTING",
            "Worker execution is in progress.",
            "Review task progress",
            new SessionProgressView.RequirementSummary(
                "REQ-1",
                2,
                2,
                "CONFIRMED",
                "Requirement",
                Instant.parse("2026-03-08T00:30:00Z")
            ),
            new SessionProgressView.TaskCounts(1, 0, 0, 0, 0, 1, 0, 0),
            new SessionProgressView.TicketCounts(0, 0, 0, 0, 0, 0),
            new SessionProgressView.RunCounts(1, 1, 0, 0, 0, 0),
            null,
            new SessionProgressView.DeliverySummary(false, 0, 0, null, null, null, null),
            false,
            List.of("Session has active runs."),
            Instant.parse("2026-03-08T00:00:00Z"),
            Instant.parse("2026-03-08T01:00:00Z")
        ));

        mockMvc.perform(get("/api/v0/sessions/SES-1/progress"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.sessionId").value("SES-1"))
            .andExpect(jsonPath("$.phase").value("EXECUTING"))
            .andExpect(jsonPath("$.taskCounts.assigned").value(1))
            .andExpect(jsonPath("$.completionBlockers[0]").value("Session has active runs."));
    }

    @Test
    void getTicketInboxShouldReturnTicketItems() throws Exception {
        when(useCase.getTicketInbox("SES-2", "WAITING_USER")).thenReturn(new TicketInboxView(
            "SES-2",
            "WAITING_USER",
            1,
            1,
            List.of(new TicketInboxView.TicketItem(
                "TCK-1",
                "CLARIFICATION",
                "WAITING_USER",
                "Need answer",
                "architect_agent",
                "architect_agent",
                null,
                null,
                "{}",
                "architect-agent-auto",
                Instant.parse("2026-03-08T01:05:00Z"),
                Instant.parse("2026-03-08T01:00:00Z"),
                Instant.parse("2026-03-08T01:00:00Z"),
                "DECISION_REQUESTED",
                "Please answer",
                "{\"request_kind\":\"CLARIFICATION\"}",
                Instant.parse("2026-03-08T01:01:00Z"),
                "RUN-1",
                "TASK-1",
                "CLARIFICATION",
                "Please answer",
                true
            ))
        ));

        mockMvc.perform(get("/api/v0/sessions/SES-2/ticket-inbox").param("status", "WAITING_USER"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.sessionId").value("SES-2"))
            .andExpect(jsonPath("$.tickets[0].ticketId").value("TCK-1"))
            .andExpect(jsonPath("$.tickets[0].needsUserAction").value(true));
    }

    @Test
    void getTaskBoardShouldReturnModuleGroups() throws Exception {
        when(useCase.getTaskBoard("SES-3")).thenReturn(new TaskBoardView(
            "SES-3",
            1,
            1,
            List.of(new TaskBoardView.ModuleLane(
                "MOD-1",
                "bootstrap",
                "Bootstrap lane",
                List.of(new TaskBoardView.TaskCard(
                    "TASK-1",
                    "Init",
                    "tmpl.init.v0",
                    "ASSIGNED",
                    "RUN-1",
                    "[\"TP-JAVA-21\"]",
                    List.of(),
                    "CTXS-1",
                    "READY",
                    "IMPL",
                    Instant.parse("2026-03-08T00:30:00Z"),
                    "RUN-1",
                    "RUNNING",
                    "IMPL",
                    Instant.parse("2026-03-08T00:45:00Z"),
                    null,
                    null,
                    null
                ))
            ))
        ));

        mockMvc.perform(get("/api/v0/sessions/SES-3/task-board"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.modules[0].moduleName").value("bootstrap"))
            .andExpect(jsonPath("$.modules[0].tasks[0].taskId").value("TASK-1"));
    }

    @Test
    void getRunTimelineShouldUseProvidedLimit() throws Exception {
        when(useCase.getRunTimeline("SES-4", 12)).thenReturn(new RunTimelineView(
            "SES-4",
            1,
            List.of(new RunTimelineView.RunItem(
                "RUN-1",
                "TASK-1",
                "Init",
                "MOD-1",
                "bootstrap",
                "WRK-1",
                "IMPL",
                "RUNNING",
                "RUN_STARTED",
                "Run started",
                null,
                Instant.parse("2026-03-08T00:30:00Z"),
                Instant.parse("2026-03-08T00:30:00Z"),
                null,
                "run/RUN-1"
            ))
        ));

        mockMvc.perform(get("/api/v0/sessions/SES-4/run-timeline").param("limit", "12"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items[0].runId").value("RUN-1"))
            .andExpect(jsonPath("$.items[0].eventType").value("RUN_STARTED"));
    }

    @Test
    void getProgressShouldReturnNotFoundWhenSessionMissing() throws Exception {
        when(useCase.getSessionProgress("SES-404")).thenThrow(new java.util.NoSuchElementException("Session not found: SES-404"));

        mockMvc.perform(get("/api/v0/sessions/SES-404/progress"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }
}
