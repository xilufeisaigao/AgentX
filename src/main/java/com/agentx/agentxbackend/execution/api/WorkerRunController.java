package com.agentx.agentxbackend.execution.api;

import com.agentx.agentxbackend.execution.application.port.in.RunCommandUseCase;
import com.agentx.agentxbackend.execution.domain.model.GitAlloc;
import com.agentx.agentxbackend.execution.domain.model.RunFinishedPayload;
import com.agentx.agentxbackend.execution.domain.model.TaskContext;
import com.agentx.agentxbackend.execution.domain.model.TaskPackage;
import com.agentx.agentxbackend.execution.domain.model.TaskRun;
import com.agentx.agentxbackend.execution.domain.model.TaskRunEvent;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@RestController
public class WorkerRunController {

    private final RunCommandUseCase useCase;

    public WorkerRunController(RunCommandUseCase useCase) {
        this.useCase = useCase;
    }

    @PostMapping("/api/v0/workers/{workerId}/claim")
    public ResponseEntity<TaskPackageResponse> claim(@PathVariable String workerId) {
        Optional<TaskPackage> claimed = useCase.claimTask(workerId);
        if (claimed.isEmpty()) {
            return ResponseEntity.noContent().build();
        }
        return ResponseEntity.ok(TaskPackageResponse.from(claimed.get()));
    }

    @PostMapping("/api/v0/runs/{runId}/heartbeat")
    public ResponseEntity<TaskRunResponse> heartbeat(
        @PathVariable String runId,
        @RequestBody(required = false) HeartbeatRequest request
    ) {
        TaskRun run = useCase.heartbeat(runId);
        return ResponseEntity.ok(TaskRunResponse.from(run));
    }

    @PostMapping("/api/v0/runs/{runId}/events")
    public ResponseEntity<TaskRunEventResponse> appendEvent(
        @PathVariable String runId,
        @RequestBody AppendTaskRunEventRequest request
    ) {
        TaskRunEvent event = useCase.appendEvent(
            runId,
            request.eventType(),
            request.body(),
            request.dataJson()
        );
        return ResponseEntity.ok(TaskRunEventResponse.from(event));
    }

    @PostMapping("/api/v0/runs/{runId}/finish")
    public ResponseEntity<TaskRunResponse> finish(
        @PathVariable String runId,
        @RequestBody FinishRunRequest request
    ) {
        TaskRun run = useCase.finishRun(
            runId,
            new RunFinishedPayload(
                request.resultStatus(),
                request.workReport(),
                request.deliveryCommit(),
                request.artifactRefsJson()
            )
        );
        return ResponseEntity.ok(TaskRunResponse.from(run));
    }

    public record HeartbeatRequest() {
    }

    public record AppendTaskRunEventRequest(
        @JsonProperty("event_type") String eventType,
        String body,
        @JsonProperty("data_json") String dataJson
    ) {
    }

    public record FinishRunRequest(
        @JsonProperty("result_status") String resultStatus,
        @JsonProperty("work_report") String workReport,
        @JsonProperty("delivery_commit") String deliveryCommit,
        @JsonProperty("artifact_refs_json") String artifactRefsJson
    ) {
    }

    public record TaskRunResponse(
        @JsonProperty("run_id") String runId,
        @JsonProperty("task_id") String taskId,
        @JsonProperty("worker_id") String workerId,
        String status,
        @JsonProperty("run_kind") String runKind,
        @JsonProperty("context_snapshot_id") String contextSnapshotId,
        @JsonProperty("lease_until") Instant leaseUntil,
        @JsonProperty("last_heartbeat_at") Instant lastHeartbeatAt,
        @JsonProperty("started_at") Instant startedAt,
        @JsonProperty("finished_at") Instant finishedAt,
        @JsonProperty("task_skill_ref") String taskSkillRef,
        @JsonProperty("toolpacks_snapshot_json") String toolpacksSnapshotJson,
        @JsonProperty("base_commit") String baseCommit,
        @JsonProperty("branch_name") String branchName,
        @JsonProperty("worktree_path") String worktreePath,
        @JsonProperty("created_at") Instant createdAt,
        @JsonProperty("updated_at") Instant updatedAt
    ) {
        static TaskRunResponse from(TaskRun run) {
            return new TaskRunResponse(
                run.runId(),
                run.taskId(),
                run.workerId(),
                run.status().name(),
                run.runKind().name(),
                run.contextSnapshotId(),
                run.leaseUntil(),
                run.lastHeartbeatAt(),
                run.startedAt(),
                run.finishedAt(),
                run.taskSkillRef(),
                run.toolpacksSnapshotJson(),
                run.baseCommit(),
                run.branchName(),
                run.worktreePath(),
                run.createdAt(),
                run.updatedAt()
            );
        }
    }

