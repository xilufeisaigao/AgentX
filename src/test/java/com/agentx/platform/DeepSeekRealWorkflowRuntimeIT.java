package com.agentx.platform;

import com.agentx.platform.domain.catalog.model.AgentDefinition;
import com.agentx.platform.domain.execution.model.GitWorkspace;
import com.agentx.platform.domain.execution.model.GitWorkspaceStatus;
import com.agentx.platform.domain.execution.model.TaskRunStatus;
import com.agentx.platform.domain.flow.model.WorkflowRunStatus;
import com.agentx.platform.domain.intake.model.RequirementStatus;
import com.agentx.platform.domain.intake.model.Ticket;
import com.agentx.platform.domain.intake.model.TicketStatus;
import com.agentx.platform.domain.planning.model.WorkTask;
import com.agentx.platform.domain.planning.model.WorkTaskStatus;
import com.agentx.platform.domain.shared.model.ActorRef;
import com.agentx.platform.domain.shared.model.ActorType;
import com.agentx.platform.domain.shared.model.WriteScope;
import com.agentx.platform.runtime.agentkernel.architect.ArchitectConversationAgent;
import com.agentx.platform.runtime.agentkernel.architect.ArchitectDecision;
import com.agentx.platform.runtime.agentkernel.architect.ArchitectDecisionType;
import com.agentx.platform.runtime.agentkernel.architect.PlanningGraphSpec;
import com.agentx.platform.runtime.agentkernel.coding.CodingAgentDecision;
import com.agentx.platform.runtime.agentkernel.coding.CodingConversationAgent;
import com.agentx.platform.runtime.agentkernel.coding.CodingDecisionType;
import com.agentx.platform.runtime.agentkernel.model.AgentModelProperties;
import com.agentx.platform.runtime.agentkernel.model.DeepSeekOpenAiCompatibleGateway;
import com.agentx.platform.runtime.agentkernel.model.StructuredModelResult;
import com.agentx.platform.runtime.agentkernel.requirement.RequirementAgentDecision;
import com.agentx.platform.runtime.agentkernel.requirement.RequirementConversationAgent;
import com.agentx.platform.runtime.agentkernel.requirement.RequirementConversationContext;
import com.agentx.platform.runtime.agentkernel.requirement.RequirementDecisionType;
import com.agentx.platform.runtime.agentkernel.verify.VerifyDecision;
import com.agentx.platform.runtime.agentkernel.verify.VerifyDecisionAgent;
import com.agentx.platform.runtime.agentkernel.verify.VerifyDecisionType;
import com.agentx.platform.runtime.application.workflow.AnswerTicketCommand;
import com.agentx.platform.runtime.application.workflow.ConfirmRequirementDocCommand;
import com.agentx.platform.runtime.application.workflow.FixedCodingWorkflowUseCase;
import com.agentx.platform.runtime.application.workflow.StartCodingWorkflowCommand;
import com.agentx.platform.runtime.application.workflow.TaskTemplateCatalog;
import com.agentx.platform.runtime.application.workflow.WorkflowScenario;
import com.agentx.platform.runtime.application.workflow.WorkflowRuntimeSnapshot;
import com.agentx.platform.runtime.context.CompiledContextPack;
import com.agentx.platform.runtime.context.ContextPackType;
import com.agentx.platform.runtime.context.ContextScope;
import com.agentx.platform.runtime.context.FactBundle;
import com.agentx.platform.runtime.context.RetrievalBundle;
import com.agentx.platform.runtime.context.RetrievalSnippet;
import com.agentx.platform.runtime.evaluation.EvalEvidenceBundle;
import com.agentx.platform.runtime.evaluation.EvalScenario;
import com.agentx.platform.runtime.evaluation.WorkflowEvalCenter;
import com.agentx.platform.runtime.evaluation.WorkflowEvalTraceCollector;
import com.agentx.platform.runtime.tooling.ToolCall;
import com.agentx.platform.runtime.tooling.ToolCatalogEntry;
import com.agentx.platform.domain.execution.port.ExecutionStore;
import com.agentx.platform.support.TestGitRepoHelper;
import com.agentx.platform.support.TestStackProfiles;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Assumptions;
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
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
class DeepSeekRealWorkflowRuntimeIT {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("agentx_platform");

