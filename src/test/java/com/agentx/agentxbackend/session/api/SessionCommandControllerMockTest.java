package com.agentx.agentxbackend.session.api;

import com.agentx.agentxbackend.session.application.port.in.SessionCommandUseCase;
import com.agentx.agentxbackend.session.domain.model.Session;
import com.agentx.agentxbackend.session.domain.model.SessionStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class SessionCommandControllerMockTest {

    @Mock
    private SessionCommandUseCase useCase;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        SessionCommandController controller = new SessionCommandController(useCase);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
            .setControllerAdvice(new SessionExceptionHandler())
            .build();
    }

    @Test
    void createSessionShouldReturnSession() throws Exception {
        Session session = new Session(
            "SES-1",
            "my session",
            SessionStatus.ACTIVE,
            Instant.parse("2026-02-21T00:00:00Z"),
            Instant.parse("2026-02-21T00:00:00Z")
        );
        when(useCase.createSession("my session")).thenReturn(session);

        mockMvc.perform(post("/api/v0/sessions")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"my session\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.session_id").value("SES-1"))
            .andExpect(jsonPath("$.status").value("ACTIVE"));
    }

    @Test
    void pauseResumeEndpointsShouldReturnSession() throws Exception {
        Session paused = new Session(
            "SES-2",
            "s2",
            SessionStatus.PAUSED,
            Instant.parse("2026-02-21T00:00:00Z"),
            Instant.parse("2026-02-21T00:10:00Z")
        );
        Session active = new Session(
            "SES-2",
            "s2",
            SessionStatus.ACTIVE,
            Instant.parse("2026-02-21T00:00:00Z"),
            Instant.parse("2026-02-21T00:20:00Z")
        );
        when(useCase.pauseSession("SES-2")).thenReturn(paused);
        when(useCase.resumeSession("SES-2")).thenReturn(active);

        mockMvc.perform(post("/api/v0/sessions/SES-2/pause"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("PAUSED"));

        mockMvc.perform(post("/api/v0/sessions/SES-2/resume"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("ACTIVE"));
    }
}