    public record TaskRunEventResponse(
        @JsonProperty("event_id") String eventId,
        @JsonProperty("run_id") String runId,
        @JsonProperty("event_type") String eventType,
        String body,
        @JsonProperty("data_json") String dataJson,
        @JsonProperty("created_at") Instant createdAt
    ) {
        static TaskRunEventResponse from(TaskRunEvent event) {
            return new TaskRunEventResponse(
                event.eventId(),
                event.runId(),
                event.eventType().name(),
                event.body(),
                event.dataJson(),
                event.createdAt()
            );
        }
    }

    public record TaskPackageResponse(
        @JsonProperty("run_id") String runId,
        @JsonProperty("task_id") String taskId,
        @JsonProperty("task_title") String taskTitle,
        @JsonProperty("module_id") String moduleId,
        @JsonProperty("context_snapshot_id") String contextSnapshotId,
        @JsonProperty("run_kind") String runKind,
        @JsonProperty("task_template_id") String taskTemplateId,
        @JsonProperty("required_toolpacks") List<String> requiredToolpacks,
        @JsonProperty("task_skill_ref") String taskSkillRef,
        @JsonProperty("task_context_ref") String taskContextRef,
        @JsonProperty("task_context") TaskContextResponse taskContext,
        @JsonProperty("read_scope") List<String> readScope,
        @JsonProperty("write_scope") List<String> writeScope,
        @JsonProperty("verify_commands") List<String> verifyCommands,
        @JsonProperty("stop_rules") List<String> stopRules,
        @JsonProperty("expected_outputs") List<String> expectedOutputs,
        GitAllocResponse git
    ) {
        static TaskPackageResponse from(TaskPackage taskPackage) {
            return new TaskPackageResponse(
                taskPackage.runId(),
                taskPackage.taskId(),
                taskPackage.taskTitle(),
                taskPackage.moduleId(),
                taskPackage.contextSnapshotId(),
                taskPackage.runKind().name(),
                taskPackage.taskTemplateId(),
                taskPackage.requiredToolpacks(),
                taskPackage.taskSkillRef(),
                taskPackage.taskContextRef(),
                TaskContextResponse.from(taskPackage.taskContext()),
                taskPackage.readScope(),
                taskPackage.writeScope(),
                taskPackage.verifyCommands(),
                taskPackage.stopRules(),
                taskPackage.expectedOutputs(),
                GitAllocResponse.from(taskPackage.git())
            );
        }
    }

    public record TaskContextResponse(
        @JsonProperty("requirement_ref") String requirementRef,
        @JsonProperty("architecture_refs") List<String> architectureRefs,
        @JsonProperty("prior_run_refs") List<String> priorRunRefs,
        @JsonProperty("repo_baseline_ref") String repoBaselineRef
    ) {
        static TaskContextResponse from(TaskContext taskContext) {
            return new TaskContextResponse(
                taskContext.requirementRef(),
                taskContext.architectureRefs(),
                taskContext.priorRunRefs(),
                taskContext.repoBaselineRef()
            );
        }
    }

    public record GitAllocResponse(
        @JsonProperty("base_commit") String baseCommit,
        @JsonProperty("branch_name") String branchName,
        @JsonProperty("worktree_path") String worktreePath
    ) {
        static GitAllocResponse from(GitAlloc gitAlloc) {
            return new GitAllocResponse(
                gitAlloc.baseCommit(),
                gitAlloc.branchName(),
                gitAlloc.worktreePath()
            );
        }
    }
}
