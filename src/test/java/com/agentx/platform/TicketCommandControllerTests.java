package com.agentx.platform;

import com.agentx.platform.controlplane.api.ControlplaneExceptionHandler;
import com.agentx.platform.controlplane.api.ticket.TicketCommandController;
import com.agentx.platform.controlplane.application.WorkflowCommandFacade;
import com.agentx.platform.controlplane.application.WorkflowCommandResult;
import com.agentx.platform.domain.flow.model.WorkflowRunStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

import java.util.Map;
import java.util.NoSuchElementException;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class TicketCommandControllerTests {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void shouldAnswerTicketViaHttp() throws Exception {
        WorkflowCommandFacade facade = mock(WorkflowCommandFacade.class);
        when(facade.answerTicket("ticket-1", "已补充需求", "reviewer-1")).thenReturn(new WorkflowCommandResult(
                "workflow-1",
                WorkflowRunStatus.ACTIVE,
                null,
                null,
                null,
                null,
                0,
                0,
                0,
                Map.of()
        ));
        MockMvc mockMvc = mockMvc(new TicketCommandController(facade));

        mockMvc.perform(post("/api/v1/controlplane/tickets/ticket-1/answer")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "answer", "已补充需求",
                                "answeredByActorId", "reviewer-1"
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.workflowRunId").value("workflow-1"));

        verify(facade).answerTicket("ticket-1", "已补充需求", "reviewer-1");
    }

    @Test
    void shouldRejectBlankAnswer() throws Exception {
        WorkflowCommandFacade facade = mock(WorkflowCommandFacade.class);
        MockMvc mockMvc = mockMvc(new TicketCommandController(facade));

        mockMvc.perform(post("/api/v1/controlplane/tickets/ticket-1/answer")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "answer", "",
                                "answeredByActorId", "reviewer-1"
                        ))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors.answer").exists());
    }

    @Test
    void shouldMapTicketNotFound() throws Exception {
        WorkflowCommandFacade facade = mock(WorkflowCommandFacade.class);
        when(facade.answerTicket("ticket-404", "补充信息", "reviewer-1"))
                .thenThrow(new NoSuchElementException("ticket not found: ticket-404"));
        MockMvc mockMvc = mockMvc(new TicketCommandController(facade));

        mockMvc.perform(post("/api/v1/controlplane/tickets/ticket-404/answer")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "answer", "补充信息",
                                "answeredByActorId", "reviewer-1"
                        ))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("ticket not found: ticket-404"));
    }

    private MockMvc mockMvc(TicketCommandController controller) {
        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
        return MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new ControlplaneExceptionHandler())
                .setValidator(validator)
                .build();
    }
}
