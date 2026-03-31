package com.agentx.platform;

import com.agentx.platform.controlplane.api.ControlplaneExceptionHandler;
import com.agentx.platform.controlplane.api.workflow.WorkflowCommandController;
import com.agentx.platform.controlplane.application.WorkflowCommandFacade;
import com.agentx.platform.controlplane.application.WorkflowCommandResult;
import com.agentx.platform.domain.flow.model.WorkflowRunStatus;
import com.agentx.platform.domain.intake.model.RequirementStatus;
import com.agentx.platform.support.TestStackProfiles;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

import java.util.Map;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class WorkflowCommandControllerTests {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void shouldStartWorkflowViaHttp() throws Exception {
        WorkflowCommandFacade facade = mock(WorkflowCommandFacade.class);
        when(facade.startWorkflow(
                "学生管理系统",
                "学生管理系统需求",
                "做一个学生管理系统",
                TestStackProfiles.DEFAULT_PROFILE_ID,
                "user-1",
                true
        ))
                .thenReturn(result("workflow-1"));
        MockMvc mockMvc = mockMvc(new WorkflowCommandController(facade));

        mockMvc.perform(post("/api/v1/controlplane/workflows")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "title", "学生管理系统",
                                "requirementTitle", "学生管理系统需求",
                                "requirementContent", "做一个学生管理系统",
                                "profileId", TestStackProfiles.DEFAULT_PROFILE_ID,
                                "createdByActorId", "user-1",
                                "autoAgentMode", true
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.workflowRunId").value("workflow-1"))
                .andExpect(jsonPath("$.requirementStatus").value("IN_REVIEW"))
                .andExpect(jsonPath("$.pendingHumanTickets").value(1));

        verify(facade).startWorkflow(
                "学生管理系统",
                "学生管理系统需求",
                "做一个学生管理系统",
                TestStackProfiles.DEFAULT_PROFILE_ID,
                "user-1",
                true
        );
    }

    @Test
    void shouldRejectInvalidRequirementEditRequest() throws Exception {
        WorkflowCommandFacade facade = mock(WorkflowCommandFacade.class);
        MockMvc mockMvc = mockMvc(new WorkflowCommandController(facade));

        mockMvc.perform(put("/api/v1/controlplane/workflows/workflow-1/requirement/current")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "docId", "req-1",
                                "title", "学生管理系统",
                                "content", "",
                                "editedByActorId", "editor-1"
                        ))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors.content").exists());
    }

    @Test
    void shouldMapRequirementConfirmConflict() throws Exception {
        WorkflowCommandFacade facade = mock(WorkflowCommandFacade.class);
        when(facade.confirmRequirement("workflow-1", "req-1", 2, "reviewer-1"))
                .thenThrow(new IllegalStateException("requirement version mismatch"));
        MockMvc mockMvc = mockMvc(new WorkflowCommandController(facade));

        mockMvc.perform(post("/api/v1/controlplane/workflows/workflow-1/requirement/confirm")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "docId", "req-1",
                                "version", 2,
                                "confirmedByActorId", "reviewer-1"
                        ))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("CONFLICT"))
                .andExpect(jsonPath("$.message").value("requirement version mismatch"));
    }

    private MockMvc mockMvc(WorkflowCommandController controller) {
        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
        return MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new ControlplaneExceptionHandler())
                .setValidator(validator)
                .build();
    }

    private WorkflowCommandResult result(String workflowRunId) {
        return new WorkflowCommandResult(
                workflowRunId,
                WorkflowRunStatus.WAITING_ON_HUMAN,
                "req-1",
                RequirementStatus.IN_REVIEW,
                1,
                null,
                1,
                1,
                0,
                Map.of("READY", 2L)
        );
    }
}
