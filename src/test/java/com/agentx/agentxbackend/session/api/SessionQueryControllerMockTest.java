package com.agentx.agentxbackend.session.api;

import com.agentx.agentxbackend.session.application.port.in.SessionHistoryQueryUseCase;
import com.agentx.agentxbackend.session.application.query.SessionHistoryView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class SessionQueryControllerMockTest {

    @Mock
    private SessionHistoryQueryUseCase useCase;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        SessionQueryController controller = new SessionQueryController(useCase);
        this.mockMvc = MockMvcBuilders.standaloneSetup(controller)
            .setControllerAdvice(new SessionExceptionHandler())
            .build();
    }

    @Test
    void listSessionsWithCurrentRequirementDocShouldReturnSessionArray() throws Exception {
        SessionHistoryView.CurrentRequirementDoc currentDoc = new SessionHistoryView.CurrentRequirementDoc(
            "REQ-1",
            2,
            1,
            "IN_REVIEW",
            "Doc title",
            "markdown content",
            Instant.parse("2026-02-21T01:00:00Z")
        );
        SessionHistoryView view = new SessionHistoryView(
            "SES-1",
            "session title",
            "ACTIVE",
            Instant.parse("2026-02-21T00:00:00Z"),
            Instant.parse("2026-02-21T01:00:00Z"),
            currentDoc
        );
        when(useCase.listSessionsWithCurrentRequirementDoc()).thenReturn(List.of(view));

        mockMvc.perform(get("/api/v0/sessions"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(1))
            .andExpect(jsonPath("$[0].session_id").value("SES-1"))
            .andExpect(jsonPath("$[0].status").value("ACTIVE"))
            .andExpect(jsonPath("$[0].current_requirement_doc.doc_id").value("REQ-1"))
            .andExpect(jsonPath("$[0].current_requirement_doc.current_version").value(2));
    }

    @Test
    void listSessionsWithCurrentRequirementDocShouldReturnNullDocWhenAbsent() throws Exception {
        SessionHistoryView view = new SessionHistoryView(
            "SES-2",
            "session 2",
            "PAUSED",
            Instant.parse("2026-02-21T00:00:00Z"),
            Instant.parse("2026-02-21T02:00:00Z"),
            null
        );
        when(useCase.listSessionsWithCurrentRequirementDoc()).thenReturn(List.of(view));

        mockMvc.perform(get("/api/v0/sessions"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].session_id").value("SES-2"))
            .andExpect(jsonPath("$[0].current_requirement_doc").isEmpty());
    }

    @Test
    void getSessionWithCurrentRequirementDocShouldReturnSingleSession() throws Exception {
        SessionHistoryView.CurrentRequirementDoc currentDoc = new SessionHistoryView.CurrentRequirementDoc(
            "REQ-3",
            5,
            4,
            "CONFIRMED",
            "Doc 3",
            "markdown",
            Instant.parse("2026-02-21T03:00:00Z")
        );
        SessionHistoryView view = new SessionHistoryView(
            "SES-3",
            "session 3",
            "ACTIVE",
            Instant.parse("2026-02-21T00:00:00Z"),
            Instant.parse("2026-02-21T03:00:00Z"),
            currentDoc
        );
        when(useCase.findSessionWithCurrentRequirementDoc("SES-3")).thenReturn(Optional.of(view));

        mockMvc.perform(get("/api/v0/sessions/SES-3"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.session_id").value("SES-3"))
            .andExpect(jsonPath("$.current_requirement_doc.doc_id").value("REQ-3"))
            .andExpect(jsonPath("$.current_requirement_doc.current_version").value(5));
    }

    @Test
    void getSessionWithCurrentRequirementDocShouldReturnNotFoundWhenMissing() throws Exception {
        when(useCase.findSessionWithCurrentRequirementDoc("SES-404")).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/v0/sessions/SES-404"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }
}
