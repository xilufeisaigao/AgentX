package com.agentx.platform;

import com.agentx.platform.domain.execution.model.GitWorkspaceStatus;
import com.agentx.platform.domain.execution.model.TaskRunStatus;
import com.agentx.platform.domain.flow.model.WorkflowRunStatus;
import com.agentx.platform.domain.intake.model.RequirementStatus;
import com.agentx.platform.domain.intake.model.TicketStatus;
import com.agentx.platform.domain.planning.model.WorkTaskStatus;
import com.agentx.platform.domain.shared.model.ActorRef;
import com.agentx.platform.domain.shared.model.ActorType;
import com.agentx.platform.runtime.application.workflow.AnswerTicketCommand;
import com.agentx.platform.runtime.application.workflow.ConfirmRequirementDocCommand;
import com.agentx.platform.runtime.application.workflow.FixedCodingWorkflowUseCase;
import com.agentx.platform.runtime.application.workflow.StartCodingWorkflowCommand;
import com.agentx.platform.runtime.application.workflow.WorkflowRuntimeSnapshot;
import com.agentx.platform.runtime.application.workflow.WorkflowScenario;
import com.agentx.platform.runtime.agentkernel.architect.ArchitectConversationAgent;
import com.agentx.platform.runtime.agentkernel.architect.ArchitectDecision;
import com.agentx.platform.runtime.agentkernel.architect.ArchitectDecisionType;
import com.agentx.platform.runtime.agentkernel.architect.PlanningGraphSpec;
import com.agentx.platform.runtime.agentkernel.coding.CodingAgentDecision;
import com.agentx.platform.runtime.agentkernel.coding.CodingConversationAgent;
import com.agentx.platform.runtime.agentkernel.coding.CodingDecisionType;
import com.agentx.platform.runtime.agentkernel.model.StructuredModelResult;
import com.agentx.platform.runtime.agentkernel.requirement.RequirementAgentDecision;
import com.agentx.platform.runtime.agentkernel.requirement.RequirementConversationAgent;
import com.agentx.platform.runtime.agentkernel.requirement.RequirementDecisionType;
import com.agentx.platform.runtime.agentkernel.verify.VerifyDecision;
import com.agentx.platform.runtime.agentkernel.verify.VerifyDecisionAgent;
import com.agentx.platform.runtime.agentkernel.verify.VerifyDecisionType;
import com.agentx.platform.runtime.tooling.ToolCall;
import com.agentx.platform.support.TestGitRepoHelper;
import com.agentx.platform.support.TestStackProfiles;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
class FixedCodingWorkflowRuntimeIT {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("agentx_platform");

