package com.agentx.platform;

import com.agentx.platform.controlplane.api.ControlplaneExceptionHandler;
import com.agentx.platform.controlplane.api.agent.AgentCommandController;
import com.agentx.platform.controlplane.application.AgentDefinitionCommandResult;
import com.agentx.platform.controlplane.application.AgentDefinitionCommandService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AgentCommandControllerTests {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void shouldCreateAgentViaHttp() throws Exception {
        AgentDefinitionCommandService service = mock(AgentDefinitionCommandService.class);
        when(service.createAgent(
                "coding-agent-student",
                "Student Coding Agent",
                "handle java backend tasks",
                "docker",
                "deepseek-chat",
                4,
                true,
                false,
                true,
                true,
                List.of("cap-java-backend-coding", "cap-verify")
        )).thenReturn(result(true, List.of("cap-java-backend-coding", "cap-verify")));
        MockMvc mockMvc = mockMvc(new AgentCommandController(service));

        mockMvc.perform(post("/api/v1/controlplane/agents")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.ofEntries(
                                Map.entry("agentId", "coding-agent-student"),
                                Map.entry("displayName", "Student Coding Agent"),
                                Map.entry("purpose", "handle java backend tasks"),
                                Map.entry("runtimeType", "docker"),
                                Map.entry("model", "deepseek-chat"),
                                Map.entry("maxParallelRuns", 4),
                                Map.entry("architectSuggested", true),
                                Map.entry("autoPoolEligible", false),
                                Map.entry("manualRegistrationAllowed", true),
                                Map.entry("enabled", true),
                                Map.entry("capabilityPackIds", List.of("cap-java-backend-coding", "cap-verify"))
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.agentId").value("coding-agent-student"))
                .andExpect(jsonPath("$.capabilityPackIds[0]").value("cap-java-backend-coding"));

        verify(service).createAgent(
                "coding-agent-student",
                "Student Coding Agent",
                "handle java backend tasks",
                "docker",
                "deepseek-chat",
                4,
                true,
                false,
                true,
                true,
                List.of("cap-java-backend-coding", "cap-verify")
        );
    }

    @Test
    void shouldReplaceCapabilityBindingsViaHttp() throws Exception {
        AgentDefinitionCommandService service = mock(AgentDefinitionCommandService.class);
        when(service.replaceCapabilityBindings("coding-agent-student", List.of("cap-api-test", "cap-verify")))
                .thenReturn(result(true, List.of("cap-api-test", "cap-verify")));
        MockMvc mockMvc = mockMvc(new AgentCommandController(service));

        mockMvc.perform(put("/api/v1/controlplane/agents/coding-agent-student/capability-packs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "capabilityPackIds", List.of("cap-api-test", "cap-verify")
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.capabilityPackIds[0]").value("cap-api-test"))
                .andExpect(jsonPath("$.capabilityPackIds[1]").value("cap-verify"));

        verify(service).replaceCapabilityBindings("coding-agent-student", List.of("cap-api-test", "cap-verify"));
    }

    @Test
    void shouldEnableAndDisableAgentViaHttp() throws Exception {
        AgentDefinitionCommandService service = mock(AgentDefinitionCommandService.class);
        when(service.setAgentEnabled("coding-agent-student", true)).thenReturn(result(true, List.of("cap-java-backend-coding")));
        when(service.setAgentEnabled("coding-agent-student", false)).thenReturn(result(false, List.of("cap-java-backend-coding")));
        MockMvc mockMvc = mockMvc(new AgentCommandController(service));

        mockMvc.perform(patch("/api/v1/controlplane/agents/coding-agent-student/enable"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(true));

        mockMvc.perform(patch("/api/v1/controlplane/agents/coding-agent-student/disable"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(false));
    }

    @Test
    void shouldRejectEmptyCapabilityPackReplacement() throws Exception {
        AgentDefinitionCommandService service = mock(AgentDefinitionCommandService.class);
        MockMvc mockMvc = mockMvc(new AgentCommandController(service));

        mockMvc.perform(put("/api/v1/controlplane/agents/coding-agent-student/capability-packs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "capabilityPackIds", List.of()
                        ))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors.capabilityPackIds").exists());
    }

    @Test
    void shouldMapMissingCapabilityPackToNotFound() throws Exception {
        AgentDefinitionCommandService service = mock(AgentDefinitionCommandService.class);
        when(service.replaceCapabilityBindings("coding-agent-student", List.of("cap-missing")))
                .thenThrow(new NoSuchElementException("capability pack not found: cap-missing"));
        MockMvc mockMvc = mockMvc(new AgentCommandController(service));

        mockMvc.perform(put("/api/v1/controlplane/agents/coding-agent-student/capability-packs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "capabilityPackIds", List.of("cap-missing")
                        ))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("capability pack not found: cap-missing"));
    }

    private MockMvc mockMvc(AgentCommandController controller) {
        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
        return MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new ControlplaneExceptionHandler())
                .setValidator(validator)
                .build();
    }

    private AgentDefinitionCommandResult result(boolean enabled, List<String> capabilityPackIds) {
        return new AgentDefinitionCommandResult(
                "coding-agent-student",
                "Student Coding Agent",
                "docker",
                "deepseek-chat",
                enabled,
                false,
                false,
                true,
                capabilityPackIds
        );
    }
}
