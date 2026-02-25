package com.agentx.agentxbackend.planning.api;

import com.agentx.agentxbackend.planning.application.port.in.PlanningCommandUseCase;
import com.agentx.agentxbackend.planning.domain.model.TaskStatus;
import com.agentx.agentxbackend.planning.domain.model.TaskTemplateId;
import com.agentx.agentxbackend.planning.domain.model.WorkModule;
import com.agentx.agentxbackend.planning.domain.model.WorkTaskDependency;
import com.agentx.agentxbackend.planning.domain.model.WorkTask;
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

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class PlanningControllerMockTest {

    @Mock
    private PlanningCommandUseCase useCase;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        PlanningController controller = new PlanningController(useCase);
        this.mockMvc = MockMvcBuilders.standaloneSetup(controller)
            .setControllerAdvice(new PlanningExceptionHandler())
            .build();
    }

    @Test
    void createModuleShouldReturnModuleResponse() throws Exception {
        WorkModule module = new WorkModule(
            "MOD-1",
            "SES-1",
            "order-center",
            "Order center module",
            Instant.parse("2026-02-22T00:00:00Z"),
            Instant.parse("2026-02-22T00:00:00Z")
        );
        when(useCase.createModule("SES-1", "order-center", "Order center module"))
            .thenReturn(module);

        mockMvc.perform(post("/api/v0/sessions/SES-1/modules")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "name":"order-center",
                      "description":"Order center module"
                    }
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.module_id").value("MOD-1"))
            .andExpect(jsonPath("$.session_id").value("SES-1"))
            .andExpect(jsonPath("$.name").value("order-center"));
    }

    @Test
    void createTaskShouldReturnTaskResponse() throws Exception {
        WorkTask task = new WorkTask(
            "TASK-1",
            "MOD-1",
            "Implement create order API",
            TaskTemplateId.TMPL_IMPL_V0,
            TaskStatus.WAITING_WORKER,
            "[\"TP-JAVA-21\",\"TP-MAVEN-3\"]",
            null,
            "architect_agent",
            Instant.parse("2026-02-22T00:00:00Z"),
            Instant.parse("2026-02-22T00:00:00Z")
        );
        when(useCase.createTask(
            eq("MOD-1"),
            eq("Implement create order API"),
            eq("tmpl.impl.v0"),
            eq("[\"TP-JAVA-21\", \"TP-MAVEN-3\"]"),
            eq(List.of())
        )).thenReturn(task);

        mockMvc.perform(post("/api/v0/modules/MOD-1/tasks")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "title":"Implement create order API",
                      "task_template_id":"tmpl.impl.v0",
                      "required_toolpacks_json":"[\\"TP-JAVA-21\\", \\"TP-MAVEN-3\\"]"
                    }
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.task_id").value("TASK-1"))
            .andExpect(jsonPath("$.task_template_id").value("tmpl.impl.v0"))
            .andExpect(jsonPath("$.status").value("WAITING_WORKER"));
    }

    @Test
    void createTaskShouldReturnBadRequestWhenPayloadInvalid() throws Exception {
        when(useCase.createTask(anyString(), anyString(), anyString(), anyString(), anyList()))
            .thenThrow(new IllegalArgumentException("requiredToolpacksJson must be JSON array text"));

        mockMvc.perform(post("/api/v0/modules/MOD-404/tasks")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "title":"bad",
                      "task_template_id":"tmpl.impl.v0",
                      "required_toolpacks_json":"{}"
                    }
                    """))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("BAD_REQUEST"));
    }

    @Test
    void createTaskShouldReturnNotFoundWhenModuleMissing() throws Exception {
        when(useCase.createTask(anyString(), anyString(), anyString(), anyString(), anyList()))
            .thenThrow(new NoSuchElementException("Work module not found: MOD-404"));

        mockMvc.perform(post("/api/v0/modules/MOD-404/tasks")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "title":"task",
                      "task_template_id":"tmpl.impl.v0",
                      "required_toolpacks_json":"[]"
                    }
                    """))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void addTaskDependencyShouldReturnDependencyResponse() throws Exception {
        WorkTaskDependency dependency = new WorkTaskDependency(
            "TASK-2",
            "TASK-1",
            TaskStatus.DONE,
            Instant.parse("2026-02-22T01:00:00Z")
        );
        when(useCase.addTaskDependency("TASK-2", "TASK-1", "DONE")).thenReturn(dependency);

        mockMvc.perform(post("/api/v0/tasks/TASK-2/dependencies")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "depends_on_task_id":"TASK-1",
                      "required_upstream_status":"DONE"
                    }
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.task_id").value("TASK-2"))
            .andExpect(jsonPath("$.depends_on_task_id").value("TASK-1"))
            .andExpect(jsonPath("$.required_upstream_status").value("DONE"));
    }
}
