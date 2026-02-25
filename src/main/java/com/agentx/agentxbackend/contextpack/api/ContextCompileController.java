package com.agentx.agentxbackend.contextpack.api;

import com.agentx.agentxbackend.contextpack.application.port.in.ContextCompileUseCase;
import com.agentx.agentxbackend.contextpack.domain.model.RoleContextPack;
import com.agentx.agentxbackend.contextpack.domain.model.TaskContextPack;
import com.agentx.agentxbackend.contextpack.domain.model.TaskContextSnapshot;
import com.agentx.agentxbackend.contextpack.domain.model.TaskContextSnapshotStatusView;
import com.agentx.agentxbackend.contextpack.domain.model.TaskSkill;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;

@RestController
public class ContextCompileController {

    private final ContextCompileUseCase useCase;

    public ContextCompileController(ContextCompileUseCase useCase) {
        this.useCase = useCase;
    }

    @PostMapping("/api/v0/context/role-pack:compile")
    public ResponseEntity<RoleContextPackResponse> compileRolePack(
        @RequestBody CompileRolePackRequest request
    ) {
        RoleContextPack pack = useCase.compileRolePack(request.sessionId(), request.role());
        return ResponseEntity.ok(RoleContextPackResponse.from(pack));
    }

    @PostMapping("/api/v0/context/task-context-pack:compile")
    public ResponseEntity<TaskContextPackResponse> compileTaskContextPack(
        @RequestBody CompileTaskContextPackRequest request
    ) {
        TaskContextPack pack = useCase.compileTaskContextPack(request.taskId(), request.runKind());
        return ResponseEntity.ok(TaskContextPackResponse.from(pack));
    }

    @PostMapping("/api/v0/context/task-skill:compile")
    public ResponseEntity<TaskSkillResponse> compileTaskSkill(
        @RequestBody CompileTaskSkillRequest request
    ) {
        TaskSkill skill = useCase.compileTaskSkill(request.taskId());
        return ResponseEntity.ok(TaskSkillResponse.from(skill));
    }

    @GetMapping("/api/v0/tasks/{taskId}/context-status")
    public ResponseEntity<ContextSnapshotStatusResponse> getTaskContextStatus(
        @PathVariable String taskId,
        @RequestParam(required = false) Integer limit
    ) {
        TaskContextSnapshotStatusView view = useCase.getTaskContextStatus(taskId, limit == null ? 10 : limit);
        return ResponseEntity.ok(ContextSnapshotStatusResponse.from(view));
    }

    public record CompileRolePackRequest(
        @JsonProperty("session_id") String sessionId,
        String role
    ) {
    }

    public record CompileTaskContextPackRequest(
        @JsonProperty("task_id") String taskId,
        @JsonProperty("run_kind") String runKind
    ) {
    }

    public record CompileTaskSkillRequest(
        @JsonProperty("task_id") String taskId
    ) {
    }

    public record RoleContextPackResponse(
        @JsonProperty("pack_id") String packId,
        @JsonProperty("session_id") String sessionId,
        String role,
        @JsonProperty("generated_at") Instant generatedAt,
        @JsonProperty("source_refs") List<String> sourceRefs,
        SummaryResponse summary,
        @JsonProperty("next_actions") List<String> nextActions
    ) {
        static RoleContextPackResponse from(RoleContextPack pack) {
            return new RoleContextPackResponse(
                pack.packId(),
                pack.sessionId(),
                pack.role(),
                pack.generatedAt(),
                pack.sourceRefs(),
                SummaryResponse.from(pack.summary()),
                pack.nextActions()
            );
        }
    }

    public record SummaryResponse(
        String goal,
        @JsonProperty("hard_constraints") List<String> hardConstraints,
        @JsonProperty("current_state") List<String> currentState,
        @JsonProperty("open_questions") List<String> openQuestions
    ) {
        static SummaryResponse from(RoleContextPack.Summary summary) {
            return new SummaryResponse(
                summary.goal(),
                summary.hardConstraints(),
                summary.currentState(),
                summary.openQuestions()
            );
        }
    }

