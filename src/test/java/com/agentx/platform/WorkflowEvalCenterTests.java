package com.agentx.platform;

import com.agentx.platform.domain.execution.model.CleanupStatus;
import com.agentx.platform.domain.execution.model.GitWorkspace;
import com.agentx.platform.domain.execution.model.GitWorkspaceStatus;
import com.agentx.platform.domain.execution.model.RunKind;
import com.agentx.platform.domain.execution.model.TaskRun;
import com.agentx.platform.domain.execution.model.TaskRunEvent;
import com.agentx.platform.domain.execution.model.TaskRunStatus;
import com.agentx.platform.domain.flow.model.EntryMode;
import com.agentx.platform.domain.flow.model.WorkflowNodeRun;
import com.agentx.platform.domain.flow.model.WorkflowNodeRunStatus;
import com.agentx.platform.domain.flow.model.WorkflowRun;
import com.agentx.platform.domain.flow.model.WorkflowRunStatus;
import com.agentx.platform.domain.intake.model.RequirementDoc;
import com.agentx.platform.domain.intake.model.RequirementStatus;
import com.agentx.platform.domain.intake.model.RequirementVersion;
import com.agentx.platform.domain.intake.model.Ticket;
import com.agentx.platform.domain.intake.model.TicketBlockingScope;
import com.agentx.platform.domain.intake.model.TicketStatus;
import com.agentx.platform.domain.intake.model.TicketType;
import com.agentx.platform.domain.planning.model.WorkTask;
import com.agentx.platform.domain.planning.model.WorkTaskStatus;
import com.agentx.platform.domain.shared.model.ActorRef;
import com.agentx.platform.domain.shared.model.ActorType;
import com.agentx.platform.domain.shared.model.JsonPayload;
import com.agentx.platform.domain.shared.model.WriteScope;
import com.agentx.platform.runtime.application.workflow.TaskTemplateCatalog;
import com.agentx.platform.runtime.application.workflow.WorkflowRuntimeSnapshot;
import com.agentx.platform.runtime.context.ContextPackType;
import com.agentx.platform.runtime.context.RetrievalSnippet;
import com.agentx.platform.runtime.evaluation.EvalDimensionId;
import com.agentx.platform.runtime.evaluation.EvalEvidenceBundle;
import com.agentx.platform.runtime.evaluation.EvalScenario;
import com.agentx.platform.runtime.evaluation.EvalStatus;
import com.agentx.platform.runtime.evaluation.WorkflowEvalCenter;
import com.agentx.platform.runtime.evaluation.WorkflowEvalContextArtifact;
import com.agentx.platform.runtime.evaluation.WorkflowEvalProperties;
import com.agentx.platform.support.TestStackProfiles;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class WorkflowEvalCenterTests {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void shouldGenerateWorkflowEvalArtifactsAndFlagInvalidCatalogAndAbsolutePath() throws Exception {
        WorkflowEvalProperties properties = new WorkflowEvalProperties();
        properties.setArtifactRoot(Path.of("target", "eval-center-tests"));
        WorkflowEvalCenter evalCenter = new WorkflowEvalCenter(objectMapper, TestStackProfiles.taskTemplateCatalog(), properties);

        Path reviewBundle = Path.of("target", "eval-center-tests", "review-bundle-fixture");
        Files.createDirectories(reviewBundle.resolve("src/main/java/com/example"));
        Files.createDirectories(reviewBundle.resolve("src/test/java/com/example"));
        Files.writeString(reviewBundle.resolve("src/main/java/com/example/App.java"), "class App {}", java.nio.charset.StandardCharsets.UTF_8);
        Files.writeString(reviewBundle.resolve("src/test/java/com/example/AppTest.java"), "class AppTest {}", java.nio.charset.StandardCharsets.UTF_8);

        LocalDateTime now = LocalDateTime.now();
        WorkflowRuntimeSnapshot snapshot = new WorkflowRuntimeSnapshot(
                new WorkflowRun("workflow-eval-1", "fixed-coding", "Eval Workflow", WorkflowRunStatus.COMPLETED, EntryMode.MANUAL, false, new ActorRef(ActorType.HUMAN, "tester")),
                Optional.of(TestStackProfiles.defaultProfileRef()),
                Optional.of(new RequirementDoc("req-1", "workflow-eval-1", 1, 1, RequirementStatus.CONFIRMED, "学生管理系统")),
                List.of(new RequirementVersion("req-1", 1, "学生新增、删除、邮箱校验和回归测试", new ActorRef(ActorType.HUMAN, "tester"))),
                List.of(new Ticket(
                        "ticket-1",
                        "workflow-eval-1",
                        TicketType.CLARIFICATION,
                        TicketBlockingScope.GLOBAL_BLOCKING,
                        TicketStatus.ANSWERED,
                        "需求补充",
                        new ActorRef(ActorType.AGENT, "requirement-agent"),
                        new ActorRef(ActorType.HUMAN, "tester"),
                        "requirement",
                        "req-1",
                        1,
                        null,
                        JsonPayload.emptyObject()
                )),
                List.of(new WorkTask(
                        "task-1",
                        "module-1",
                        "实现学生管理后端骨架",
                        "交付学生 CRUD 的最小后端骨架",
                        "java-backend-code",
                        WorkTaskStatus.DONE,
                        List.of(new WriteScope("src/main/java"), new WriteScope("src/test/java")),
                        null,
                        new ActorRef(ActorType.AGENT, "architect-agent")
                )),
                List.of(),
                List.of(new TaskRun(
                        "run-1",
                        "task-1",
                        "agent-instance-1",
                        TaskRunStatus.SUCCEEDED,
                        RunKind.IMPL,
                        "snapshot-1",
                        now.plusMinutes(1),
                        now,
                        now.minusMinutes(3),
                        now.minusMinutes(1),
                        new JsonPayload("{\"image\":\"maven:3.9.11-eclipse-temurin-21\"}")
                )),
                List.of(new GitWorkspace(
                        "workspace-1",
                        "run-1",
                        "task-1",
                        GitWorkspaceStatus.CLEANED,
                        "repo-root",
                        "worktree-path",
                        "task/task-1/run-1",
                        "abc123",
                        "def456",
                        "merge789",
                        CleanupStatus.DONE
                )),
                List.of(
                        new WorkflowNodeRun("node-1", "workflow-eval-1", "requirement", "requirement-agent", null, WorkflowNodeRunStatus.SUCCEEDED, JsonPayload.emptyObject(), JsonPayload.emptyObject(), now.minusMinutes(6), now.minusMinutes(5)),
                        new WorkflowNodeRun("node-2", "workflow-eval-1", "architect", "architect-agent", null, WorkflowNodeRunStatus.SUCCEEDED, JsonPayload.emptyObject(), new JsonPayload("{\"planningGraph\":{\"summary\":\"bad plan\"}}"), now.minusMinutes(5), now.minusMinutes(4)),
                        new WorkflowNodeRun("node-3", "workflow-eval-1", "coding", "coding-agent-java", null, WorkflowNodeRunStatus.SUCCEEDED, JsonPayload.emptyObject(), JsonPayload.emptyObject(), now.minusMinutes(4), now.minusMinutes(2)),
                        new WorkflowNodeRun("node-4", "workflow-eval-1", "merge-gate", null, null, WorkflowNodeRunStatus.SUCCEEDED, JsonPayload.emptyObject(), JsonPayload.emptyObject(), now.minusMinutes(2), now.minusMinutes(1)),
                        new WorkflowNodeRun("node-5", "workflow-eval-1", "verify", "verify-agent", null, WorkflowNodeRunStatus.SUCCEEDED, JsonPayload.emptyObject(), JsonPayload.emptyObject(), now.minusMinutes(1), now)
                )
        );

        EvalScenario scenario = new EvalScenario(
                "architect-catalog-misalignment",
                "Architect mismatch",
                "做一个学生管理系统",
                "验证 catalog 对齐和绝对路径检测。",
                List.of("邮箱校验", "回归测试"),
                List.of("App.java", "AppTest.java"),
                List.of("src/main/java", "src/test/java", "."),
                null,
                true
        );

        Map<String, Object> evaluationPlan = Map.of(
                "architect", Map.of(
                        "useReal", false,
                        "fallbackReason", "real architect output did not stay within the supported task template catalog",
                        "selectedValue", Map.of(
                                "planningGraph", Map.of(
                                        "summary", "bad graph",
                                        "modules", List.of(Map.of("moduleKey", "student-core", "name", "student-core", "description", "core")),
                                        "tasks", List.of(Map.of(
                                                "taskKey", "student-impl",
                                                "moduleKey", "student-core",
                                                "title", "实现学生核心模块",
                                                "objective", "实现学生服务",
                                                "taskTemplateId", "IMPLEMENT_MODULE",
                                                "writeScopes", List.of(Map.of("path", "src/main/java/com/example/student/domain/Student.java")),
                                                "capabilityPackId", "java-backend"
                                        )),
                                        "dependencies", List.of()
                                )
                        )
                ),
                "codingImplementation", Map.of(
                        "useReal", false,
                        "fallbackReason", "real coding turn was not safe enough",
                        "selectedValue", Map.of(
                                "toolCall", Map.of(
                                        "callId", "list-root",
                                        "toolId", "tool-filesystem",
                                        "operation", "list_directory",
                                        "arguments", Map.of("path", "/workspace"),
                                        "summary", "列出工作区根目录"
                                )
                        )
                )
        );

        EvalEvidenceBundle evidence = new EvalEvidenceBundle(
                snapshot,
                Map.of("run-1", List.of(new TaskRunEvent(
                        "event-1",
                        "run-1",
                        "CODING_TURN_COMPLETED",
                        "list workspace",
                        new JsonPayload("""
                                {
                                  "decision": {
                                    "decisionType": "TOOL_CALL",
                                    "toolCall": {
                                      "callId": "list-root",
                                      "toolId": "tool-filesystem",
                                      "operation": "list_directory",
                                      "arguments": {
                                        "path": "/workspace"
                                      },
                                      "summary": "列出工作区根目录"
                                    }
                                  },
                                  "toolCallReused": false,
                                  "toolPayload": {
                                    "terminal": false,
                                    "succeeded": true
                                  }
                                }
                                """)
                ))),
                List.of(new WorkflowEvalContextArtifact(
                        ContextPackType.CODING,
                        "workflow-eval-1",
                        "task-1",
                        "run-1",
                        "coding",
                        "eval://coding",
                        "fingerprint-1",
                        now.minusMinutes(4),
                        Map.of("requirement", "邮箱校验和回归测试"),
                        List.of(new RetrievalSnippet("snippet-1", "code", "src/main/java/com/example/App.java", "App.java", "email 校验逻辑", 0.9, List.of(), Map.of()))
                )),
                Map.of("evaluationPlan", evaluationPlan),
                Map.of("reviewBundle", reviewBundle.toString())
        );

        var artifacts = evalCenter.generateWorkflowReport(scenario, evidence);

        assertThat(artifacts.rawEvidencePath()).exists();
        assertThat(artifacts.scorecardPath()).exists();
        assertThat(artifacts.markdownReportPath()).exists();
        assertThat(artifacts.profileSnapshotPath()).exists();

        String scorecardJson = Files.readString(artifacts.scorecardPath());
        assertThat(scorecardJson).contains("\"dimensionId\" : \"DAG_QUALITY\"");
        assertThat(scorecardJson).contains("IMPLEMENT_MODULE");
        assertThat(scorecardJson).contains("/workspace");
        String markdown = Files.readString(artifacts.markdownReportPath());
        assertThat(markdown).contains("IMPLEMENT_MODULE");
        assertThat(markdown).contains("/workspace");
        assertThat(markdown).contains("RAG 专项");
    }

    @Test
    void shouldPreserveNullFactValuesInEvalContextArtifact() {
        LinkedHashMap<String, Object> factSections = new LinkedHashMap<>();
        factSections.put("workflow", Map.of("workflowRunId", "workflow-eval-null"));
        factSections.put("requirementDoc", null);
        WorkflowEvalContextArtifact artifact = new WorkflowEvalContextArtifact(
                ContextPackType.CODING,
                "workflow-eval-null",
                "task-1",
                null,
                "coding",
                "eval://coding-null",
                "fingerprint-null",
                LocalDateTime.now(),
                factSections,
                List.of()
        );

        assertThat(artifact.factSections()).containsEntry("requirementDoc", null);
    }

    @Test
    void shouldNotFlagMissingVerifyOrDeliveryArtifactsBeforeThoseStagesAreReached() {
        WorkflowEvalProperties properties = new WorkflowEvalProperties();
        properties.setArtifactRoot(Path.of("target", "eval-center-tests"));
        WorkflowEvalCenter evalCenter = new WorkflowEvalCenter(objectMapper, TestStackProfiles.taskTemplateCatalog(), properties);
        LocalDateTime now = LocalDateTime.now();

        WorkflowRuntimeSnapshot snapshot = new WorkflowRuntimeSnapshot(
                new WorkflowRun("workflow-eval-2", "fixed-coding", "Eval Workflow", WorkflowRunStatus.ACTIVE, EntryMode.MANUAL, false, new ActorRef(ActorType.HUMAN, "tester")),
                Optional.of(TestStackProfiles.defaultProfileRef()),
                Optional.of(new RequirementDoc("req-2", "workflow-eval-2", 1, 1, RequirementStatus.CONFIRMED, "学生管理系统")),
                List.of(new RequirementVersion("req-2", 1, "学生新增、删除、邮箱校验和回归测试", new ActorRef(ActorType.HUMAN, "tester"))),
                List.of(),
                List.of(new WorkTask(
                        "task-2",
                        "module-2",
                        "实现学生管理后端骨架",
                        "交付学生 CRUD 的最小后端骨架",
                        "java-backend-code",
                        WorkTaskStatus.READY,
                        List.of(new WriteScope("src/main/java"), new WriteScope("src/test/java")),
                        null,
                        new ActorRef(ActorType.AGENT, "architect-agent")
                )),
                List.of(),
                List.of(),
                List.of(),
                List.of(
                        new WorkflowNodeRun("node-1", "workflow-eval-2", "requirement", "requirement-agent", null, WorkflowNodeRunStatus.SUCCEEDED, JsonPayload.emptyObject(), JsonPayload.emptyObject(), now.minusMinutes(3), now.minusMinutes(2)),
                        new WorkflowNodeRun("node-2", "workflow-eval-2", "architect", "architect-agent", null, WorkflowNodeRunStatus.SUCCEEDED, JsonPayload.emptyObject(), JsonPayload.emptyObject(), now.minusMinutes(2), now.minusMinutes(1)),
                        new WorkflowNodeRun("node-3", "workflow-eval-2", "task-graph", null, null, WorkflowNodeRunStatus.SUCCEEDED, JsonPayload.emptyObject(), JsonPayload.emptyObject(), now.minusMinutes(1), now)
                )
        );

        EvalScenario scenario = new EvalScenario(
                "pre-delivery-runtime-abort",
                "Pre delivery abort",
                "做一个学生管理系统",
                "验证评测中心不会把尚未到达的 verify/delivery 阶段误判成 hard gate。",
                List.of("邮箱校验"),
                List.of("App.java"),
                List.of("src/main/java", "src/test/java", "."),
                null,
                true
        );

        EvalEvidenceBundle evidence = new EvalEvidenceBundle(
                snapshot,
                Map.of(),
                List.of(new WorkflowEvalContextArtifact(
                        ContextPackType.ARCHITECT,
                        "workflow-eval-2",
                        null,
                        null,
                        "architect",
                        "eval://architect",
                        "fingerprint-2",
                        now.minusMinutes(1),
                        Map.of("requirement", "邮箱校验"),
                        List.of(new RetrievalSnippet("snippet-1", "code", "src/main/java/com/example/App.java", "App.java", "email validation", 0.9, List.of(), Map.of()))
                )),
                Map.of(),
                Map.of("reviewBundle", Path.of("target", "eval-center-tests", "empty-review-bundle").toString())
        );

        var scorecard = evalCenter.scoreWorkflow(scenario, evidence);

        assertThat(scorecard.findings()).noneMatch(finding -> "missing-verify-context".equals(finding.code()));
        assertThat(scorecard.findings()).noneMatch(finding -> "missing-required-artifact-roles".equals(finding.code()));
    }

    @Test
    void shouldAttributeProviderFailureToRuntimeInsteadOfRagBeforeArchitectStarts() {
        WorkflowEvalProperties properties = new WorkflowEvalProperties();
        properties.setArtifactRoot(Path.of("target", "eval-center-tests"));
        WorkflowEvalCenter evalCenter = new WorkflowEvalCenter(objectMapper, TestStackProfiles.taskTemplateCatalog(), properties);

        WorkflowRuntimeSnapshot snapshot = new WorkflowRuntimeSnapshot(
                new WorkflowRun("workflow-eval-provider", "fixed-coding", "Eval Workflow", WorkflowRunStatus.ACTIVE, EntryMode.MANUAL, false, new ActorRef(ActorType.HUMAN, "tester")),
                Optional.of(TestStackProfiles.defaultProfileRef()),
                Optional.empty(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of()
        );

        EvalScenario scenario = new EvalScenario(
                "provider-failure-before-architect",
                "Provider failure before architect",
                "做一个学生管理系统",
                "验证 provider 失败时评测中心应把失败归因到 runtime，而不是把未开始的 RAG/DAG 阶段误判成 hard gate。",
                List.of("邮箱校验"),
                List.of("StudentService.java"),
                List.of("src/main/java"),
                null,
                true
        );

        EvalEvidenceBundle evidence = new EvalEvidenceBundle(
                snapshot,
                Map.of(),
                List.of(),
                Map.of("workflowResult", Map.of(
                        "runnerStatus", "ABORTED",
                        "stopReason", "workflow terminated because the real model provider failed before reaching a stable boundary",
                        "stepHistory", List.of(Map.of(
                                "stepType", "PROVIDER_FAILURE"
                        ))
                )),
                Map.of()
        );

        var scorecard = evalCenter.scoreWorkflow(scenario, evidence);
        var runtimeDimension = scorecard.dimensions().stream()
                .filter(result -> result.dimensionId() == EvalDimensionId.RUNTIME_ROBUSTNESS)
                .findFirst()
                .orElseThrow();
        var ragDimension = scorecard.dimensions().stream()
                .filter(result -> result.dimensionId() == EvalDimensionId.RAG_QUALITY)
                .findFirst()
                .orElseThrow();

        assertThat(scorecard.overallStatus()).isEqualTo(EvalStatus.FAIL);
        assertThat(runtimeDimension.findings()).anyMatch(finding -> "provider-failure-abort".equals(finding.code()));
        assertThat(ragDimension.findings()).anyMatch(finding -> "rag-not-reached".equals(finding.code()));
        assertThat(scorecard.findings()).noneMatch(finding -> "missing-repo-retrieval".equals(finding.code()));
        assertThat(scorecard.findings()).noneMatch(finding -> finding.code().startsWith("missing-golden-fact-"));
    }

    @Test
    void shouldTreatFailedRunAsRecoverableWhenTaskReturnedToReadyWithoutBlockerTicket() {
        WorkflowEvalProperties properties = new WorkflowEvalProperties();
        properties.setArtifactRoot(Path.of("target", "eval-center-tests"));
        WorkflowEvalCenter evalCenter = new WorkflowEvalCenter(objectMapper, TestStackProfiles.taskTemplateCatalog(), properties);
        LocalDateTime now = LocalDateTime.now();

        WorkflowRuntimeSnapshot snapshot = new WorkflowRuntimeSnapshot(
                new WorkflowRun("workflow-eval-3", "fixed-coding", "Eval Workflow", WorkflowRunStatus.EXECUTING_TASKS, EntryMode.MANUAL, false, new ActorRef(ActorType.HUMAN, "tester")),
                Optional.of(TestStackProfiles.defaultProfileRef()),
                Optional.of(new RequirementDoc("req-3", "workflow-eval-3", 1, 1, RequirementStatus.CONFIRMED, "学生管理系统")),
                List.of(new RequirementVersion("req-3", 1, "学生新增、删除、邮箱校验和回归测试", new ActorRef(ActorType.HUMAN, "tester"))),
                List.of(),
                List.of(
                        new WorkTask(
                                "task-3",
                                "module-3",
                                "实现学生管理后端骨架",
                                "交付学生 CRUD 的最小后端骨架",
                                "java-backend-code",
                                WorkTaskStatus.READY,
                                List.of(new WriteScope("src/main/java")),
                                null,
                                new ActorRef(ActorType.AGENT, "architect-agent")
                        )
                ),
                List.of(),
                List.of(new TaskRun(
                        "run-3",
                        "task-3",
                        "agent-instance-3",
                        TaskRunStatus.FAILED,
                        RunKind.IMPL,
                        "snapshot-3",
                        now.plusMinutes(1),
                        now,
                        now.minusMinutes(2),
                        now.minusMinutes(1),
                        JsonPayload.emptyObject()
                )),
                List.of(new GitWorkspace(
                        "workspace-3",
                        "run-3",
                        "task-3",
                        GitWorkspaceStatus.CLEANED,
                        "repo-root",
                        "worktree-path",
                        "task/task-3/run-3",
                        "abc123",
                        null,
                        null,
                        CleanupStatus.DONE
                )),
                List.of(
                        new WorkflowNodeRun("node-1", "workflow-eval-3", "requirement", "requirement-agent", null, WorkflowNodeRunStatus.SUCCEEDED, JsonPayload.emptyObject(), JsonPayload.emptyObject(), now.minusMinutes(4), now.minusMinutes(3)),
                        new WorkflowNodeRun("node-2", "workflow-eval-3", "architect", "architect-agent", null, WorkflowNodeRunStatus.SUCCEEDED, JsonPayload.emptyObject(), JsonPayload.emptyObject(), now.minusMinutes(3), now.minusMinutes(2)),
                        new WorkflowNodeRun("node-3", "workflow-eval-3", "coding", "coding-agent-java", null, WorkflowNodeRunStatus.SUCCEEDED, JsonPayload.emptyObject(), JsonPayload.emptyObject(), now.minusMinutes(2), now.minusMinutes(1))
                )
        );

        EvalScenario scenario = new EvalScenario(
                "retryable-runtime-failure",
                "Retryable runtime failure",
                "做一个学生管理系统",
                "验证评测中心不会把已回到 READY 的失败运行误判成缺失 blocker ticket。",
                List.of("邮箱校验"),
                List.of("StudentService.java"),
                List.of("src/main/java"),
                null,
                true
        );

        EvalEvidenceBundle evidence = new EvalEvidenceBundle(
                snapshot,
                Map.of(),
                List.of(),
                Map.of(),
                Map.of()
        );

        var scorecard = evalCenter.scoreWorkflow(scenario, evidence);
        var runtimeDimension = scorecard.dimensions().stream()
                .filter(result -> result.dimensionId() == EvalDimensionId.RUNTIME_ROBUSTNESS)
                .findFirst()
                .orElseThrow();

        assertThat(runtimeDimension.findings()).noneMatch(finding -> "failed-run-without-task-blocking-ticket".equals(finding.code()));
        assertThat(runtimeDimension.findings()).anyMatch(finding -> "retryable-failed-run-observed".equals(finding.code()));
        assertThat(runtimeDimension.status()).isEqualTo(EvalStatus.WARN);
    }

    @Test
    void shouldUseLatestSuccessfulRunAndWorkspaceForDeliveryArtifactJudgement() {
        WorkflowEvalProperties properties = new WorkflowEvalProperties();
        properties.setArtifactRoot(Path.of("target", "eval-center-tests"));
        WorkflowEvalCenter evalCenter = new WorkflowEvalCenter(objectMapper, TestStackProfiles.taskTemplateCatalog(), properties);
        LocalDateTime now = LocalDateTime.now();

        WorkflowRuntimeSnapshot snapshot = new WorkflowRuntimeSnapshot(
                new WorkflowRun("workflow-eval-4", "fixed-coding", "Eval Workflow", WorkflowRunStatus.COMPLETED, EntryMode.MANUAL, false, new ActorRef(ActorType.HUMAN, "tester")),
                Optional.of(TestStackProfiles.defaultProfileRef()),
                Optional.of(new RequirementDoc("req-4", "workflow-eval-4", 1, 1, RequirementStatus.CONFIRMED, "学生管理系统")),
                List.of(new RequirementVersion("req-4", 1, "学生新增、删除、邮箱校验和回归测试", new ActorRef(ActorType.HUMAN, "tester"))),
                List.of(),
                List.of(new WorkTask(
                        "task-4",
                        "module-4",
                        "实现学生管理后端骨架",
                        "交付学生 CRUD 的最小后端骨架",
                        "java-backend-code",
                        WorkTaskStatus.DONE,
                        List.of(new WriteScope("src/main/java"), new WriteScope("src/test/java")),
                        null,
                        new ActorRef(ActorType.AGENT, "architect-agent")
                )),
                List.of(),
                List.of(
                        new TaskRun(
                                "run-4-1",
                                "task-4",
                                "agent-instance-4-1",
                                TaskRunStatus.FAILED,
                                RunKind.IMPL,
                                "snapshot-4-1",
                                now.minusMinutes(4),
                                now.minusMinutes(5),
                                now.minusMinutes(7),
                                now.minusMinutes(5),
                                JsonPayload.emptyObject()
                        ),
                        new TaskRun(
                                "run-4-2",
                                "task-4",
                                "agent-instance-4-2",
                                TaskRunStatus.SUCCEEDED,
                                RunKind.IMPL,
                                "snapshot-4-2",
                                now.plusMinutes(1),
                                now,
                                now.minusMinutes(3),
                                now.minusMinutes(1),
                                JsonPayload.emptyObject()
                        )
                ),
                List.of(
                        new GitWorkspace(
                                "workspace-4-1",
                                "run-4-1",
                                "task-4",
                                GitWorkspaceStatus.READY,
                                "repo-root",
                                "worktree-path-1",
                                "task/task-4/run-4-1",
                                "abc123",
                                null,
                                null,
                                CleanupStatus.PENDING
                        ),
                        new GitWorkspace(
                                "workspace-4-2",
                                "run-4-2",
                                "task-4",
                                GitWorkspaceStatus.CLEANED,
                                "repo-root",
                                "worktree-path-2",
                                "task/task-4/run-4-2",
                                "def456",
                                "ghi789",
                                "merge012",
                                CleanupStatus.DONE
                        )
                ),
                List.of(
                        new WorkflowNodeRun("node-1", "workflow-eval-4", "requirement", "requirement-agent", null, WorkflowNodeRunStatus.SUCCEEDED, JsonPayload.emptyObject(), JsonPayload.emptyObject(), now.minusMinutes(8), now.minusMinutes(7)),
                        new WorkflowNodeRun("node-2", "workflow-eval-4", "architect", "architect-agent", null, WorkflowNodeRunStatus.SUCCEEDED, JsonPayload.emptyObject(), JsonPayload.emptyObject(), now.minusMinutes(7), now.minusMinutes(6)),
                        new WorkflowNodeRun("node-3", "workflow-eval-4", "coding", "coding-agent-java", null, WorkflowNodeRunStatus.SUCCEEDED, JsonPayload.emptyObject(), JsonPayload.emptyObject(), now.minusMinutes(6), now.minusMinutes(5)),
                        new WorkflowNodeRun("node-4", "workflow-eval-4", "merge-gate", null, null, WorkflowNodeRunStatus.SUCCEEDED, JsonPayload.emptyObject(), JsonPayload.emptyObject(), now.minusMinutes(2), now.minusMinutes(1)),
                        new WorkflowNodeRun("node-5", "workflow-eval-4", "verify", "verify-agent", null, WorkflowNodeRunStatus.SUCCEEDED, JsonPayload.emptyObject(), JsonPayload.emptyObject(), now.minusMinutes(1), now)
                )
        );

        EvalScenario scenario = new EvalScenario(
                "delivery-latest-run-wins",
                "Delivery latest run wins",
                "做一个学生管理系统",
                "验证交付维度按任务最终有效 run/workspace 判定，而不是被历史失败尝试误伤。",
                List.of("邮箱校验"),
                List.of("StudentService.java"),
                List.of("src/main/java", "src/test/java"),
                null,
                true
        );

        EvalEvidenceBundle evidence = new EvalEvidenceBundle(
                snapshot,
                Map.of(),
                List.of(),
                Map.of(),
                Map.of("reviewBundle", Path.of("target", "eval-center-tests", "review-bundle-fixture").toString())
        );

        var scorecard = evalCenter.scoreWorkflow(scenario, evidence);
        var deliveryDimension = scorecard.dimensions().stream()
                .filter(result -> result.dimensionId() == EvalDimensionId.DELIVERY_ARTIFACT)
                .findFirst()
                .orElseThrow();
        var runtimeDimension = scorecard.dimensions().stream()
                .filter(result -> result.dimensionId() == EvalDimensionId.RUNTIME_ROBUSTNESS)
                .findFirst()
                .orElseThrow();

        assertThat(deliveryDimension.status()).isEqualTo(EvalStatus.PASS);
        assertThat(deliveryDimension.findings()).noneMatch(finding -> "task-run-not-succeeded".equals(finding.code()));
        assertThat(deliveryDimension.findings()).noneMatch(finding -> "workspace-not-merged-or-cleaned".equals(finding.code()));
        assertThat(runtimeDimension.findings()).anyMatch(finding -> "retryable-failed-run-observed".equals(finding.code()));
        assertThat(runtimeDimension.findings()).anyMatch(finding -> "workspace-cleanup-pending".equals(finding.code()));
    }

    @Test
    void shouldUseLatestNonEmptyPlanningGraphForDagEvaluation() {
        WorkflowEvalProperties properties = new WorkflowEvalProperties();
        properties.setArtifactRoot(Path.of("target", "eval-center-tests"));
        WorkflowEvalCenter evalCenter = new WorkflowEvalCenter(objectMapper, TestStackProfiles.taskTemplateCatalog(), properties);

        LocalDateTime now = LocalDateTime.now();
        WorkflowRuntimeSnapshot snapshot = new WorkflowRuntimeSnapshot(
                new WorkflowRun("workflow-eval-dag", "fixed-coding", "Eval Workflow", WorkflowRunStatus.EXECUTING_TASKS, EntryMode.MANUAL, false, new ActorRef(ActorType.HUMAN, "tester")),
                Optional.of(TestStackProfiles.defaultProfileRef()),
                Optional.empty(),
                List.of(),
                List.of(),
                List.of(new WorkTask(
                        "task-1",
                        "module-1",
                        "实现学生管理后端骨架",
                        "交付学生 CRUD 的最小后端骨架",
                        "java-backend-code",
                        WorkTaskStatus.READY,
                        List.of(new WriteScope("src/main/java")),
                        null,
                        new ActorRef(ActorType.AGENT, "architect-agent")
                )),
                List.of(),
                List.of(),
                List.of(),
                List.of(
                        new WorkflowNodeRun(
                                "architect-node-1",
                                "workflow-eval-dag",
                                "architect",
                                "architect-agent",
                                null,
                                WorkflowNodeRunStatus.SUCCEEDED,
                                JsonPayload.emptyObject(),
                                new JsonPayload("""
                                        {
                                          "planningGraph": {
                                            "summary": "student backend",
                                            "modules": [
                                              {
                                                "moduleKey": "student-core",
                                                "name": "学生核心",
                                                "description": "核心服务"
                                              }
                                            ],
                                            "tasks": [
                                              {
                                                "taskKey": "student-backend",
                                                "moduleKey": "student-core",
                                                "title": "实现学生后端",
                                                "objective": "交付学生 CRUD",
                                                "taskTemplateId": "java-backend-code",
                                                "writeScopes": [
                                                  {
                                                    "path": "src/main/java"
                                                  }
                                                ],
                                                "capabilityPackId": "cap-java-backend-coding"
                                              }
                                            ],
                                            "dependencies": []
                                          }
                                        }
                                        """),
                                now.minusMinutes(2),
                                now.minusMinutes(1)
                        ),
                        new WorkflowNodeRun(
                                "architect-node-2",
                                "workflow-eval-dag",
                                "architect",
                                "architect-agent",
                                null,
                                WorkflowNodeRunStatus.SUCCEEDED,
                                JsonPayload.emptyObject(),
                                new JsonPayload("""
                                        {
                                          "decision": "NO_CHANGES",
                                          "planningGraph": {
                                            "summary": "",
                                            "modules": [],
                                            "tasks": [],
                                            "dependencies": []
                                          }
                                        }
                                        """),
                                now.minusSeconds(30),
                                now.minusSeconds(10)
                        )
                )
        );

        EvalScenario scenario = new EvalScenario(
                "dag-non-empty-fallback",
                "DAG fallback",
                "做一个学生管理系统",
                "验证 DAG 评测应回退到最近一次非空 planning graph。",
                List.of(),
                List.of(),
                List.of("src/main/java"),
                null,
                true
        );

        var scorecard = evalCenter.scoreWorkflow(scenario, new EvalEvidenceBundle(snapshot, Map.of(), List.of(), Map.of(), Map.of()));
        var dagDimension = scorecard.dimensions().stream()
                .filter(result -> result.dimensionId() == EvalDimensionId.DAG_QUALITY)
                .findFirst()
                .orElseThrow();

        assertThat(dagDimension.metrics()).containsEntry("taskCount", 1);
        assertThat(dagDimension.summary()).contains("1 个 task");
    }
}