    private static final Path TEST_ROOT = tempDir("agentx-runtime-it");
    private static final Path REPO_ROOT = TEST_ROOT.resolve("repo");
    private static final Path WORKSPACE_ROOT = TEST_ROOT.resolve("workspaces");

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("agentx.platform.runtime.repo-root", REPO_ROOT::toString);
        registry.add("agentx.platform.runtime.base-branch", () -> "main");
        registry.add("agentx.platform.runtime.workspace-root", WORKSPACE_ROOT::toString);
        registry.add("agentx.platform.runtime.driver-enabled", () -> false);
        registry.add("agentx.platform.runtime.supervisor-enabled", () -> false);
        registry.add("agentx.platform.runtime.blocking-timeout", () -> "PT120S");
    }

    @Autowired
    private FixedCodingWorkflowUseCase workflowUseCase;

    @Autowired
    private DataSource dataSource;

    @MockBean
    private RequirementConversationAgent requirementConversationAgent;

    @MockBean
    private ArchitectConversationAgent architectConversationAgent;

    @MockBean
    private CodingConversationAgent codingConversationAgent;

    @MockBean
    private VerifyDecisionAgent verifyDecisionAgent;

    @BeforeEach
    void resetEnvironment() throws Exception {
        TestGitRepoHelper.resetFixtureRepository(REPO_ROOT);
        TestGitRepoHelper.cleanDirectory(WORKSPACE_ROOT);
        try (Connection connection = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(connection, sanitizedSchemaScript());
        }
        reset(requirementConversationAgent, architectConversationAgent, codingConversationAgent, verifyDecisionAgent);
        when(architectConversationAgent.evaluate(any(), any())).thenReturn(planReady());
        when(codingConversationAgent.evaluate(any(), any(), eq("no prior turns"))).thenAnswer(invocation -> {
            com.agentx.platform.runtime.context.CompiledContextPack contextPack = invocation.getArgument(1);
            Object taskObject = contextPack.factBundle().sections().get("task");
            String taskId;
            String markerPath;
            if (taskObject instanceof com.agentx.platform.domain.planning.model.WorkTask workTask) {
                taskId = workTask.taskId();
                String writeRoot = workTask.writeScopes().isEmpty()
                        ? ".agentx-runtime"
                        : workTask.writeScopes().get(0).path().replace('\\', '/');
                markerPath = writeRoot + "/.agentx-" + taskId + ".txt";
            } else {
                java.util.Map<?, ?> taskMap = (java.util.Map<?, ?>) taskObject;
                taskId = String.valueOf(taskMap.get("taskId"));
                Object writeScopes = taskMap.get("writeScopes");
                String writeRoot = "src/main/java";
                if (writeScopes instanceof java.util.List<?> scopes && !scopes.isEmpty() && scopes.get(0) != null) {
                    writeRoot = String.valueOf(scopes.get(0));
                }
                markerPath = writeRoot.replace('\\', '/') + "/.agentx-" + taskId + ".txt";
            }
            return new StructuredModelResult<>(
                    new CodingAgentDecision(
                            CodingDecisionType.TOOL_CALL,
                            new ToolCall(
                                    "tool-filesystem",
                                    "write_file",
                                    java.util.Map.of(
                                            "path", markerPath,
                                            "content", "workflow=" + contextPack.scope().workflowRunId() + "\ntask=" + taskId + "\n"
                                    ),
                                    "write deterministic marker"
                            ),
                            null,
                            null,
                            "write deterministic marker"
                    ),
                    "stub",
                    "deepseek-chat",
                    "{\"decisionType\":\"TOOL_CALL\"}"
            );
        });
        when(codingConversationAgent.evaluate(any(), any(), org.mockito.ArgumentMatchers.contains("write deterministic marker")))
                .thenReturn(new StructuredModelResult<>(
                        new CodingAgentDecision(
                                CodingDecisionType.DELIVER,
                                null,
                                null,
                                null,
                                "deliver candidate"
                        ),
                        "stub",
                        "deepseek-chat",
                        "{\"decisionType\":\"DELIVER\"}"
                ));
        when(verifyDecisionAgent.evaluate(any(), any())).thenReturn(new StructuredModelResult<>(
                new VerifyDecision(
                        VerifyDecisionType.PASS,
                        "deterministic verify passed",
                        null,
                        null
                ),
                "stub",
                "deepseek-chat",
                "{\"decision\":\"PASS\"}"
        ));
    }

    @Test
    void shouldCompleteWorkflowWithRealDockerAndGit() {
        when(requirementConversationAgent.evaluate(any(), any())).thenReturn(draftReady("Happy Path Requirement", "实现一个最小固定主链 happy path。"));

        String workflowRunId = workflowUseCase.start(new StartCodingWorkflowCommand(
                "Happy Path Runtime",
                "Happy Path Requirement",
                "实现一个最小固定主链 happy path。",
                TestStackProfiles.DEFAULT_PROFILE_ID,
                new ActorRef(ActorType.HUMAN, "happy-user"),
                false,
                WorkflowScenario.defaultScenario()
        ));

        WorkflowRuntimeSnapshot reviewSnapshot = workflowUseCase.runUntilStable(workflowRunId);
        assertThat(reviewSnapshot.workflowRun().status()).isEqualTo(WorkflowRunStatus.WAITING_ON_HUMAN);
        assertThat(reviewSnapshot.requirementDoc()).isPresent();
        assertThat(reviewSnapshot.requirementDoc().orElseThrow().status()).isEqualTo(RequirementStatus.IN_REVIEW);

        workflowUseCase.confirmRequirementDoc(new ConfirmRequirementDocCommand(
                reviewSnapshot.requirementDoc().orElseThrow().docId(),
                reviewSnapshot.requirementDoc().orElseThrow().currentVersion(),
                new ActorRef(ActorType.HUMAN, "happy-user")
        ));

        WorkflowRuntimeSnapshot snapshot = workflowUseCase.runUntilStable(workflowRunId);

        assertThat(snapshot.workflowRun().status()).isEqualTo(WorkflowRunStatus.COMPLETED);
        assertThat(snapshot.tasks()).anyMatch(task -> task.status() == WorkTaskStatus.DONE);
        assertThat(snapshot.taskRuns()).anyMatch(run -> run.status() == TaskRunStatus.SUCCEEDED);
        assertThat(snapshot.workspaces()).anyMatch(workspace ->
                workspace.status() == GitWorkspaceStatus.CLEANED || workspace.status() == GitWorkspaceStatus.MERGED);
    }

    @Test
    void shouldPauseForClarificationAndResume() {
        when(requirementConversationAgent.evaluate(any(), any())).thenReturn(
                clarification(),
                draftReady("Clarification Requirement", "第一次执行需要人工澄清的固定主链场景。")
        );

        String workflowRunId = workflowUseCase.start(new StartCodingWorkflowCommand(
                "Clarification Runtime",
                "Clarification Requirement",
                "第一次执行需要人工澄清的固定主链场景。",
                TestStackProfiles.DEFAULT_PROFILE_ID,
                new ActorRef(ActorType.HUMAN, "clarification-user"),
                false,
                new WorkflowScenario(true, false, false)
        ));

        WorkflowRuntimeSnapshot waitingSnapshot = workflowUseCase.runUntilStable(workflowRunId);
        assertThat(waitingSnapshot.workflowRun().status()).isEqualTo(WorkflowRunStatus.WAITING_ON_HUMAN);
        assertThat(waitingSnapshot.requirementDoc()).isEmpty();

        String ticketId = waitingSnapshot.tickets().stream()
                .filter(ticket -> ticket.status() == TicketStatus.OPEN && ticket.assignee().type() == ActorType.HUMAN)
                .findFirst()
                .orElseThrow()
                .ticketId();

        workflowUseCase.answerTicket(new AnswerTicketCommand(
                ticketId,
                "可以继续执行，不需要额外探测语义。",
                new ActorRef(ActorType.HUMAN, "clarification-user")
        ));

        WorkflowRuntimeSnapshot draftSnapshot = workflowUseCase.runUntilStable(workflowRunId);
        assertThat(draftSnapshot.workflowRun().status()).isEqualTo(WorkflowRunStatus.WAITING_ON_HUMAN);
        assertThat(draftSnapshot.requirementDoc()).isPresent();
        assertThat(draftSnapshot.requirementDoc().orElseThrow().status()).isEqualTo(RequirementStatus.IN_REVIEW);

        workflowUseCase.confirmRequirementDoc(new ConfirmRequirementDocCommand(
                draftSnapshot.requirementDoc().orElseThrow().docId(),
                draftSnapshot.requirementDoc().orElseThrow().currentVersion(),
                new ActorRef(ActorType.HUMAN, "clarification-user")
        ));

        WorkflowRuntimeSnapshot completedSnapshot = workflowUseCase.runUntilStable(workflowRunId);
        assertThat(completedSnapshot.workflowRun().status()).isEqualTo(WorkflowRunStatus.COMPLETED);
        assertThat(completedSnapshot.tasks()).allMatch(task -> task.status() == WorkTaskStatus.DONE);
        assertThat(completedSnapshot.taskRuns()).anyMatch(run -> run.status() == TaskRunStatus.SUCCEEDED);
    }

    @Test
    void shouldCompleteStudentManagementWorkflowWithDeterministicRuntime() {
        when(requirementConversationAgent.evaluate(any(), any())).thenReturn(draftReady(
                "学生管理系统需求",
                """
                        构建一个最小学生管理系统，支持学生信息新增、查询、更新和删除。
                        需要包含基础字段校验，并补齐一组最小回归测试。
                        """
        ));
        when(architectConversationAgent.evaluate(any(), any())).thenReturn(studentManagementPlanReady());

        String workflowRunId = workflowUseCase.start(new StartCodingWorkflowCommand(
                "Student Management Runtime",
                "学生管理系统需求",
                """
                        构建一个最小学生管理系统，支持学生信息新增、查询、更新和删除。
                        需要包含基础字段校验，并补齐一组最小回归测试。
                        """,
                TestStackProfiles.DEFAULT_PROFILE_ID,
                new ActorRef(ActorType.HUMAN, "student-user"),
                false,
                WorkflowScenario.defaultScenario()
        ));

        WorkflowRuntimeSnapshot reviewSnapshot = workflowUseCase.runUntilStable(workflowRunId);
        assertThat(reviewSnapshot.workflowRun().status()).isEqualTo(WorkflowRunStatus.WAITING_ON_HUMAN);
        assertThat(reviewSnapshot.requirementDoc()).isPresent();

        workflowUseCase.confirmRequirementDoc(new ConfirmRequirementDocCommand(
                reviewSnapshot.requirementDoc().orElseThrow().docId(),
                reviewSnapshot.requirementDoc().orElseThrow().currentVersion(),
                new ActorRef(ActorType.HUMAN, "student-user")
        ));

        WorkflowRuntimeSnapshot completedSnapshot = workflowUseCase.runUntilStable(workflowRunId);

        assertThat(completedSnapshot.workflowRun().status()).isEqualTo(WorkflowRunStatus.COMPLETED);
        assertThat(completedSnapshot.tasks())
                .hasSize(2)
                .allMatch(task -> task.status() == WorkTaskStatus.DONE);
        assertThat(completedSnapshot.tasks())
                .extracting(task -> task.title())
                .containsExactlyInAnyOrder("实现学生管理后端骨架", "补齐学生管理回归测试");
        assertThat(completedSnapshot.taskRuns())
                .filteredOn(run -> run.status() == TaskRunStatus.SUCCEEDED)
                .hasSize(2);
        assertThat(completedSnapshot.workspaces())
                .allMatch(workspace -> workspace.status() == GitWorkspaceStatus.CLEANED);
    }

    private StructuredModelResult<RequirementAgentDecision> clarification() {
        return new StructuredModelResult<>(
                new RequirementAgentDecision(
                        RequirementDecisionType.NEED_INPUT,
                        List.of("缺少失败提示"),
                        List.of("登录失败时应该展示什么提示？"),
                        null,
                        null,
                        "need clarification"
                ),
                "stub",
                "deepseek-chat",
                "{\"decision\":\"NEED_INPUT\"}"
        );
    }

    private StructuredModelResult<RequirementAgentDecision> draftReady(String title, String content) {
        return new StructuredModelResult<>(
                new RequirementAgentDecision(
                        RequirementDecisionType.DRAFT_READY,
                        List.of(),
                        List.of(),
                        title,
                        content,
                        "draft ready"
                ),
                "stub",
                "deepseek-chat",
                "{\"decision\":\"DRAFT_READY\"}"
        );
    }

    private StructuredModelResult<ArchitectDecision> planReady() {
        return new StructuredModelResult<>(
                new ArchitectDecision(
                        ArchitectDecisionType.PLAN_READY,
                        List.of(),
                        List.of(),
                        "create one coding task",
                        new PlanningGraphSpec(
                                "single module plan",
                                List.of(new PlanningGraphSpec.ModulePlan(
                                        "core",
                                        "core",
                                        "runtime core"
                                )),
                                List.of(new PlanningGraphSpec.TaskPlan(
                                        "impl",
                                        "core",
                                        "实现运行标记",
                                        "写出一个可验证的最小交付候选",
                                        "java-backend-code",
                                        List.of(),
                                        "cap-java-backend-coding"
                                )),
                                List.of()
                        )
                ),
                "stub",
                "deepseek-chat",
                "{\"decision\":\"PLAN_READY\"}"
        );
    }

    private StructuredModelResult<ArchitectDecision> studentManagementPlanReady() {
        return new StructuredModelResult<>(
                new ArchitectDecision(
                        ArchitectDecisionType.PLAN_READY,
                        List.of(),
                        List.of(),
                        "split the student management workflow into implementation and regression tasks",
                        new PlanningGraphSpec(
                                "student management baseline plan",
                                List.of(new PlanningGraphSpec.ModulePlan(
                                        "student-core",
                                        "student-core",
                                        "student management module"
                                )),
                                List.of(
                                        new PlanningGraphSpec.TaskPlan(
                                                "student-impl",
                                                "student-core",
                                                "实现学生管理后端骨架",
                                                "交付学生 CRUD 的最小后端骨架",
                                                "java-backend-code",
                                                List.of(),
                                                "cap-java-backend-coding"
                                        ),
                                        new PlanningGraphSpec.TaskPlan(
                                                "student-tests",
                                                "student-core",
                                                "补齐学生管理回归测试",
                                                "补齐围绕学生 CRUD 的最小回归测试",
                                                "java-backend-test",
                                                List.of(),
                                                "cap-java-backend-coding"
                                        )
                                ),
                                List.of(new PlanningGraphSpec.DependencyPlan("student-tests", "student-impl"))
                        )
                ),
                "stub",
                "deepseek-chat",
                "{\"decision\":\"PLAN_READY\"}"
        );
    }

    private static Path tempDir(String prefix) {
        try {
            return Files.createTempDirectory(prefix);
        } catch (IOException exception) {
            throw new IllegalStateException("failed to create temp directory", exception);
        }
    }

    private static ByteArrayResource sanitizedSchemaScript() {
        try {
            String schema = Files.readString(Path.of("db/schema/agentx_platform_v1.sql"), StandardCharsets.UTF_8);
            String sanitized = schema
                    .replaceFirst("(?is)create database if not exists agentx_platform\\s+character set utf8mb4\\s+collate utf8mb4_0900_ai_ci;\\s*", "")
                    .replaceFirst("(?im)^use agentx_platform;\\s*", "");
            return new ByteArrayResource(sanitized.getBytes(StandardCharsets.UTF_8));
        } catch (IOException exception) {
            throw new IllegalStateException("failed to read schema script", exception);
        }
    }
}
