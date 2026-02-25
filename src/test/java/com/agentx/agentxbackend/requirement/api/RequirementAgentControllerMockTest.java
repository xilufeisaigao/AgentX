package com.agentx.agentxbackend.requirement.api;

import com.agentx.agentxbackend.requirement.application.port.in.RequirementAgentUseCase;
import com.agentx.agentxbackend.requirement.domain.model.RequirementAgentPhase;
import com.agentx.agentxbackend.requirement.domain.model.RequirementDoc;
import com.agentx.agentxbackend.requirement.domain.model.RequirementDocStatus;
import com.agentx.agentxbackend.requirement.domain.model.RequirementDocVersion;
import com.agentx.agentxbackend.requirement.application.RequirementAgentUpstreamException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;
import java.util.NoSuchElementException;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class RequirementAgentControllerMockTest {

    @Mock
    private RequirementAgentUseCase useCase;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        RequirementAgentController controller = new RequirementAgentController(useCase);
        this.mockMvc = MockMvcBuilders.standaloneSetup(controller)
            .setControllerAdvice(new RequirementDocExceptionHandler())
            .build();
    }

    @Test
    void generateDraftShouldReturnCreatedResponse() throws Exception {
        RequirementDoc doc = new RequirementDoc(
            "REQ-201",
            "SES-201",
            1,
            null,
            RequirementDocStatus.IN_REVIEW,
            "Checkout",
            Instant.parse("2026-02-21T10:00:00Z"),
            Instant.parse("2026-02-21T10:01:00Z")
        );
        RequirementDocVersion version = new RequirementDocVersion(
            "REQ-201",
            1,
            "---\\nschema_version: req_doc_v1\\n---",
            "requirement_agent",
            Instant.parse("2026-02-21T10:01:00Z")
        );
        RequirementAgentUseCase.DraftResult result = new RequirementAgentUseCase.DraftResult(
            doc,
            version,
            version.content(),
            true,
            "mock",
            "qwen3.5-plus-2026-02-15",
            RequirementAgentPhase.DRAFT_CREATED,
            "已生成需求草稿。",
            true,
            List.of()
        );
        when(useCase.generateDraft(any())).thenReturn(result);

        mockMvc.perform(post("/api/v0/sessions/SES-201/requirement-agent/drafts")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "title":"Checkout",
                      "user_input":"Need checkout flow",
                      "persist":true
                    }
                    """))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.doc_id").value("REQ-201"))
            .andExpect(jsonPath("$.version").value(1))
            .andExpect(jsonPath("$.status").value("IN_REVIEW"))
            .andExpect(jsonPath("$.persisted").value(true))
            .andExpect(jsonPath("$.provider").value("mock"))
            .andExpect(jsonPath("$.phase").value("DRAFT_CREATED"))
            .andExpect(jsonPath("$.assistant_message").value("已生成需求草稿。"))
            .andExpect(jsonPath("$.ready_to_draft").value(true));
    }

    @Test
    void generateDraftShouldReturnOkForDiscoveryChat() throws Exception {
        RequirementAgentUseCase.DraftResult result = new RequirementAgentUseCase.DraftResult(
            null,
            null,
            null,
            false,
            "bailian",
            "qwen3.5-plus-2026-02-15",
            RequirementAgentPhase.DISCOVERY_CHAT,
            "请补充验收标准。",
            false,
            List.of("验收标准")
        );
        when(useCase.generateDraft(any())).thenReturn(result);

        mockMvc.perform(post("/api/v0/sessions/SES-201/requirement-agent/drafts")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "title":"Checkout",
                      "user_input":"你好"
                    }
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.doc_id").isEmpty())
            .andExpect(jsonPath("$.content").isEmpty())
            .andExpect(jsonPath("$.persisted").value(false))
            .andExpect(jsonPath("$.phase").value("DISCOVERY_CHAT"))
            .andExpect(jsonPath("$.assistant_message").value("请补充验收标准。"))
            .andExpect(jsonPath("$.ready_to_draft").value(false))
            .andExpect(jsonPath("$.missing_information[0]").value("验收标准"));
    }

    @Test
    void generateDraftShouldReturnNotFoundWhenDocMissing() throws Exception {
        when(useCase.generateDraft(any())).thenThrow(new NoSuchElementException("Requirement doc not found"));

        mockMvc.perform(post("/api/v0/sessions/SES-201/requirement-agent/drafts")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "doc_id":"REQ-404",
                      "user_input":"Revise"
                    }
                    """))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void generateDraftShouldReturnBadRequestWhenInputInvalid() throws Exception {
        when(useCase.generateDraft(any())).thenThrow(new IllegalArgumentException("userInput must not be blank"));

        mockMvc.perform(post("/api/v0/sessions/SES-201/requirement-agent/drafts")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "title":"Checkout",
                      "user_input":" "
                    }
                    """))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("BAD_REQUEST"));
    }

    @Test
    void generateDraftShouldReturnBadGatewayWhenUpstreamFails() throws Exception {
        when(useCase.generateDraft(any())).thenThrow(new RequirementAgentUpstreamException("Bailian API call failed"));

        mockMvc.perform(post("/api/v0/sessions/SES-201/requirement-agent/drafts")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "title":"Checkout",
                      "user_input":"确认需求"
                    }
                    """))
            .andExpect(status().isBadGateway())
            .andExpect(jsonPath("$.code").value("UPSTREAM_ERROR"));
    }
}