    private static final Path SMOKE_ROOT = Path.of(
                    "artifacts",
                    "evaluation-runs",
                    "real-llm-smoke-" + System.currentTimeMillis()
            )
            .toAbsolutePath()
            .normalize();
    private static final Path REPO_ROOT = SMOKE_ROOT.resolve("repo");
    private static final Path WORKSPACE_ROOT = SMOKE_ROOT.resolve("workspaces");
    private static final Path ARTIFACT_ROOT = SMOKE_ROOT.resolve("artifacts");
    private static final Path EXPORT_ROOT = SMOKE_ROOT.resolve("exported-commits");
    private static final Path REVIEW_BUNDLE_ROOT = SMOKE_ROOT.resolve("review-bundle");
    private static final Path EVAL_ARTIFACT_ROOT = SMOKE_ROOT.resolve("reports");

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
        registry.add("agentx.platform.runtime.blocking-timeout", () -> "PT180S");
        registry.add("agentx.platform.evaluation.artifact-root", EVAL_ARTIFACT_ROOT::toString);
    }

    @Autowired
    private FixedCodingWorkflowUseCase workflowUseCase;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private ExecutionStore executionStore;

    @Autowired
    private WorkflowEvalCenter workflowEvalCenter;

    @Autowired
    private WorkflowEvalTraceCollector workflowEvalTraceCollector;

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
        TestGitRepoHelper.deleteRecursively(SMOKE_ROOT);
        Files.createDirectories(SMOKE_ROOT);
        TestGitRepoHelper.resetFixtureRepository(REPO_ROOT);
        seedStudentFixtureRepository(REPO_ROOT);
        TestGitRepoHelper.cleanDirectory(WORKSPACE_ROOT);
        Files.createDirectories(ARTIFACT_ROOT);
        Files.createDirectories(EXPORT_ROOT);
        Files.createDirectories(REVIEW_BUNDLE_ROOT);
        Files.createDirectories(EVAL_ARTIFACT_ROOT);
        try (Connection connection = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(connection, sanitizedSchemaScript());
        }
        reset(requirementConversationAgent, architectConversationAgent, codingConversationAgent, verifyDecisionAgent);
    }

    @Test
    void shouldRunRealDeepSeekWorkflowAndExportGeneratedCode() throws Exception {
        String apiKey = System.getenv("AGENTX_DEEPSEEK_API_KEY");
        boolean smokeEnabled = Boolean.parseBoolean(System.getProperty("agentx.llm.smoke", "false"))
                || Boolean.parseBoolean(System.getenv("AGENTX_LLM_SMOKE"));
        Assumptions.assumeTrue(smokeEnabled);
        Assumptions.assumeTrue(apiKey != null && !apiKey.isBlank());

        RealAgents realAgents = realAgents(apiKey);
        SmokeEvaluationPlan plan = evaluateRealOutputs(realAgents);
        writeJson(ARTIFACT_ROOT.resolve("evaluation-plan.json"), plan.asMap());
        configureWorkflowMocks(plan);

        String workflowRunId = workflowUseCase.start(new StartCodingWorkflowCommand(
                "Student Management Real Smoke",
                "学生管理系统",
                "做一个学生管理系统",
                TestStackProfiles.DEFAULT_PROFILE_ID,
                new ActorRef(ActorType.HUMAN, "real-smoke-user"),
                false,
                WorkflowScenario.defaultScenario()
        ));

        WorkflowRuntimeSnapshot firstStableSnapshot = workflowUseCase.runUntilStable(workflowRunId);
        WorkflowRuntimeSnapshot reviewSnapshot = firstStableSnapshot;
        if (plan.requirementFirst().selected().value().decision() == RequirementDecisionType.NEED_INPUT) {
            assertThat(firstStableSnapshot.workflowRun().status()).isEqualTo(WorkflowRunStatus.WAITING_ON_HUMAN);
            assertThat(firstStableSnapshot.requirementDoc()).isEmpty();
            String ticketId = firstStableSnapshot.tickets().stream()
                    .filter(ticket -> ticket.status() == TicketStatus.OPEN && ticket.assignee().type() == ActorType.HUMAN)
                    .map(Ticket::ticketId)
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException("expected an open human clarification ticket"));
            workflowUseCase.answerTicket(new AnswerTicketCommand(
                    ticketId,
                    plan.requirementAnswer(),
                    new ActorRef(ActorType.HUMAN, "real-smoke-user")
            ));
            reviewSnapshot = workflowUseCase.runUntilStable(workflowRunId);
        }

        assertThat(reviewSnapshot.workflowRun().status()).isEqualTo(WorkflowRunStatus.WAITING_ON_HUMAN);
        assertThat(reviewSnapshot.requirementDoc()).isPresent();
        assertThat(reviewSnapshot.requirementDoc().orElseThrow().status()).isEqualTo(RequirementStatus.IN_REVIEW);

        workflowUseCase.confirmRequirementDoc(new ConfirmRequirementDocCommand(
                reviewSnapshot.requirementDoc().orElseThrow().docId(),
                reviewSnapshot.requirementDoc().orElseThrow().currentVersion(),
                new ActorRef(ActorType.HUMAN, "real-smoke-user")
        ));

        WorkflowRuntimeSnapshot completedSnapshot = workflowUseCase.runUntilStable(workflowRunId);

        assertThat(completedSnapshot.workflowRun().status()).isEqualTo(WorkflowRunStatus.COMPLETED);
        assertThat(completedSnapshot.tasks()).isNotEmpty();
        assertThat(completedSnapshot.tasks()).allMatch(task -> task.status() == WorkTaskStatus.DONE);
        assertThat(completedSnapshot.taskRuns()).allMatch(run -> run.status() == TaskRunStatus.SUCCEEDED);
        assertThat(completedSnapshot.workspaces()).allMatch(workspace ->
                workspace.status() == GitWorkspaceStatus.CLEANED || workspace.status() == GitWorkspaceStatus.MERGED
        );

        Map<String, Path> exportedSnapshots = exportWorkspaceSnapshots(completedSnapshot);
        Path reviewBundle = buildReviewBundle(completedSnapshot, exportedSnapshots);
        Map<String, Object> workflowResult = workflowResultMap(plan, completedSnapshot, reviewBundle, exportedSnapshots);
        writeJson(ARTIFACT_ROOT.resolve("workflow-result.json"), workflowResult);

        Map<String, String> artifactRefs = new LinkedHashMap<>();
        artifactRefs.put("evaluationPlan", ARTIFACT_ROOT.resolve("evaluation-plan.json").toString());
        artifactRefs.put("workflowResult", ARTIFACT_ROOT.resolve("workflow-result.json").toString());
        artifactRefs.put("reviewBundle", reviewBundle.toString());
        exportedSnapshots.forEach((taskId, path) -> artifactRefs.put("exportedSnapshot:" + taskId, path.toString()));

        workflowEvalCenter.generateWorkflowReport(
                studentManagementEvalScenario(),
                new EvalEvidenceBundle(
                        completedSnapshot,
                        taskRunEventsByRun(completedSnapshot),
                        workflowEvalTraceCollector.listContextArtifacts(workflowRunId),
                        Map.of(
                                "evaluationPlan", plan.asMap(),
                                "workflowResult", workflowResult
                        ),
                        artifactRefs
                )
        );
        workflowEvalTraceCollector.clearWorkflow(workflowRunId);
    }

    private void configureWorkflowMocks(SmokeEvaluationPlan plan) {
        when(requirementConversationAgent.evaluate(any(), any()))
                .thenReturn(plan.requirementFirst().selected())
                .thenReturn(plan.requirementSecond().selected());
        when(architectConversationAgent.evaluate(any(), any()))
                .thenReturn(plan.architect().selected());
        when(codingConversationAgent.evaluate(any(), any(), any()))
                .thenAnswer(invocation -> nextCodingDecision(plan, invocation.getArgument(1, CompiledContextPack.class)));
        when(verifyDecisionAgent.evaluate(any(), any()))
                .thenAnswer(invocation -> plan.verify().selected());
    }

    private StructuredModelResult<CodingAgentDecision> nextCodingDecision(
            SmokeEvaluationPlan plan,
            CompiledContextPack contextPack
    ) {
        TaskFactView task = taskFactView(contextPack);
        String runId = contextPack.scope().runId();
        if (runId == null || runId.isBlank()) {
            throw new IllegalStateException("coding context is missing runId");
        }
        Deque<StructuredModelResult<CodingAgentDecision>> decisions = plan.codingQueues()
                .computeIfAbsent(runId, ignored -> buildCodingQueue(task, plan));
        StructuredModelResult<CodingAgentDecision> decision = decisions.pollFirst();
        if (decision == null) {
            throw new IllegalStateException("coding decision queue exhausted for run " + runId + " task " + task.taskId());
        }
        return decision;
    }

    private Deque<StructuredModelResult<CodingAgentDecision>> buildCodingQueue(
            TaskFactView task,
            SmokeEvaluationPlan plan
    ) {
        Deque<StructuredModelResult<CodingAgentDecision>> queue = new ArrayDeque<>();
        NodeSelection<CodingAgentDecision> realCodingSelection = task.title().contains("回归测试")
                ? plan.codingTests()
                : plan.codingImplementation();
        if (realCodingSelection.useReal()) {
            queue.add(realCodingSelection.selected());
        }
        if (task.title().contains("回归测试")) {
            queue.add(fallbackCodingDecision(
                    "manual-test-write",
                    "tool-filesystem",
                    "write_file",
                    Map.of(
                            "path", "src/test/java/com/example/student/StudentServiceTest.java",
                            "content", studentServiceTestContent()
                    ),
                    "write student service regression tests"
            ));
            queue.add(fallbackCodingDecision(
                    "manual-test-marker",
                    "tool-filesystem",
                    "write_file",
                    Map.of(
                            "path", markerPath(task),
                            "content", "workflow=student-management-real-smoke\ntask=" + task.taskId() + "\nphase=test\n"
                    ),
                    "write runtime marker for test task"
            ));
        } else {
            queue.add(fallbackCodingDecision(
                    "manual-impl-student",
                    "tool-filesystem",
                    "write_file",
                    Map.of(
                            "path", "src/main/java/com/example/student/Student.java",
                            "content", studentRecordContent()
                    ),
                    "write student record"
            ));
            queue.add(fallbackCodingDecision(
                    "manual-impl-service",
                    "tool-filesystem",
                    "write_file",
                    Map.of(
                            "path", "src/main/java/com/example/student/StudentService.java",
                            "content", studentServiceContent()
                    ),
                    "write student service"
            ));
            queue.add(fallbackCodingDecision(
                    "manual-impl-controller",
                    "tool-filesystem",
                    "write_file",
                    Map.of(
                            "path", "src/main/java/com/example/student/StudentController.java",
                            "content", studentControllerContent()
                    ),
                    "write student controller facade"
            ));
            queue.add(fallbackCodingDecision(
                    "manual-impl-marker",
                    "tool-filesystem",
                    "write_file",
                    Map.of(
                            "path", markerPath(task),
                            "content", "workflow=student-management-real-smoke\ntask=" + task.taskId() + "\nphase=implementation\n"
                    ),
                    "write runtime marker for implementation task"
            ));
        }
        queue.add(new StructuredModelResult<>(
                new CodingAgentDecision(
                        CodingDecisionType.DELIVER,
                        null,
                        null,
                        null,
                        "deliver generated student management files"
                ),
                "manual-fallback",
                "manual",
                "{\"decisionType\":\"DELIVER\"}"
        ));
        return queue;
    }

    private RealAgents realAgents(String apiKey) {
        AgentModelProperties properties = new AgentModelProperties();
        properties.getDeepseek().setApiKey(apiKey);
        properties.setTimeout(Duration.ofSeconds(60));
        properties.setMaxRetries(2);
        DeepSeekOpenAiCompatibleGateway gateway = new DeepSeekOpenAiCompatibleGateway(properties, objectMapper);
        return new RealAgents(
                new RequirementConversationAgent(gateway, objectMapper),
                new ArchitectConversationAgent(gateway, new TaskTemplateCatalog()),
                new CodingConversationAgent(gateway, TestStackProfiles.registry()),
                new VerifyDecisionAgent(gateway, TestStackProfiles.registry()),
                agent("requirement-agent", "Requirement Agent"),
                agent("architect-agent", "Architect Agent"),
                agent("coding-agent-java", "Coding Agent"),
                agent("verify-agent-java", "Verify Agent")
        );
    }

    private AgentDefinition agent(String agentId, String displayName) {
        return new AgentDefinition(
                agentId,
                displayName,
                "real smoke validation",
                "SYSTEM",
                "in-process",
                "deepseek-chat",
                4,
                false,
                false,
                true,
                true
        );
    }

    private StructuredModelResult<CodingAgentDecision> fallbackCodingDecision(
            String callId,
            String toolId,
            String operation,
            Map<String, Object> arguments,
            String summary
    ) {
        return new StructuredModelResult<>(
                new CodingAgentDecision(
                        CodingDecisionType.TOOL_CALL,
                        new ToolCall(callId, toolId, operation, arguments, summary),
                        null,
                        null,
                        summary
                ),
                "manual-fallback",
                "manual",
                "{\"decisionType\":\"TOOL_CALL\"}"
        );
    }

    private SmokeEvaluationPlan evaluateRealOutputs(RealAgents realAgents) throws Exception {
        String initialPrompt = "做一个学生管理系统";
        String requirementAnswer = """
                这是一个最小后端版本，不需要前端。
                需要支持学生新增、查询、更新、删除。
                学生字段至少包含学号、姓名、年级、邮箱。
                学号、姓名和邮箱必填，邮箱格式必须合法。
                删除后再次查询应返回不存在。
                需要补齐最小回归测试，重点覆盖新增、更新、删除和邮箱校验。
                可以先用内存存储，不要求接数据库。
                """;

        RequirementConversationContext firstRequirementContext = new RequirementConversationContext(
                "workflow-real-smoke-1",
                "学生管理系统",
                "学生管理系统",
                initialPrompt,
                Optional.empty(),
                List.of(),
                "SEED",
                initialPrompt
        );
        StructuredModelResult<RequirementAgentDecision> firstRequirement = realAgents.requirementAgent().evaluate(
                realAgents.requirementDefinition(),
                firstRequirementContext
        );
        writeJson(ARTIFACT_ROOT.resolve("requirement-first-real.json"), rawDecisionMap(firstRequirement));
        NodeSelection<RequirementAgentDecision> selectedFirstRequirement = selectFirstRequirement(firstRequirement);

        RequirementConversationContext secondRequirementContext = new RequirementConversationContext(
                "workflow-real-smoke-1",
                "学生管理系统",
                "学生管理系统",
                initialPrompt,
                Optional.empty(),
                List.of(new RequirementConversationContext.RequirementTicketTurn(
                        "ticket-real-smoke-1",
                        "CLARIFICATION",
                        "DISCOVERY",
                        "补齐学生管理系统需求",
                        null,
                        selectedFirstRequirement.selected().value().gaps(),
                        selectedFirstRequirement.selected().value().questions(),
                        requirementAnswer
                )),
                "DISCOVERY",
                requirementAnswer
        );
        StructuredModelResult<RequirementAgentDecision> secondRequirement = realAgents.requirementAgent().evaluate(
                realAgents.requirementDefinition(),
                secondRequirementContext
        );
        writeJson(ARTIFACT_ROOT.resolve("requirement-second-real.json"), rawDecisionMap(secondRequirement));
        NodeSelection<RequirementAgentDecision> selectedSecondRequirement = selectSecondRequirement(secondRequirement);

        StructuredModelResult<RequirementAgentDecision> confirmedRequirement = selectedSecondRequirement.selected();
        CompiledContextPack architectPack = architectContextPack(confirmedRequirement.value().draftTitle(), confirmedRequirement.value().draftContent());
        StructuredModelResult<ArchitectDecision> architectDecision = realAgents.architectAgent().evaluate(
                realAgents.architectDefinition(),
                architectPack
        );
        writeJson(ARTIFACT_ROOT.resolve("architect-real.json"), rawDecisionMap(architectDecision));
        NodeSelection<ArchitectDecision> selectedArchitect = selectArchitect(architectDecision);

        CompiledContextPack codingImplementationPack = codingContextPack(
                "task-student-impl",
                "实现学生管理后端骨架",
                "实现学生 CRUD 的最小后端骨架",
                List.of("src/main/java", "src/test/java"),
                confirmedRequirement.value().draftContent()
        );
        StructuredModelResult<CodingAgentDecision> codingImplementation = realAgents.codingAgent().evaluate(
                realAgents.codingDefinition(),
                codingImplementationPack,
                "no prior turns"
        );
        writeJson(ARTIFACT_ROOT.resolve("coding-implementation-real.json"), rawDecisionMap(codingImplementation));
        NodeSelection<CodingAgentDecision> selectedCodingImplementation = selectCoding(
                codingImplementation,
                List.of("src/main/java", "src/test/java")
        );

        CompiledContextPack codingTestsPack = codingContextPack(
                "task-student-tests",
                "补齐学生管理回归测试",
                "补齐围绕学生 CRUD 的最小回归测试",
                List.of("src/test/java"),
                confirmedRequirement.value().draftContent()
        );
        StructuredModelResult<CodingAgentDecision> codingTests = realAgents.codingAgent().evaluate(
                realAgents.codingDefinition(),
                codingTestsPack,
                "no prior turns"
        );
        writeJson(ARTIFACT_ROOT.resolve("coding-tests-real.json"), rawDecisionMap(codingTests));
        NodeSelection<CodingAgentDecision> selectedCodingTests = selectCoding(codingTests, List.of("src/test/java"));

        CompiledContextPack verifyPack = verifyContextPack(confirmedRequirement.value().draftContent());
        StructuredModelResult<VerifyDecision> verifyDecision = realAgents.verifyAgent().evaluate(
                realAgents.verifyDefinition(),
                verifyPack
        );
        writeJson(ARTIFACT_ROOT.resolve("verify-real.json"), rawDecisionMap(verifyDecision));
        NodeSelection<VerifyDecision> selectedVerify = selectVerify(verifyDecision);

        return new SmokeEvaluationPlan(
                selectedFirstRequirement,
                selectedSecondRequirement,
                selectedArchitect,
                selectedCodingImplementation,
                selectedCodingTests,
                selectedVerify,
                requirementAnswer,
                new LinkedHashMap<>()
        );
    }

    private NodeSelection<RequirementAgentDecision> selectFirstRequirement(
            StructuredModelResult<RequirementAgentDecision> realDecision
    ) {
        RequirementAgentDecision decision = realDecision.value();
        if (decision.decision() == RequirementDecisionType.NEED_INPUT
                && (!decision.gaps().isEmpty() || !decision.questions().isEmpty())) {
            return NodeSelection.useReal(realDecision, "real requirement clarification was acceptable");
        }
        if (decision.decision() == RequirementDecisionType.DRAFT_READY
                && draftLooksDetailedEnough(decision.draftContent())) {
            return NodeSelection.useReal(realDecision, "real requirement draft was already detailed enough");
        }
        return NodeSelection.useFallback(
                realDecision,
                new StructuredModelResult<>(
                        new RequirementAgentDecision(
                                RequirementDecisionType.NEED_INPUT,
                                List.of("缺少清晰的数据字段、校验边界和最小验收说明"),
                                List.of(
                                        "学生记录至少需要哪些字段？",
                                        "新增和更新时要校验哪些必填项？",
                                        "最小回归测试希望覆盖哪些行为？"
                                ),
                                null,
                                null,
                                "manual clarification fallback"
                        ),
                        "manual-fallback",
                        "manual",
                        "{\"decision\":\"NEED_INPUT\"}"
                ),
                "real requirement first turn was too eager or too vague for a stable requirement review"
        );
    }

    private NodeSelection<RequirementAgentDecision> selectSecondRequirement(
            StructuredModelResult<RequirementAgentDecision> realDecision
    ) {
        RequirementAgentDecision decision = realDecision.value();
        if (decision.decision() == RequirementDecisionType.DRAFT_READY
                && draftLooksDetailedEnough(decision.draftContent())) {
            return NodeSelection.useReal(realDecision, "real requirement draft was acceptable");
        }
        return NodeSelection.useFallback(
                realDecision,
                new StructuredModelResult<>(
                        new RequirementAgentDecision(
                                RequirementDecisionType.DRAFT_READY,
                                List.of(),
                                List.of(),
                                "学生管理系统需求",
                                """
                                        目标：
                                        构建一个最小学生管理后端服务，用于维护学生基础信息。

                                        功能范围：
                                        1. 支持学生新增。
                                        2. 支持按学号查询单个学生。
                                        3. 支持查询全部学生列表。
                                        4. 支持更新学生姓名、年级和邮箱。
                                        5. 支持按学号删除学生。

                                        数据字段：
                                        - studentId：学号，唯一且必填。
                                        - name：姓名，必填。
                                        - grade：年级，选填但推荐保留。
                                        - email：邮箱，必填且必须满足基础格式校验。

                                        校验与行为约束：
                                        - 新增时 studentId、name、email 必填。
                                        - 更新时 studentId 不允许修改。
                                        - email 必须满足基本邮箱格式。
                                        - 删除成功后，再次查询该学生应返回不存在。
                                        - 当学号不存在时，查询、更新和删除都应返回明确的失败结果。

                                        非目标：
                                        - 本轮不接数据库，可先使用内存存储。
                                        - 本轮不做前端页面。

                                        测试要求：
                                        - 至少覆盖新增成功。
                                        - 至少覆盖邮箱校验失败。
                                        - 至少覆盖更新成功。
                                        - 至少覆盖删除后查询不到该学生。
                                        """,
                                "manual draft fallback"
                        ),
                        "manual-fallback",
                        "manual",
                        "{\"decision\":\"DRAFT_READY\"}"
                ),
                "real requirement second turn still did not produce a stable reviewable draft"
        );
    }

    private NodeSelection<ArchitectDecision> selectArchitect(StructuredModelResult<ArchitectDecision> realDecision) {
        ArchitectDecision decision = realDecision.value();
        if ((decision.decision() == ArchitectDecisionType.PLAN_READY || decision.decision() == ArchitectDecisionType.REPLAN_READY)
                && architectPlanLooksUsable(decision.planningGraph())) {
            return NodeSelection.useReal(realDecision, "real architect plan matched supported templates");
        }
        return NodeSelection.useFallback(
                realDecision,
                new StructuredModelResult<>(
                        new ArchitectDecision(
                                ArchitectDecisionType.PLAN_READY,
                                List.of(),
                                List.of(),
                                "split the student management work into implementation and regression tasks",
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
                                                        List.of(new WriteScope("src/main/java"), new WriteScope("src/test/java")),
                                                        "cap-java-backend-coding"
                                                ),
                                                new PlanningGraphSpec.TaskPlan(
                                                        "student-tests",
                                                        "student-core",
                                                        "补齐学生管理回归测试",
                                                        "补齐围绕学生 CRUD 的最小回归测试",
                                                        "java-backend-test",
                                                        List.of(new WriteScope("src/test/java")),
                                                        "cap-java-backend-coding"
                                                )
                                        ),
                                        List.of(new PlanningGraphSpec.DependencyPlan("student-tests", "student-impl"))
                                )
                        ),
                        "manual-fallback",
                        "manual",
                        "{\"decision\":\"PLAN_READY\"}"
                ),
                "real architect output did not stay within the supported task template catalog"
        );
    }

    private NodeSelection<CodingAgentDecision> selectCoding(
            StructuredModelResult<CodingAgentDecision> realDecision,
            List<String> writeScopes
    ) {
        CodingAgentDecision decision = realDecision.value();
        if (decision.decisionType() == CodingDecisionType.TOOL_CALL && decision.toolCall() != null) {
            ToolCall toolCall = decision.toolCall();
            if ("tool-filesystem".equals(toolCall.toolId())) {
                if ("list_directory".equals(toolCall.operation())) {
                    String path = String.valueOf(toolCall.arguments().getOrDefault("path", ""));
                    if (!path.isBlank() && !path.startsWith("/")) {
                        return NodeSelection.useReal(realDecision, "real coding turn chose a safe directory listing");
                    }
                }
                if ("grep_text".equals(toolCall.operation())) {
                    String query = String.valueOf(toolCall.arguments().getOrDefault("query", ""));
                    if (!query.isBlank()) {
                        return NodeSelection.useReal(realDecision, "real coding turn chose a safe search step");
                    }
                }
                if ("read_file".equals(toolCall.operation())) {
                    String path = String.valueOf(toolCall.arguments().getOrDefault("path", ""));
                    if (!path.isBlank() && !path.startsWith("/")) {
                        return NodeSelection.useReal(realDecision, "real coding turn chose a safe file read");
                    }
                }
                if ("write_file".equals(toolCall.operation())) {
                    String path = String.valueOf(toolCall.arguments().getOrDefault("path", ""));
                    if (!path.isBlank() && !path.startsWith("/") && withinWriteScope(path, writeScopes)) {
                        String content = String.valueOf(toolCall.arguments().getOrDefault("content", ""));
                        if (!content.isBlank() && content.length() > 120) {
                            return NodeSelection.useReal(realDecision, "real coding turn produced a valid in-scope write");
                        }
                    }
                }
            }
        }
        return NodeSelection.useFallback(
                realDecision,
                null,
                "real coding turn was not safe or useful enough to trust for the deterministic runtime push"
        );
    }

    private NodeSelection<VerifyDecision> selectVerify(StructuredModelResult<VerifyDecision> realDecision) {
        if (realDecision.value().decision() == VerifyDecisionType.PASS) {
            return NodeSelection.useReal(realDecision, "real verify decision accepted the deterministic pass evidence");
        }
        return NodeSelection.useFallback(
                realDecision,
                new StructuredModelResult<>(
                        new VerifyDecision(
                                VerifyDecisionType.PASS,
                                "manual verify fallback accepted the deterministic evidence",
                                null,
                                null
                        ),
                        "manual-fallback",
                        "manual",
                        "{\"decision\":\"PASS\"}"
                ),
                "real verify decision did not accept a deterministic pass context"
        );
    }

    private CompiledContextPack architectContextPack(String draftTitle, String draftContent) {
        Map<String, Object> facts = new LinkedHashMap<>();
        facts.put("workflow", Map.ofEntries(
                Map.entry("workflowRunId", "workflow-real-smoke-1"),
                Map.entry("title", "学生管理系统")
        ));
        facts.put("requirement", Map.ofEntries(
                Map.entry("docId", "req-real-smoke-1"),
                Map.entry("status", "CONFIRMED"),
                Map.entry("currentVersion", 1),
                Map.entry("confirmedVersion", 1),
                Map.entry("title", draftTitle),
                Map.entry("content", draftContent)
        ));
        facts.put("tickets", List.of());
        facts.put("planning", Map.of(
                "modules", List.of(),
                "tasks", List.of(),
                "dependencies", List.of()
        ));
        facts.put("runtimeAlerts", List.of());
        return contextPack(
                ContextPackType.ARCHITECT,
                ContextScope.workflow("workflow-real-smoke-1", "architect"),
                "REAL_SMOKE_ARCHITECT",
                facts,
                List.of(
                        snippet(
                                "repo-1",
                                "repo-code",
                                "pom.xml",
                                "student repo pom",
                                "The baseline repo already contains a Maven build and can accept plain Java domain code and JUnit tests.",
                                0.88,
                                List.of("pom.xml", "maven")
                        ),
                        snippet(
                                "repo-2",
                                "docs",
                                "README.md",
                                "student workflow notes",
                                "Keep the initial DAG conservative: one implementation task and one regression test task.",
                                0.76,
                                List.of("DAG", "task")
                        )
                )
        );
    }

    private CompiledContextPack codingContextPack(
            String taskId,
            String title,
            String objective,
            List<String> writeScopes,
            String requirementContent
    ) {
        Map<String, Object> facts = new LinkedHashMap<>();
        facts.put("task", Map.ofEntries(
                Map.entry("taskId", taskId),
                Map.entry("title", title),
                Map.entry("objective", objective),
                Map.entry("taskTemplateId", title.contains("测试") ? "java-backend-test" : "java-backend-code"),
                Map.entry("capabilityPackId", "cap-java-backend-coding"),
                Map.entry("writeScopes", writeScopes)
        ));
        facts.put("workflow", Map.ofEntries(
                Map.entry("workflowRunId", "workflow-real-smoke-1"),
                Map.entry("title", "学生管理系统")
        ));
        facts.put("requirementSlice", Map.of("summary", requirementContent));
        facts.put("upstreamDeliveries", List.of());
        facts.put("openBlockers", List.of());
        facts.put("runtimePlatform", "LINUX_CONTAINER");
        facts.put("shellFamily", "POSIX_SH");
        facts.put("workspaceRoot", "/workspace");
        facts.put("repoRoot", "/workspace");
        facts.put("explorationRoots", List.of(".", "src/main/java", "src/test/java"));
        facts.put("workspaceReadPolicy", "BROAD_WORKSPACE");
        facts.put("runtimeGuardrails", Map.ofEntries(
                Map.entry("runtimePlatform", "LINUX_CONTAINER"),
                Map.entry("shellFamily", "POSIX_SH"),
                Map.entry("workspaceRoot", "/workspace"),
                Map.entry("repoRoot", "/workspace"),
                Map.entry("explorationRoots", List.of(".", "src/main/java", "src/test/java")),
                Map.entry("workspaceReadPolicy", "BROAD_WORKSPACE"),
                Map.entry("toolCatalog", List.of(
                        new ToolCatalogEntry("tool-filesystem", "Filesystem", "DIRECT", List.of("read_file", "read_range", "head_file", "tail_file", "list_directory", "glob_files", "grep_text", "write_file", "delete_file"), "schema://tool-filesystem", ""),
                        new ToolCatalogEntry("tool-shell", "Shell", "DIRECT", List.of("run_command", "run_exploration_command"), "schema://tool-shell", ""),
                        new ToolCatalogEntry("tool-git", "Git", "DIRECT", List.of("git_status", "git_diff_stat", "git_head"), "schema://tool-git", "")
                )),
                Map.entry("allowedCommandCatalog", Map.ofEntries(
                        Map.entry("show-marker", List.of("sh", "-lc", "test -f \"$MARKER_FILE\" && cat \"$MARKER_FILE\" || true")),
                        Map.entry("git-commit-delivery", List.of("sh", "-lc", "git add -A && git commit -m smoke"))
                )),
                Map.entry("explorationCommandCatalog", Map.ofEntries(
                        Map.entry("grep-text", Map.of("description", "Readonly recursive grep inside the workspace.")),
                        Map.entry("read-range", Map.of("description", "Readonly line-range read for a file."))
                )),
                Map.entry("writeScopes", writeScopes)
        ));
        facts.put("latestRunFacts", Map.ofEntries(
                Map.entry("runId", "run-" + taskId),
                Map.entry("status", "RUNNING"),
                Map.entry("attempt", 1)
        ));
        return contextPack(
                ContextPackType.CODING,
                ContextScope.task("workflow-real-smoke-1", taskId, "run-" + taskId, "coding", null),
                "REAL_SMOKE_CODING",
                facts,
                List.of()
        );
    }

    private CompiledContextPack verifyContextPack(String requirementContent) {
        Map<String, Object> facts = new LinkedHashMap<>();
        facts.put("task", Map.ofEntries(
                Map.entry("taskId", "task-student-review"),
                Map.entry("title", "验证学生管理交付"),
                Map.entry("objective", "确认学生 CRUD 骨架和最小回归测试都已到位")
        ));
        facts.put("requirementSlice", Map.of("summary", requirementContent));
        facts.put("deterministicVerifyResult", Map.ofEntries(
                Map.entry("status", "PASSED"),
                Map.entry("commands", List.of("git status --short")),
                Map.entry("changedFiles", List.of(
                        "src/main/java/com/example/student/Student.java",
                        "src/main/java/com/example/student/StudentService.java",
                        "src/main/java/com/example/student/StudentController.java",
                        "src/test/java/com/example/student/StudentServiceTest.java"
                ))
        ));
        facts.put("deliveryEvidence", Map.ofEntries(
                Map.entry("mergeCandidateCommit", "real-smoke-commit"),
                Map.entry("diffSummary", "Added student domain/service/controller and regression tests.")
        ));
        facts.put("tickets", List.of());
        return contextPack(
                ContextPackType.VERIFY,
                ContextScope.task("workflow-real-smoke-1", "task-student-review", "run-student-review", "verify", null),
                "REAL_SMOKE_VERIFY",
                facts,
                List.of(
                        snippet(
                                "verify-1",
                                "overlay-code",
                                "src/main/java/com/example/student/StudentController.java",
                                "student changed files",
                                "Changed files include Student, StudentService, StudentController and StudentServiceTest.",
                                0.92,
                                List.of("Student", "StudentService", "StudentController", "StudentServiceTest")
                        )
                )
        );
    }

    private CompiledContextPack contextPack(
            ContextPackType packType,
            ContextScope scope,
            String triggerType,
            Map<String, Object> facts,
            List<RetrievalSnippet> retrieval
    ) {
        LocalDateTime compiledAt = LocalDateTime.now();
        String fingerprint = packType.name().toLowerCase(Locale.ROOT) + "-real-smoke-" + scope.workflowRunId();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("packType", packType.name());
        payload.put("scope", scope);
        payload.put("triggerType", triggerType);
        payload.put("sourceFingerprint", fingerprint);
        payload.put("compiledAt", compiledAt.toString());
        payload.put("facts", facts);
        payload.put("retrieval", retrieval);
        return new CompiledContextPack(
                packType,
                scope,
                fingerprint,
                "real-smoke://" + packType.name().toLowerCase(Locale.ROOT) + "/" + scope.workflowRunId(),
                toJson(payload),
                new FactBundle(facts),
                new RetrievalBundle(retrieval),
                compiledAt
        );
    }

    private RetrievalSnippet snippet(
            String snippetId,
            String sourceType,
            String sourceRef,
            String title,
            String excerpt,
            double score,
            List<String> symbols
    ) {
        return new RetrievalSnippet(
                snippetId,
                sourceType,
                sourceRef,
                title,
                excerpt,
                score,
                symbols,
                Map.of("realSmoke", true)
        );
    }

    private TaskFactView taskFactView(CompiledContextPack contextPack) {
        Map<String, Object> task = objectMapper.convertValue(contextPack.factBundle().sections().getOrDefault("task", Map.of()), Map.class);
        String taskId = stringValue(task.get("taskId"), contextPack.scope().taskId());
        String title = stringValue(task.get("title"), taskId);
        List<String> writeScopes = normalizeWriteScopes(task.getOrDefault("writeScopes", List.of()));
        return new TaskFactView(taskId, title, writeScopes);
    }

    private List<String> normalizeWriteScopes(Object rawWriteScopes) {
        List<?> scopes = objectMapper.convertValue(rawWriteScopes, List.class);
        return scopes.stream()
                .map(scope -> {
                    if (scope instanceof Map<?, ?> map && map.get("path") != null) {
                        return String.valueOf(map.get("path"));
                    }
                    return String.valueOf(scope);
                })
                .toList();
    }

    private String markerPath(TaskFactView task) {
        String root = task.writeScopes().isEmpty() ? ".agentx-runtime" : sanitizePathSegment(task.writeScopes().get(0));
        return root + "/.agentx-" + sanitizePathSegment(task.taskId()) + ".txt";
    }

    private String sanitizePathSegment(String rawValue) {
        return rawValue.replace('\\', '/').replaceAll("[^a-zA-Z0-9._/-]", "-");
    }

    private boolean draftLooksDetailedEnough(String content) {
        if (content == null || content.isBlank()) {
            return false;
        }
        String normalized = content.toLowerCase(Locale.ROOT);
        return content.length() >= 180
                && normalized.contains("学号")
                && normalized.contains("邮箱")
                && normalized.contains("删除")
                && (normalized.contains("测试") || normalized.contains("回归"));
    }

    private boolean architectPlanLooksUsable(PlanningGraphSpec planningGraph) {
        if (planningGraph == null || planningGraph.tasks().size() < 2) {
            return false;
        }
        boolean hasCodeTask = false;
        boolean hasTestTask = false;
        for (PlanningGraphSpec.TaskPlan task : planningGraph.tasks()) {
            if (!List.of("java-backend-code", "java-backend-test", "docs-update", "config-or-schema-update")
                    .contains(task.taskTemplateId())) {
                return false;
            }
            if (!"cap-java-backend-coding".equals(task.capabilityPackId())) {
                return false;
            }
            if ("java-backend-code".equals(task.taskTemplateId())) {
                hasCodeTask = true;
            }
            if ("java-backend-test".equals(task.taskTemplateId())) {
                hasTestTask = true;
            }
        }
        return hasCodeTask && hasTestTask;
    }

    private boolean withinWriteScope(String path, List<String> writeScopes) {
        String normalizedPath = path.replace('\\', '/');
        return writeScopes.stream()
                .map(scope -> scope.replace('\\', '/'))
                .anyMatch(scope -> normalizedPath.equals(scope) || normalizedPath.startsWith(scope + "/"));
    }

    private Map<String, Path> exportWorkspaceSnapshots(WorkflowRuntimeSnapshot snapshot) throws IOException {
        Map<String, String> taskTitles = new LinkedHashMap<>();
        for (WorkTask task : snapshot.tasks()) {
            taskTitles.put(task.taskId(), task.title());
        }
        Map<String, Path> exports = new LinkedHashMap<>();
        for (GitWorkspace workspace : snapshot.workspaces()) {
            if (workspace.headCommit() == null || workspace.headCommit().isBlank()) {
                continue;
            }
            String taskId = workspace.taskId();
            String title = taskTitles.getOrDefault(taskId, taskId);
            Path exportDirectory = EXPORT_ROOT.resolve(sanitizeForFileName(taskId + "-" + title));
            exportCommit(REPO_ROOT, workspace.headCommit(), exportDirectory);
            exports.put(taskId, exportDirectory);
        }
        return exports;
    }

    private Path buildReviewBundle(WorkflowRuntimeSnapshot snapshot, Map<String, Path> exportedSnapshots) throws IOException {
        TestGitRepoHelper.deleteRecursively(REVIEW_BUNDLE_ROOT);
        Files.createDirectories(REVIEW_BUNDLE_ROOT);
        WorkTask implementationTask = snapshot.tasks().stream()
                .filter(task -> task.title().contains("后端骨架"))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("implementation task not found"));
        WorkTask testTask = snapshot.tasks().stream()
                .filter(task -> task.title().contains("回归测试"))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("test task not found"));

        copyTree(exportedSnapshots.get(implementationTask.taskId()), REVIEW_BUNDLE_ROOT);
        copyTree(exportedSnapshots.get(testTask.taskId()).resolve("src/test/java"), REVIEW_BUNDLE_ROOT.resolve("src/test/java"));
        deleteMarkerFiles(REVIEW_BUNDLE_ROOT);
        return REVIEW_BUNDLE_ROOT;
    }

    private void exportCommit(Path repoRoot, String commit, Path exportDirectory) throws IOException {
        TestGitRepoHelper.deleteRecursively(exportDirectory);
        Files.createDirectories(exportDirectory);
        List<String> files = gitOutput(repoRoot, List.of("git", "ls-tree", "-r", "--name-only", commit)).lines()
                .map(String::trim)
                .filter(line -> !line.isEmpty())
                .toList();
        for (String file : files) {
            if (file.contains(".git")) {
                continue;
            }
            String content = gitOutput(repoRoot, List.of("git", "show", commit + ":" + file));
            Path target = exportDirectory.resolve(file);
            Files.createDirectories(target.getParent());
            Files.writeString(target, content, StandardCharsets.UTF_8);
        }
    }

    private void copyTree(Path sourceRoot, Path targetRoot) throws IOException {
        if (sourceRoot == null || Files.notExists(sourceRoot)) {
            throw new IllegalStateException("missing source root for review bundle: " + sourceRoot);
        }
        try (var stream = Files.walk(sourceRoot)) {
            for (Path candidate : stream.sorted(Comparator.naturalOrder()).toList()) {
                Path relative = sourceRoot.relativize(candidate);
                Path target = targetRoot.resolve(relative);
                if (Files.isDirectory(candidate)) {
                    Files.createDirectories(target);
                } else {
                    Files.createDirectories(target.getParent());
                    Files.copy(candidate, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    private void deleteMarkerFiles(Path root) throws IOException {
        try (var stream = Files.walk(root)) {
            for (Path candidate : stream.toList()) {
                if (Files.isRegularFile(candidate) && candidate.getFileName().toString().startsWith(".agentx-")) {
                    Files.deleteIfExists(candidate);
                }
            }
        }
    }

    private Map<String, Object> workflowResultMap(
            SmokeEvaluationPlan plan,
            WorkflowRuntimeSnapshot snapshot,
            Path reviewBundle,
            Map<String, Path> exportedSnapshots
    ) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("workflowRunId", snapshot.workflowRun().workflowRunId());
        result.put("workflowStatus", snapshot.workflowRun().status().name());
        result.put("reviewBundle", reviewBundle.toString());
        Map<String, String> exportRefs = new LinkedHashMap<>();
        exportedSnapshots.forEach((taskId, path) -> exportRefs.put(taskId, path.toString()));
        result.put("exportedSnapshots", exportRefs);
        result.put("taskStatuses", snapshot.tasks().stream().map(task -> Map.of(
                "taskId", task.taskId(),
                "title", task.title(),
                "status", task.status().name()
        )).toList());
        result.put("nodeSelections", plan.asMap());
        return result;
    }

    private Map<String, Object> rawDecisionMap(StructuredModelResult<?> result) {
        return Map.ofEntries(
                Map.entry("provider", result.provider()),
                Map.entry("model", result.model()),
                Map.entry("parsedValue", result.value()),
                Map.entry("rawResponse", result.rawResponse())
        );
    }

    private EvalScenario studentManagementEvalScenario() {
        return new EvalScenario(
                "student-management-happy-path",
                "Student Management Real Smoke",
                "做一个学生管理系统",
                "真实 DeepSeek 全流程 smoke 的 workflow 评测场景。",
                List.of(
                        "学生新增",
                        "邮箱格式必须合法",
                        "回归测试",
                        "内存存储"
                ),
                List.of(
                        "Student.java",
                        "StudentService.java",
                        "StudentServiceTest.java"
                ),
                List.of(
                        "src/main/java",
                        "src/test/java",
                        "."
                ),
                null,
                true
        );
    }

    private Map<String, List<com.agentx.platform.domain.execution.model.TaskRunEvent>> taskRunEventsByRun(
            WorkflowRuntimeSnapshot snapshot
    ) {
        Map<String, List<com.agentx.platform.domain.execution.model.TaskRunEvent>> events = new LinkedHashMap<>();
        snapshot.taskRuns().forEach(run -> events.put(run.runId(), executionStore.listTaskRunEvents(run.runId())));
        return events;
    }

    private void writeJson(Path path, Object payload) throws IOException {
        Files.createDirectories(path.getParent());
        Files.writeString(path, objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(payload), StandardCharsets.UTF_8);
    }

    private String toJson(Map<String, Object> payload) {
        try {
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(payload);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("failed to serialize context pack", exception);
        }
    }

    private String gitOutput(Path workingDirectory, List<String> command) {
        try {
            Process process = new ProcessBuilder(command)
                    .directory(workingDirectory.toFile())
                    .start();
            String stdout = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            String stderr = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
            int exitCode = process.waitFor();
            if (exitCode != 0) {
                throw new IllegalStateException("git command failed: " + String.join(" ", command) + System.lineSeparator() + stderr);
            }
            return stdout;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("git command interrupted: " + String.join(" ", command), exception);
        } catch (IOException exception) {
            throw new IllegalStateException("failed to execute git command: " + String.join(" ", command), exception);
        }
    }

    private void seedStudentFixtureRepository(Path repoRoot) {
        try {
            Files.deleteIfExists(repoRoot.resolve("src/main/java/App.java"));
            Files.deleteIfExists(repoRoot.resolve("src/test/java/AppTest.java"));
            Files.createDirectories(repoRoot.resolve("src/main/java/com/example/student"));
            Files.createDirectories(repoRoot.resolve("src/test/java/com/example/student"));
            Files.writeString(repoRoot.resolve("pom.xml"), baselinePomXml(), StandardCharsets.UTF_8);
            Files.writeString(repoRoot.resolve("README.md"), """
                    # Student Management Fixture Repo

                    This repo is used by the real DeepSeek runtime smoke.
                    The runtime should generate a minimal Java student management skeleton and matching regression tests.
                    """, StandardCharsets.UTF_8);
            TestGitRepoHelper.run(repoRoot, List.of("git", "add", "."));
            TestGitRepoHelper.run(repoRoot, List.of("git", "commit", "-m", "seed student management fixture"));
        } catch (IOException exception) {
            throw new IllegalStateException("failed to seed student fixture repository", exception);
        }
    }

    private String stringValue(Object value, String defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        String stringValue = String.valueOf(value);
        return stringValue.isBlank() ? defaultValue : stringValue;
    }

    private String sanitizeForFileName(String rawValue) {
        return rawValue.replaceAll("[^a-zA-Z0-9._-]", "-");
    }

    private String baselinePomXml() {
        return """
                <project xmlns="http://maven.apache.org/POM/4.0.0"
                         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.example</groupId>
                    <artifactId>student-management-fixture</artifactId>
                    <version>1.0-SNAPSHOT</version>
                    <properties>
                        <maven.compiler.release>21</maven.compiler.release>
                        <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
                        <junit.version>5.10.2</junit.version>
                    </properties>
                    <dependencies>
                        <dependency>
                            <groupId>org.junit.jupiter</groupId>
                            <artifactId>junit-jupiter</artifactId>
                            <version>${junit.version}</version>
                            <scope>test</scope>
                        </dependency>
                    </dependencies>
                    <build>
                        <plugins>
                            <plugin>
                                <groupId>org.apache.maven.plugins</groupId>
                                <artifactId>maven-surefire-plugin</artifactId>
                                <version>3.2.5</version>
                                <configuration>
                                    <useModulePath>false</useModulePath>
                                </configuration>
                            </plugin>
                        </plugins>
                    </build>
                </project>
                """;
    }

    private String studentRecordContent() {
        return """
                package com.example.student;

                import java.util.Objects;
                import java.util.regex.Pattern;

                public record Student(
                        String studentId,
                        String name,
                        String grade,
                        String email
                ) {

                    private static final Pattern EMAIL_PATTERN =
                            Pattern.compile("^[A-Za-z0-9+_.-]+@[A-Za-z0-9.-]+$");

                    public Student {
                        if (studentId == null || studentId.isBlank()) {
                            throw new IllegalArgumentException("studentId must not be blank");
                        }
                        if (name == null || name.isBlank()) {
                            throw new IllegalArgumentException("name must not be blank");
                        }
                        if (email == null || email.isBlank()) {
                            throw new IllegalArgumentException("email must not be blank");
                        }
                        if (!EMAIL_PATTERN.matcher(email).matches()) {
                            throw new IllegalArgumentException("email must be valid");
                        }
                        grade = normalizeGrade(grade);
                    }

                    public static Student of(String studentId, String name, String grade, String email) {
                        return new Student(studentId, name, grade, email);
                    }

                    public Student updateProfile(String name, String grade, String email) {
                        return new Student(studentId, name, grade, email);
                    }

                    private static String normalizeGrade(String grade) {
                        return Objects.requireNonNullElse(grade, "").trim();
                    }
                }
                """;
    }

    private String studentServiceContent() {
        return """
                package com.example.student;

                import java.util.ArrayList;
                import java.util.LinkedHashMap;
                import java.util.List;
                import java.util.Map;
                import java.util.NoSuchElementException;
                import java.util.Optional;

                public class StudentService {

                    private final Map<String, Student> students = new LinkedHashMap<>();

                    public Student create(Student student) {
                        if (students.containsKey(student.studentId())) {
                            throw new IllegalArgumentException("student already exists: " + student.studentId());
                        }
                        students.put(student.studentId(), student);
                        return student;
                    }

                    public Optional<Student> findById(String studentId) {
                        return Optional.ofNullable(students.get(studentId));
                    }

                    public List<Student> list() {
                        return new ArrayList<>(students.values());
                    }

                    public Student update(String studentId, String name, String grade, String email) {
                        Student existing = students.get(studentId);
                        if (existing == null) {
                            throw new NoSuchElementException("student not found: " + studentId);
                        }
                        Student updated = existing.updateProfile(name, grade, email);
                        students.put(studentId, updated);
                        return updated;
                    }

                    public void delete(String studentId) {
                        if (students.remove(studentId) == null) {
                            throw new NoSuchElementException("student not found: " + studentId);
                        }
                    }
                }
                """;
    }

    private String studentControllerContent() {
        return """
                package com.example.student;

                import java.util.List;
                import java.util.Optional;

                public class StudentController {

                    private final StudentService studentService;

                    public StudentController(StudentService studentService) {
                        this.studentService = studentService;
                    }

                    public Student createStudent(String studentId, String name, String grade, String email) {
                        return studentService.create(Student.of(studentId, name, grade, email));
                    }

                    public Optional<Student> getStudent(String studentId) {
                        return studentService.findById(studentId);
                    }

                    public List<Student> listStudents() {
                        return studentService.list();
                    }

                    public Student updateStudent(String studentId, String name, String grade, String email) {
                        return studentService.update(studentId, name, grade, email);
                    }

                    public void deleteStudent(String studentId) {
                        studentService.delete(studentId);
                    }
                }
                """;
    }

    private String studentServiceTestContent() {
        return """
                package com.example.student;

                import org.junit.jupiter.api.Test;

                import static org.junit.jupiter.api.Assertions.assertEquals;
                import static org.junit.jupiter.api.Assertions.assertFalse;
                import static org.junit.jupiter.api.Assertions.assertThrows;
                import static org.junit.jupiter.api.Assertions.assertTrue;

                class StudentServiceTest {

                    @Test
                    void shouldCreateAndQueryStudent() {
                        StudentService service = new StudentService();

                        Student created = service.create(Student.of("S-1001", "Alice", "Grade 1", "alice@example.com"));

                        assertEquals("S-1001", created.studentId());
                        assertTrue(service.findById("S-1001").isPresent());
                    }

                    @Test
                    void shouldUpdateExistingStudent() {
                        StudentService service = new StudentService();
                        service.create(Student.of("S-1002", "Bob", "Grade 2", "bob@example.com"));

                        Student updated = service.update("S-1002", "Bobby", "Grade 3", "bobby@example.com");

                        assertEquals("Bobby", updated.name());
                        assertEquals("Grade 3", updated.grade());
                    }

                    @Test
                    void shouldDeleteStudentAndMakeLookupMiss() {
                        StudentService service = new StudentService();
                        service.create(Student.of("S-1003", "Cindy", "Grade 4", "cindy@example.com"));

                        service.delete("S-1003");

                        assertFalse(service.findById("S-1003").isPresent());
                    }

                    @Test
                    void shouldRejectInvalidEmail() {
                        assertThrows(IllegalArgumentException.class,
                                () -> Student.of("S-1004", "David", "Grade 5", "not-an-email"));
                    }
                }
                """;
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

    private record RealAgents(
            RequirementConversationAgent requirementAgent,
            ArchitectConversationAgent architectAgent,
            CodingConversationAgent codingAgent,
            VerifyDecisionAgent verifyAgent,
            AgentDefinition requirementDefinition,
            AgentDefinition architectDefinition,
            AgentDefinition codingDefinition,
            AgentDefinition verifyDefinition
    ) {
    }

    private record NodeSelection<T>(
            StructuredModelResult<T> selected,
            boolean useReal,
            String note,
            String fallbackReason,
            String realRawResponse
    ) {

        static <T> NodeSelection<T> useReal(StructuredModelResult<T> realDecision, String note) {
            return new NodeSelection<>(realDecision, true, note, null, realDecision.rawResponse());
        }

        static <T> NodeSelection<T> useFallback(
                StructuredModelResult<T> realDecision,
                StructuredModelResult<T> fallbackDecision,
                String fallbackReason
        ) {
            StructuredModelResult<T> selected = fallbackDecision == null ? realDecision : fallbackDecision;
            return new NodeSelection<>(
                    selected,
                    false,
                    "manual fallback selected",
                    fallbackReason,
                    realDecision.rawResponse()
            );
        }

        Map<String, Object> asMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("useReal", useReal);
            map.put("note", note);
            map.put("fallbackReason", fallbackReason);
            map.put("selectedProvider", selected.provider());
            map.put("selectedModel", selected.model());
            map.put("selectedValue", selected.value());
            map.put("realRawResponse", realRawResponse);
            return map;
        }
    }

    private record SmokeEvaluationPlan(
            NodeSelection<RequirementAgentDecision> requirementFirst,
            NodeSelection<RequirementAgentDecision> requirementSecond,
            NodeSelection<ArchitectDecision> architect,
            NodeSelection<CodingAgentDecision> codingImplementation,
            NodeSelection<CodingAgentDecision> codingTests,
            NodeSelection<VerifyDecision> verify,
            String requirementAnswer,
            Map<String, Deque<StructuredModelResult<CodingAgentDecision>>> codingQueues
    ) {

        Map<String, Object> asMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("requirementFirst", requirementFirst.asMap());
            map.put("requirementSecond", requirementSecond.asMap());
            map.put("architect", architect.asMap());
            map.put("codingImplementation", codingImplementation.asMap());
            map.put("codingTests", codingTests.asMap());
            map.put("verify", verify.asMap());
            map.put("requirementAnswer", requirementAnswer);
            return map;
        }
    }

    private record TaskFactView(
            String taskId,
            String title,
            List<String> writeScopes
    ) {
    }
}
