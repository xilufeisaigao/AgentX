package com.agentx.agentxbackend.planning.api;

import com.agentx.agentxbackend.planning.application.port.in.PlanningCommandUseCase;
import com.agentx.agentxbackend.planning.domain.model.WorkModule;
import com.agentx.agentxbackend.planning.domain.model.WorkTaskDependency;
import com.agentx.agentxbackend.planning.domain.model.WorkTask;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;

@RestController
public class PlanningController {

    private final PlanningCommandUseCase useCase;

    public PlanningController(PlanningCommandUseCase useCase) {
        this.useCase = useCase;
    }

    @PostMapping("/api/v0/sessions/{sessionId}/modules")
    public ResponseEntity<WorkModuleResponse> createModule(
        @PathVariable String sessionId,
        @RequestBody CreateModuleRequest request
    ) {
        WorkModule module = useCase.createModule(
            sessionId,
            request.name(),
            request.description()
        );
        return ResponseEntity.ok(WorkModuleResponse.from(module));
    }

    @PostMapping("/api/v0/modules/{moduleId}/tasks")
    public ResponseEntity<WorkTaskResponse> createTask(
        @PathVariable String moduleId,
        @RequestBody CreateTaskRequest request
    ) {
        List<String> dependsOnTaskIds = request.dependsOnTaskIds() == null ? List.of() : request.dependsOnTaskIds();
        WorkTask task = useCase.createTask(
            moduleId,
            request.title(),
            request.taskTemplateId(),
            request.requiredToolpacksJson(),
            dependsOnTaskIds
        );
        return ResponseEntity.ok(WorkTaskResponse.from(task));
    }

    @PostMapping("/api/v0/tasks/{taskId}/dependencies")
    public ResponseEntity<TaskDependencyResponse> addTaskDependency(
        @PathVariable String taskId,
        @RequestBody AddTaskDependencyRequest request
    ) {
        WorkTaskDependency dependency = useCase.addTaskDependency(
            taskId,
            request.dependsOnTaskId(),
            request.requiredUpstreamStatus()
        );
        return ResponseEntity.ok(TaskDependencyResponse.from(dependency));
    }

    public record CreateModuleRequest(String name, String description) {
    }

    public record CreateTaskRequest(
        String title,
        @JsonProperty("task_template_id") String taskTemplateId,
        @JsonProperty("required_toolpacks_json") String requiredToolpacksJson,
        @JsonProperty("depends_on_task_ids") List<String> dependsOnTaskIds
    ) {
    }

    public record AddTaskDependencyRequest(
        @JsonProperty("depends_on_task_id") String dependsOnTaskId,
        @JsonProperty("required_upstream_status") String requiredUpstreamStatus
    ) {
    }

    public record WorkModuleResponse(
        @JsonProperty("module_id") String moduleId,
        @JsonProperty("session_id") String sessionId,
        String name,
        String description,
        @JsonProperty("created_at") Instant createdAt,
        @JsonProperty("updated_at") Instant updatedAt
    ) {
        static WorkModuleResponse from(WorkModule module) {
            return new WorkModuleResponse(
                module.moduleId(),
                module.sessionId(),
                module.name(),
                module.description(),
                module.createdAt(),
                module.updatedAt()
            );
        }
    }

    public record WorkTaskResponse(
        @JsonProperty("task_id") String taskId,
        @JsonProperty("module_id") String moduleId,
        String title,
        @JsonProperty("task_template_id") String taskTemplateId,
        String status,
        @JsonProperty("required_toolpacks_json") String requiredToolpacksJson,
        @JsonProperty("active_run_id") String activeRunId,
        @JsonProperty("created_by_role") String createdByRole,
        @JsonProperty("created_at") Instant createdAt,
        @JsonProperty("updated_at") Instant updatedAt
    ) {
        static WorkTaskResponse from(WorkTask task) {
            return new WorkTaskResponse(
                task.taskId(),
                task.moduleId(),
                task.title(),
                task.taskTemplateId().value(),
                task.status().name(),
                task.requiredToolpacksJson(),
                task.activeRunId(),
                task.createdByRole(),
                task.createdAt(),
                task.updatedAt()
            );
        }
    }

    public record TaskDependencyResponse(
        @JsonProperty("task_id") String taskId,
        @JsonProperty("depends_on_task_id") String dependsOnTaskId,
        @JsonProperty("required_upstream_status") String requiredUpstreamStatus,
        @JsonProperty("created_at") Instant createdAt
    ) {
        static TaskDependencyResponse from(WorkTaskDependency dependency) {
            return new TaskDependencyResponse(
                dependency.taskId(),
                dependency.dependsOnTaskId(),
                dependency.requiredUpstreamStatus().name(),
                dependency.createdAt()
            );
        }
    }
}
