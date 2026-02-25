package com.agentx.agentxbackend.process.api;

import com.agentx.agentxbackend.process.application.ArchitectTicketAutoProcessorService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class ArchitectAutomationControllerMockTest {

    @Mock
    private ArchitectTicketAutoProcessorService autoProcessorService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        ArchitectAutomationController controller = new ArchitectAutomationController(autoProcessorService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    void autoProcessShouldReturnProcessedResult() throws Exception {
        when(autoProcessorService.processOpenArchitectTickets("SES-1", 5))
            .thenReturn(
                new ArchitectTicketAutoProcessorService.AutoProcessResult(
                    1,
                    List.of("TCK-1"),
                    List.of("TCK-2")
                )
            );

        mockMvc.perform(post("/api/v0/architect/auto-process")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "session_id":"SES-1",
                      "max_tickets":5
                    }
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.processed_count").value(1))
            .andExpect(jsonPath("$.processed_ticket_ids[0]").value("TCK-1"))
            .andExpect(jsonPath("$.skipped_ticket_ids[0]").value("TCK-2"));
    }
}
