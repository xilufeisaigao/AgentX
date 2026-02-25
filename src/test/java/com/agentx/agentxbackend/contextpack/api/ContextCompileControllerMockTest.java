package com.agentx.agentxbackend.contextpack.api;

import com.agentx.agentxbackend.contextpack.application.port.in.ContextCompileUseCase;
import com.agentx.agentxbackend.contextpack.domain.model.RoleContextPack;
import com.agentx.agentxbackend.contextpack.domain.model.TaskContextPack;
import com.agentx.agentxbackend.contextpack.domain.model.TaskContextSnapshot;
import com.agentx.agentxbackend.contextpack.domain.model.TaskContextSnapshotStatus;
import com.agentx.agentxbackend.contextpack.domain.model.TaskContextSnapshotStatusView;
import com.agentx.agentxbackend.contextpack.domain.model.TaskSkill;
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

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class ContextCompileControllerMockTest {

    @Mock
    private ContextCompileUseCase useCase;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        ContextCompileController controller = new ContextCompileController(useCase);
        this.mockMvc = MockMvcBuilders.standaloneSetup(controller)
            .setControllerAdvice(new ContextCompileExceptionHandler())
            .build();
    }

    @Test
    void compileRolePackShouldReturnPack() throws Exception {
        RoleContextPack rolePack = new RoleContextPack(
            "CTX-1",
            "SES-1",
            "architect_agent",
            Instant.parse("2026-02-22T12:00:00Z"),
            List.of("req:REQ-1@v1"),
            new RoleContextPack.Summary(
                "Order Center MVP",
                List.of("Latency under 300ms"),
                List.of("ARCH_REVIEW is WAITING_USER"),
                List.of("Need environment strategy decision")
            ),
            List.of("Wait for user decision on architecture strategy")
        );
        when(useCase.compileRolePack("SES-1", "architect_agent")).thenReturn(rolePack);

        mockMvc.perform(post("/api/v0/context/role-pack:compile")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "session_id":"SES-1",
                      "role":"architect_agent"
                    }
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.pack_id").value("CTX-1"))
            .andExpect(jsonPath("$.summary.goal").value("Order Center MVP"));
    }

    @Test
    void compileTaskContextPackShouldReturnSnapshotId() throws Exception {
        TaskContextPack taskContextPack = new TaskContextPack(
            "CTXS-1",
            "TASK-1",
            "IMPL",
            "req:REQ-1@v1",
            List.of("ticket:TCK-1|ARCH_REVIEW|DONE"),
            "module:MOD-1",
            List.of("run:RUN-1|SUCCEEDED"),
            "git:abc123",
            List.of("ticket:TCK-DEC-1")
        );
        when(useCase.compileTaskContextPack("TASK-1", "IMPL")).thenReturn(taskContextPack);

        mockMvc.perform(post("/api/v0/context/task-context-pack:compile")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "task_id":"TASK-1",
                      "run_kind":"IMPL"
                    }
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.snapshot_id").value("CTXS-1"))
            .andExpect(jsonPath("$.task_id").value("TASK-1"))
            .andExpect(jsonPath("$.run_kind").value("IMPL"));
    }

    @Test
    void compileTaskSkillShouldReturnSkillPayload() throws Exception {
        TaskSkill skill = new TaskSkill(
            "CTXS-1",
            "TSKILL-1",
            "TASK-1",
            Instant.parse("2026-02-22T12:01:00Z"),
            List.of("template:tmpl.impl.v0"),
            List.of("toolpack:TP-JAVA-21"),
            List.of("Keep module boundaries."),
            List.of("mvn -q test"),
            List.of("Do not bypass decision ticket flow."),
            List.of("Need CLARIFICATION when facts are missing."),
            List.of("work_report", "artifact_refs", "delivery_commit")
        );
        when(useCase.compileTaskSkill("TASK-1")).thenReturn(skill);

        mockMvc.perform(post("/api/v0/context/task-skill:compile")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "task_id":"TASK-1"
                    }
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.snapshot_id").value("CTXS-1"))
            .andExpect(jsonPath("$.skill_id").value("TSKILL-1"))
            .andExpect(jsonPath("$.task_id").value("TASK-1"));
    }

    @Test
    void getTaskContextStatusShouldReturnLatestAndList() throws Exception {
        TaskContextSnapshot latest = new TaskContextSnapshot(
            "CTXS-2",
            "TASK-1",
            "IMPL",
            TaskContextSnapshotStatus.READY,
            "MANUAL_REFRESH",
            "sha256:2",
            "file:ctx2",
            "file:skill2",
            null,
            null,
            Instant.parse("2026-02-22T12:02:00Z"),
            Instant.parse("2026-08-21T00:00:00Z"),
            Instant.parse("2026-02-22T12:01:00Z"),
            Instant.parse("2026-02-22T12:02:00Z")
        );
        TaskContextSnapshot older = new TaskContextSnapshot(
            "CTXS-1",
            "TASK-1",
            "IMPL",
            TaskContextSnapshotStatus.STALE,
            "MANUAL_REFRESH",
            "sha256:1",
            "file:ctx1",
            "file:skill1",
            null,
            null,
            Instant.parse("2026-02-22T12:00:00Z"),
            Instant.parse("2026-08-21T00:00:00Z"),
            Instant.parse("2026-02-22T11:59:00Z"),
            Instant.parse("2026-02-22T12:00:00Z")
        );
        when(useCase.getTaskContextStatus("TASK-1", 10))
            .thenReturn(new TaskContextSnapshotStatusView("TASK-1", latest, List.of(latest, older)));

        mockMvc.perform(get("/api/v0/tasks/TASK-1/context-status"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.task_id").value("TASK-1"))
            .andExpect(jsonPath("$.latest.snapshot_id").value("CTXS-2"))
            .andExpect(jsonPath("$.snapshots[0].status").value("READY"))
            .andExpect(jsonPath("$.snapshots[1].status").value("STALE"));
    }

    @Test
    void compileTaskContextPackShouldReturnBadRequestWhenUseCaseThrowsIllegalArgument() throws Exception {
        when(useCase.compileTaskContextPack("TASK-1", "BAD"))
            .thenThrow(new IllegalArgumentException("runKind must be IMPL or VERIFY"));

        mockMvc.perform(post("/api/v0/context/task-context-pack:compile")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "task_id":"TASK-1",
                      "run_kind":"BAD"
                    }
                    """))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("BAD_REQUEST"));
    }
}
