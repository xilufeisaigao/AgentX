package com.agentx.agentxbackend.requirement.api;

import com.agentx.agentxbackend.requirement.application.port.in.RequirementDocUseCase;
import com.agentx.agentxbackend.requirement.domain.model.RequirementDoc;
import com.agentx.agentxbackend.requirement.domain.model.RequirementDocStatus;
import com.agentx.agentxbackend.requirement.domain.model.RequirementDocVersion;
import com.agentx.agentxbackend.requirement.domain.policy.RequirementDocContentPolicy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.NoSuchElementException;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class RequirementDocControllerMockTest {

    @Mock
    private RequirementDocUseCase useCase;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        RequirementDocController controller = new RequirementDocController(useCase);
        this.mockMvc = MockMvcBuilders.standaloneSetup(controller)
            .setControllerAdvice(new RequirementDocExceptionHandler())
            .build();
    }

    @Test
    void createRequirementDocShouldReturnCreatedResponse() throws Exception {
        RequirementDoc doc = new RequirementDoc(
            "REQ-101",
            "SES-101",
            0,
            null,
            RequirementDocStatus.DRAFT,
            "Requirement title",
            Instant.parse("2026-02-20T10:00:00Z"),
            Instant.parse("2026-02-20T10:00:00Z")
        );
        when(useCase.createRequirementDoc("SES-101", "Requirement title")).thenReturn(doc);

        mockMvc.perform(post("/api/v0/sessions/SES-101/requirement-docs")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"Requirement title\"}"))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.doc_id").value("REQ-101"))
            .andExpect(jsonPath("$.session_id").value("SES-101"))
            .andExpect(jsonPath("$.current_version").value(0))
            .andExpect(jsonPath("$.status").value("DRAFT"));
    }

    @Test
    void createVersionShouldReturnCreatedResponse() throws Exception {
        String content = validMarkdown("updated markdown");
        RequirementDocVersion version = new RequirementDocVersion(
            "REQ-102",
            2,
            content,
            "user",
            Instant.parse("2026-02-20T10:01:00Z")
        );
        when(useCase.createVersion("REQ-102", content, "user")).thenReturn(version);

        mockMvc.perform(post("/api/v0/requirement-docs/REQ-102/versions")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"content\":\"" + toJsonString(content) + "\",\"created_by_role\":\"user\"}"))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.doc_id").value("REQ-102"))
            .andExpect(jsonPath("$.version").value(2))
            .andExpect(jsonPath("$.created_by_role").value("user"));
    }

    @Test
    void confirmShouldReturnUpdatedDoc() throws Exception {
        RequirementDoc doc = new RequirementDoc(
            "REQ-103",
            "SES-103",
            3,
            3,
            RequirementDocStatus.CONFIRMED,
            "Confirmed doc",
            Instant.parse("2026-02-20T10:00:00Z"),
            Instant.parse("2026-02-20T11:00:00Z")
        );
        when(useCase.confirm("REQ-103")).thenReturn(doc);

        mockMvc.perform(post("/api/v0/requirement-docs/REQ-103/confirm"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.doc_id").value("REQ-103"))
            .andExpect(jsonPath("$.confirmed_version").value(3))
            .andExpect(jsonPath("$.status").value("CONFIRMED"));
    }

    @Test
    void overwriteCurrentContentShouldCreateNextVersionAsUser() throws Exception {
        String content = validMarkdown("frontend overwrite");
        RequirementDocVersion version = new RequirementDocVersion(
            "REQ-106",
            4,
            content,
            "user",
            Instant.parse("2026-02-20T10:02:00Z")
        );
        when(useCase.createVersion("REQ-106", content, "user")).thenReturn(version);

        mockMvc.perform(put("/api/v0/requirement-docs/REQ-106/content")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"content\":\"" + toJsonString(content) + "\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.doc_id").value("REQ-106"))
            .andExpect(jsonPath("$.version").value(4))
            .andExpect(jsonPath("$.created_by_role").value("user"));

        verify(useCase).createVersion("REQ-106", content, "user");
    }

    @Test
    void createVersionShouldReturnNotFoundWhenDocMissing() throws Exception {
        String content = validMarkdown("x");
        when(useCase.createVersion(eq("REQ-404"), eq(content), eq("user")))
            .thenThrow(new NoSuchElementException("Requirement doc not found"));

        mockMvc.perform(post("/api/v0/requirement-docs/REQ-404/versions")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"content\":\"" + toJsonString(content) + "\",\"created_by_role\":\"user\"}"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void createRequirementDocShouldReturnBadRequestForIllegalArguments() throws Exception {
        when(useCase.createRequirementDoc("SES-104", ""))
            .thenThrow(new IllegalArgumentException("title must not be blank"));

        mockMvc.perform(post("/api/v0/sessions/SES-104/requirement-docs")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("BAD_REQUEST"))
            .andExpect(jsonPath("$.message").value("title must not be blank"));
    }

    @Test
    void confirmShouldReturnConflictForInvalidState() throws Exception {
        when(useCase.confirm("REQ-105"))
            .thenThrow(new IllegalStateException("Cannot confirm requirement doc in DRAFT status"));

        mockMvc.perform(post("/api/v0/requirement-docs/REQ-105/confirm"))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("CONFLICT"));
    }

    private static String validMarkdown(String marker) {
        return RequirementDocContentPolicy.buildTemplate(
            "Requirement title",
            "Summary " + marker,
            "User input " + marker,
            "change " + marker
        );
    }

    private static String toJsonString(String value) {
        return value
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\r", "\\r")
            .replace("\n", "\\n");
    }
}
