package com.agentx.agentxbackend.execution.api;

import com.agentx.agentxbackend.execution.application.PreconditionFailedException;
import com.agentx.agentxbackend.execution.application.port.in.RunCommandUseCase;
import com.agentx.agentxbackend.execution.domain.model.GitAlloc;
import com.agentx.agentxbackend.execution.domain.model.RunKind;
import com.agentx.agentxbackend.execution.domain.model.RunStatus;
import com.agentx.agentxbackend.execution.domain.model.TaskContext;
import com.agentx.agentxbackend.execution.domain.model.TaskPackage;
import com.agentx.agentxbackend.execution.domain.model.TaskRun;
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
import java.util.Optional;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class WorkerRunControllerMockTest {

    @Mock
    private RunCommandUseCase useCase;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        WorkerRunController controller = new WorkerRunController(useCase);
        this.mockMvc = MockMvcBuilders.standaloneSetup(controller)
            .setControllerAdvice(new WorkerRunExceptionHandler())
            .build();
    }

    @Test
    void claimShouldReturnNoContentWhenNoTask() throws Exception {
        when(useCase.claimTask("WRK-1")).thenReturn(Optional.empty());

        mockMvc.perform(post("/api/v0/workers/WRK-1/claim"))
            .andExpect(status().isNoContent());
    }

    @Test
    void claimShouldReturnTaskPackageWhenClaimed() throws Exception {
        TaskPackage taskPackage = new TaskPackage(
            "RUN-1",
            "TASK-1",
            "Implement worker claim response",
            "MOD-1",
            "CTXS-1",
            RunKind.IMPL,
            "tmpl.impl.v0",
            List.of("TP-JAVA-21", "TP-MAVEN-3"),
            "file:.agentx/task_skill.md",
            new TaskContext("task:TASK-1", List.of(), List.of(), "git:BASELINE"),
            List.of("./"),
            List.of("./"),
            List.of(),
            List.of("rule-1"),
            List.of("work_report"),
            new GitAlloc("BASELINE", "run/RUN-1", "worktrees/TASK-1/RUN-1")
        );
        when(useCase.claimTask("WRK-1")).thenReturn(Optional.of(taskPackage));

        mockMvc.perform(post("/api/v0/workers/WRK-1/claim"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.run_id").value("RUN-1"))
            .andExpect(jsonPath("$.task_id").value("TASK-1"))
            .andExpect(jsonPath("$.task_title").value("Implement worker claim response"))
            .andExpect(jsonPath("$.context_snapshot_id").value("CTXS-1"));
    }

    @Test
    void heartbeatShouldReturnRun() throws Exception {
        TaskRun run = new TaskRun(
            "RUN-1",
            "TASK-1",
            "WRK-1",
            RunStatus.RUNNING,
            RunKind.IMPL,
            "CTXS-1",
            Instant.parse("2026-02-22T00:10:00Z"),
            Instant.parse("2026-02-22T00:05:00Z"),
            Instant.parse("2026-02-22T00:00:00Z"),
            null,
            "file:.agentx/task_skill.md",
            "[\"TP-JAVA-21\"]",
            "BASELINE",
            "run/RUN-1",
            "worktrees/TASK-1/RUN-1",
            Instant.parse("2026-02-22T00:00:00Z"),
            Instant.parse("2026-02-22T00:05:00Z")
        );
        when(useCase.heartbeat("RUN-1")).thenReturn(run);

        mockMvc.perform(post("/api/v0/runs/RUN-1/heartbeat")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.run_id").value("RUN-1"))
            .andExpect(jsonPath("$.status").value("RUNNING"));
    }

    @Test
    void claimShouldReturnPreconditionFailed() throws Exception {
        when(useCase.claimTask("WRK-1")).thenThrow(new PreconditionFailedException("snapshot missing"));

        mockMvc.perform(post("/api/v0/workers/WRK-1/claim"))
            .andExpect(status().isPreconditionFailed())
            .andExpect(jsonPath("$.code").value("PRECONDITION_FAILED"));
    }
}