    public record TaskContextPackResponse(
        @JsonProperty("snapshot_id") String snapshotId,
        @JsonProperty("task_id") String taskId,
        @JsonProperty("run_kind") String runKind,
        @JsonProperty("requirement_ref") String requirementRef,
        @JsonProperty("architecture_refs") List<String> architectureRefs,
        @JsonProperty("module_ref") String moduleRef,
        @JsonProperty("prior_run_refs") List<String> priorRunRefs,
        @JsonProperty("repo_baseline_ref") String repoBaselineRef,
        @JsonProperty("decision_refs") List<String> decisionRefs
    ) {
        static TaskContextPackResponse from(TaskContextPack pack) {
            return new TaskContextPackResponse(
                pack.snapshotId(),
                pack.taskId(),
                pack.runKind(),
                pack.requirementRef(),
                pack.architectureRefs(),
                pack.moduleRef(),
                pack.priorRunRefs(),
                pack.repoBaselineRef(),
                pack.decisionRefs()
            );
        }
    }

    public record TaskSkillResponse(
        @JsonProperty("snapshot_id") String snapshotId,
        @JsonProperty("skill_id") String skillId,
        @JsonProperty("task_id") String taskId,
        @JsonProperty("generated_at") Instant generatedAt,
        @JsonProperty("source_fragments") List<String> sourceFragments,
        @JsonProperty("toolpack_assumptions") List<String> toolpackAssumptions,
        List<String> conventions,
        @JsonProperty("recommended_commands") List<String> recommendedCommands,
        List<String> pitfalls,
        @JsonProperty("stop_rules") List<String> stopRules,
        @JsonProperty("expected_outputs") List<String> expectedOutputs
    ) {
        static TaskSkillResponse from(TaskSkill skill) {
            return new TaskSkillResponse(
                skill.snapshotId(),
                skill.skillId(),
                skill.taskId(),
                skill.generatedAt(),
                skill.sourceFragments(),
                skill.toolpackAssumptions(),
                skill.conventions(),
                skill.recommendedCommands(),
                skill.pitfalls(),
                skill.stopRules(),
                skill.expectedOutputs()
            );
        }
    }

    public record ContextSnapshotStatusResponse(
        @JsonProperty("task_id") String taskId,
        SnapshotResponse latest,
        List<SnapshotResponse> snapshots
    ) {
        static ContextSnapshotStatusResponse from(TaskContextSnapshotStatusView view) {
            return new ContextSnapshotStatusResponse(
                view.taskId(),
                SnapshotResponse.fromNullable(view.latest()),
                view.snapshots().stream().map(SnapshotResponse::from).toList()
            );
        }
    }

    public record SnapshotResponse(
        @JsonProperty("snapshot_id") String snapshotId,
        @JsonProperty("task_id") String taskId,
        @JsonProperty("run_kind") String runKind,
        String status,
        @JsonProperty("trigger_type") String triggerType,
        @JsonProperty("source_fingerprint") String sourceFingerprint,
        @JsonProperty("task_context_ref") String taskContextRef,
        @JsonProperty("task_skill_ref") String taskSkillRef,
        @JsonProperty("error_code") String errorCode,
        @JsonProperty("error_message") String errorMessage,
        @JsonProperty("compiled_at") Instant compiledAt,
        @JsonProperty("retained_until") Instant retainedUntil,
        @JsonProperty("created_at") Instant createdAt,
        @JsonProperty("updated_at") Instant updatedAt
    ) {
        static SnapshotResponse from(TaskContextSnapshot snapshot) {
            return new SnapshotResponse(
                snapshot.snapshotId(),
                snapshot.taskId(),
                snapshot.runKind(),
                snapshot.status().name(),
                snapshot.triggerType(),
                snapshot.sourceFingerprint(),
                snapshot.taskContextRef(),
                snapshot.taskSkillRef(),
                snapshot.errorCode(),
                snapshot.errorMessage(),
                snapshot.compiledAt(),
                snapshot.retainedUntil(),
                snapshot.createdAt(),
                snapshot.updatedAt()
            );
        }

        static SnapshotResponse fromNullable(TaskContextSnapshot snapshot) {
            return snapshot == null ? null : from(snapshot);
        }
    }
}
