package com.agentx.agentxbackend.delivery.api;

import com.agentx.agentxbackend.delivery.application.port.in.DeliveryClonePublishUseCase;
import com.agentx.agentxbackend.delivery.domain.model.DeliveryClonePublication;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.Optional;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class DeliveryCloneControllerMockTest {

    @Mock
    private DeliveryClonePublishUseCase useCase;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        DeliveryCloneController controller = new DeliveryCloneController(useCase);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
            .setControllerAdvice(new DeliveryCloneExceptionHandler())
            .build();
    }

    @Test
    void publishShouldReturnCloneRepositoryInfo() throws Exception {
        DeliveryClonePublication publication = new DeliveryClonePublication(
            "SES-1",
            "agentx-session-SES-1.git",
            "git://127.0.0.1:19418/agentx-session-SES-1.git",
            Instant.parse("2026-02-23T00:00:00Z"),
            Instant.parse("2026-02-26T00:00:00Z")
        );
        when(useCase.publish("SES-1")).thenReturn(publication);

        mockMvc.perform(post("/api/v0/sessions/SES-1/delivery/clone-repo"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.session_id").value("SES-1"))
            .andExpect(jsonPath("$.repository_name").value("agentx-session-SES-1.git"))
            .andExpect(jsonPath("$.clone_url").value("git://127.0.0.1:19418/agentx-session-SES-1.git"))
            .andExpect(jsonPath("$.clone_command").value("git clone git://127.0.0.1:19418/agentx-session-SES-1.git"));
    }

    @Test
    void getShouldReturnNotFoundWhenNoActivePublication() throws Exception {
        when(useCase.findActive("SES-404")).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/v0/sessions/SES-404/delivery/clone-repo"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }
}
