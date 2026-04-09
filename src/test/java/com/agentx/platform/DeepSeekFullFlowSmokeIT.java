package com.agentx.platform;

import com.agentx.platform.domain.catalog.model.AgentDefinition;
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
import com.agentx.platform.runtime.context.CompiledContextPack;
import com.agentx.platform.runtime.context.ContextPackType;
import com.agentx.platform.runtime.context.ContextScope;
import com.agentx.platform.runtime.context.FactBundle;
import com.agentx.platform.runtime.context.RetrievalBundle;
import com.agentx.platform.runtime.context.RetrievalSnippet;
import com.agentx.platform.runtime.application.workflow.TaskTemplateCatalog;
import com.agentx.platform.runtime.tooling.ToolCatalogEntry;
import com.agentx.platform.support.TestStackProfiles;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class DeepSeekFullFlowSmokeIT {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void shouldExerciseStudentManagementFlowAgainstRealDeepSeek() {
        String apiKey = System.getenv("AGENTX_DEEPSEEK_API_KEY");
        boolean smokeEnabled = Boolean.parseBoolean(System.getProperty("agentx.llm.smoke", "false"))
                || Boolean.parseBoolean(System.getenv("AGENTX_LLM_SMOKE"));
        Assumptions.assumeTrue(smokeEnabled);
        Assumptions.assumeTrue(apiKey != null && !apiKey.isBlank());

        AgentModelProperties properties = new AgentModelProperties();
        properties.getDeepseek().setApiKey(apiKey);
        properties.setTimeout(Duration.ofSeconds(45));
        properties.setMaxRetries(2);

        DeepSeekOpenAiCompatibleGateway gateway = new DeepSeekOpenAiCompatibleGateway(properties, objectMapper);
        RequirementConversationAgent requirementAgent = new RequirementConversationAgent(gateway, objectMapper);
        ArchitectConversationAgent architectAgent = new ArchitectConversationAgent(gateway, new TaskTemplateCatalog());
        CodingConversationAgent codingAgent = new CodingConversationAgent(gateway, TestStackProfiles.registry());
        VerifyDecisionAgent verifyAgent = new VerifyDecisionAgent(gateway, TestStackProfiles.registry());

        AgentDefinition requirementDefinition = agent("requirement-agent", "Requirement Agent", "deepseek-chat");
        AgentDefinition architectDefinition = agent("architect-agent", "Architect Agent", "deepseek-chat");
        AgentDefinition codingDefinition = agent("coding-agent-java", "Coding Agent", "deepseek-chat");
        AgentDefinition verifyDefinition = agent("verify-agent-java", "Verify Agent", "deepseek-chat");

        StructuredModelResult<RequirementAgentDecision> clarification = requirementAgent.evaluate(
                requirementDefinition,
                new RequirementConversationContext(
                        "workflow-student-smoke-1",
                        "学生管理系统",
                        "学生管理系统",
                        "做一个学生管理系统",
                        Optional.empty(),
                        List.of(),
                        "SEED",
                        "做一个学生管理系统"
                )
        );
        assertRequirementDecision(clarification.value());

        StructuredModelResult<RequirementAgentDecision> draft = requirementAgent.evaluate(
                requirementDefinition,
                new RequirementConversationContext(
                        "workflow-student-smoke-2",
                        "学生管理系统",
                        "学生管理系统",
                        """
                                构建一个最小学生管理系统。
                                需要支持学生信息新增、查询、更新和删除。
                                学生字段至少包含学号、姓名、年级、邮箱。
                                需要基础字段校验、错误提示和一组最小回归测试。
                                技术栈默认按 Java 后端服务处理。
                                """,
                        Optional.empty(),
                        List.of(
                                new RequirementConversationContext.RequirementTicketTurn(
                                        "ticket-student-clarification",
                                        "CLARIFICATION",
                                        "DISCOVERY",
                                        "补齐学生管理系统需求",
                                        null,
                                        List.of("缺少最小验收边界"),
                                        List.of("学生新增和更新至少要校验哪些字段？"),
                                        "学号、姓名和邮箱必填；邮箱格式必须合法；删除后查询不到该学生记录。"
                                )
                        ),
                        "DISCOVERY",
                        "学号、姓名和邮箱必填；邮箱格式必须合法；删除后查询不到该学生记录。"
                )
        );
        assertRequirementDecision(draft.value());

        String confirmedTitle = draft.value().decision() == RequirementDecisionType.DRAFT_READY
                ? draft.value().draftTitle()
                : "学生管理系统需求";
        String confirmedContent = draft.value().decision() == RequirementDecisionType.DRAFT_READY
                ? draft.value().draftContent()
                : """
                        学生管理系统需要支持学生新增、查询、更新和删除。
                        学生记录至少包含学号、姓名、年级、邮箱。
                        学号、姓名和邮箱必填，邮箱格式必须合法。
                        删除后查询不到该学生记录，并补齐最小回归测试。
                        """;

        CompiledContextPack architectPack = contextPack(
                ContextPackType.ARCHITECT,
                ContextScope.workflow("workflow-student-smoke-2", "architect"),
                "SMOKE_ARCHITECT",
                architectFacts(confirmedTitle, confirmedContent),
                List.of(
                        snippet(
                                "repo-1",
                                "repo-code",
                                "src/main/java/com/example/student/StudentController.java",
                                "StudentController",
                                "@RestController class StudentController { ... }",
                                0.91,
                                List.of("StudentController", "createStudent")
                        ),
                        snippet(
                                "doc-1",
                                "docs",
                                "docs/runtime/04-local-rag-and-code-indexing.md",
                                "Runtime RAG Notes",
                                "coding pack must retrieve changed classes, methods, tests and config around current task scope.",
                                0.77,
                                List.of("coding-pack", "overlay-index")
                        )
                )
        );

        StructuredModelResult<ArchitectDecision> architect = architectAgent.evaluate(architectDefinition, architectPack);
        assertArchitectDecision(architect.value());

        PlanningGraphSpec.TaskPlan plannedTask = firstPlannedTask(architect.value().planningGraph())
                .orElseGet(this::fallbackTaskPlan);

        List<String> writeScopes = plannedTask.writeScopes().isEmpty()
                ? List.of("src/main/java")
                : plannedTask.writeScopes().stream().map(WriteScope::path).toList();

        CompiledContextPack codingPack = contextPack(
                ContextPackType.CODING,
                ContextScope.task("workflow-student-smoke-2", "task-student-impl", "run-student-1", "coding", null),
                "SMOKE_CODING",
                codingFacts(plannedTask, confirmedContent, writeScopes),
                List.of()
        );

        StructuredModelResult<CodingAgentDecision> codingFirstTurn = codingAgent.evaluate(
                codingDefinition,
                codingPack,
                "no prior turns"
        );
        assertCodingDecision(codingFirstTurn.value(), writeScopes);

        StructuredModelResult<CodingAgentDecision> codingSecondTurn = codingAgent.evaluate(
                codingDefinition,
                codingPack,
                "wrote file src/main/java/com/example/student/StudentService.java and prepared CRUD endpoints"
        );
        assertCodingDecision(codingSecondTurn.value(), writeScopes);

        CompiledContextPack verifyPack = contextPack(
                ContextPackType.VERIFY,
                ContextScope.task("workflow-student-smoke-2", "task-student-verify", "run-student-verify-1", "verify", null),
                "SMOKE_VERIFY",
                verifyFacts(plannedTask, confirmedContent),
                List.of(
                        snippet(
                                "verify-1",
                                "overlay-code",
                                "src/main/java/com/example/student/StudentController.java",
                                "StudentController changed files",
                                "Changed files include StudentController, StudentService and StudentControllerTest.",
                                0.93,
                                List.of("StudentController", "StudentService", "StudentControllerTest")
                        )
                )
        );

        StructuredModelResult<VerifyDecision> verify = verifyAgent.evaluate(verifyDefinition, verifyPack);
        assertVerifyDecision(verify.value());
    }

    private AgentDefinition agent(String agentId, String displayName, String model) {
        return new AgentDefinition(
                agentId,
                displayName,
                "smoke validation",
                "SYSTEM",
                "in-process",
                model,
                4,
                false,
                false,
                true,
                true
        );
    }

    private void assertRequirementDecision(RequirementAgentDecision decision) {
        assertThat(decision.decision()).isIn(RequirementDecisionType.NEED_INPUT, RequirementDecisionType.DRAFT_READY);
        if (decision.decision() == RequirementDecisionType.NEED_INPUT) {
            assertThat(decision.gaps().isEmpty() && decision.questions().isEmpty()).isFalse();
            return;
        }
        assertThat(decision.draftTitle()).isNotBlank();
        assertThat(decision.draftContent()).isNotBlank();
    }

    private void assertArchitectDecision(ArchitectDecision decision) {
        assertThat(decision.decision()).isIn(
                ArchitectDecisionType.NEED_INPUT,
                ArchitectDecisionType.PLAN_READY,
                ArchitectDecisionType.REPLAN_READY,
                ArchitectDecisionType.NO_CHANGES
        );
        assertThat(decision.summary()).isNotBlank();
        if (decision.decision() == ArchitectDecisionType.NEED_INPUT) {
            assertThat(decision.gaps().isEmpty() && decision.questions().isEmpty()).isFalse();
            return;
        }
        if (decision.planningGraph() == null) {
            return;
        }
        assertThat(decision.planningGraph().tasks()).allSatisfy(task -> {
            assertThat(task.title()).isNotBlank();
            assertThat(task.objective()).isNotBlank();
            assertThat(task.taskTemplateId()).isNotBlank();
            assertThat(task.capabilityPackId()).isNotBlank();
        });
    }

    private void assertCodingDecision(CodingAgentDecision decision, List<String> writeScopes) {
        assertThat(decision.decisionType()).isIn(
                CodingDecisionType.TOOL_CALL,
                CodingDecisionType.ASK_BLOCKER,
                CodingDecisionType.DELIVER
        );
        assertThat(decision.summary()).isNotBlank();
        switch (decision.decisionType()) {
            case TOOL_CALL -> {
                assertThat(decision.toolCall()).isNotNull();
                assertThat(decision.toolCall().toolId()).isNotBlank();
                assertThat(decision.toolCall().operation()).isNotBlank();
                if (decision.toolCall().toolId().equals("tool-filesystem")) {
                    switch (decision.toolCall().operation()) {
                        case "read_file", "write_file", "delete_file", "list_directory" -> {
                            String path = String.valueOf(decision.toolCall().arguments().getOrDefault("path", ""));
                            assertThat(path).isNotBlank();
                            if (decision.toolCall().operation().equals("write_file")
                                    || decision.toolCall().operation().equals("delete_file")) {
                                assertThat(isWithinWriteScope(path, writeScopes)).isTrue();
                            }
                        }
                        case "grep_text" -> assertThat(String.valueOf(
                                decision.toolCall().arguments().getOrDefault("query", "")
                        )).isNotBlank();
                        default -> {
                            // other filesystem operations are not part of the current baseline.
                        }
                    }
                }
                if (decision.toolCall().toolId().equals("tool-shell")) {
                    assertThat(String.valueOf(decision.toolCall().arguments().getOrDefault("commandId", ""))).isNotBlank();
                }
            }
            case ASK_BLOCKER -> assertThat(decision.blockerTitle()).isNotBlank();
            case DELIVER -> {
                // no extra payload required
            }
        }
    }

    private void assertVerifyDecision(VerifyDecision decision) {
        assertThat(decision.decision()).isIn(VerifyDecisionType.PASS, VerifyDecisionType.REWORK, VerifyDecisionType.ESCALATE);
        assertThat(decision.summary()).isNotBlank();
        if (decision.decision() == VerifyDecisionType.ESCALATE) {
            assertThat(decision.escalationTitle()).isNotBlank();
            assertThat(decision.escalationBody()).isNotBlank();
        }
    }

    private Optional<PlanningGraphSpec.TaskPlan> firstPlannedTask(PlanningGraphSpec planningGraph) {
        if (planningGraph == null || planningGraph.tasks().isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(planningGraph.tasks().get(0));
    }

    private PlanningGraphSpec.TaskPlan fallbackTaskPlan() {
        return new PlanningGraphSpec.TaskPlan(
                "student-impl",
                "student-core",
                "实现学生管理后端骨架",
                "交付学生 CRUD 的最小后端骨架",
                "java-backend-code",
                List.of(new WriteScope("src/main/java"), new WriteScope("src/test/java")),
                "cap-java-backend-coding"
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
        String fingerprint = packType.name().toLowerCase() + "-smoke-" + scope.workflowRunId();
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
                "smoke://" + packType.name().toLowerCase() + "/" + scope.workflowRunId(),
                toJson(payload),
                new FactBundle(facts),
                new RetrievalBundle(retrieval),
                compiledAt
        );
    }

    private Map<String, Object> architectFacts(String requirementTitle, String requirementContent) {
        Map<String, Object> facts = new LinkedHashMap<>();
        facts.put("workflow", Map.of(
                "workflowRunId", "workflow-student-smoke-2",
                "title", "学生管理系统"
        ));
        facts.put("requirement", Map.of(
                "docId", "req-student-1",
                "status", "CONFIRMED",
                "currentVersion", 1,
                "confirmedVersion", 1,
                "title", requirementTitle,
                "content", requirementContent
        ));
        facts.put("tickets", List.of());
        facts.put("planning", Map.of(
                "modules", List.of(),
                "tasks", List.of(),
                "dependencies", List.of()
        ));
        facts.put("runtimeAlerts", List.of());
        return facts;
    }

    private Map<String, Object> codingFacts(
            PlanningGraphSpec.TaskPlan taskPlan,
            String requirementContent,
            List<String> writeScopes
    ) {
        Map<String, Object> facts = new LinkedHashMap<>();
        facts.put("task", Map.of(
                "taskId", "task-student-impl",
                "title", taskPlan.title(),
                "objective", taskPlan.objective(),
                "taskTemplateId", taskPlan.taskTemplateId(),
                "capabilityPackId", taskPlan.capabilityPackId(),
                "writeScopes", writeScopes
        ));
        facts.put("workflow", Map.of(
                "workflowRunId", "workflow-student-smoke-2",
                "title", "学生管理系统"
        ));
        facts.put("requirementSlice", Map.of(
                "summary", requirementContent
        ));
        facts.put("upstreamDeliveries", List.of());
        facts.put("openBlockers", List.of());
        facts.put("runtimePlatform", "LINUX_CONTAINER");
        facts.put("shellFamily", "POSIX_SH");
        facts.put("workspaceRoot", "/workspace");
        facts.put("repoRoot", "/workspace");
        facts.put("explorationRoots", List.of(".", "src/main/java", "src/test/java"));
        facts.put("workspaceReadPolicy", "BROAD_WORKSPACE");
        facts.put("runtimeGuardrails", Map.of(
                "runtimePlatform", "LINUX_CONTAINER",
                "shellFamily", "POSIX_SH",
                "workspaceRoot", "/workspace",
                "repoRoot", "/workspace",
                "explorationRoots", List.of(".", "src/main/java", "src/test/java"),
                "workspaceReadPolicy", "BROAD_WORKSPACE",
                "toolCatalog", List.of(
                        new ToolCatalogEntry("tool-filesystem", "Filesystem", "DIRECT", List.of("read_file", "read_range", "head_file", "tail_file", "list_directory", "glob_files", "grep_text", "write_file", "delete_file"), "schema://tool-filesystem", ""),
                        new ToolCatalogEntry("tool-shell", "Shell", "DIRECT", List.of("run_command", "run_exploration_command"), "schema://tool-shell", ""),
                        new ToolCatalogEntry("tool-git", "Git", "DIRECT", List.of("git_status", "git_diff_stat", "git_head"), "schema://tool-git", "")
                ),
                "allowedCommandCatalog", Map.of(
                        "show-marker", List.of("sh", "-lc", "test -f \"$MARKER_FILE\" && cat \"$MARKER_FILE\" || true"),
                        "git-commit-delivery", List.of("sh", "-lc", "git add -A && git commit -m smoke")
                ),
                "explorationCommandCatalog", Map.of(
                        "grep-text", Map.of("description", "Readonly recursive grep inside the workspace."),
                        "read-range", Map.of("description", "Readonly line-range read for a file.")
                ),
                "writeScopes", writeScopes
        ));
        facts.put("latestRunFacts", Map.of(
                "runId", "run-student-1",
                "status", "RUNNING",
                "attempt", 1
        ));
        return facts;
    }

    private Map<String, Object> verifyFacts(PlanningGraphSpec.TaskPlan taskPlan, String requirementContent) {
        Map<String, Object> facts = new LinkedHashMap<>();
        facts.put("task", Map.of(
                "taskId", "task-student-verify",
                "title", taskPlan.title(),
                "objective", taskPlan.objective()
        ));
        facts.put("requirementSlice", Map.of(
                "summary", requirementContent
        ));
        facts.put("deterministicVerifyResult", Map.of(
                "status", "PASSED",
                "commands", List.of("mvn -q test"),
                "changedFiles", List.of(
                        "src/main/java/com/example/student/StudentController.java",
                        "src/main/java/com/example/student/StudentService.java",
                        "src/test/java/com/example/student/StudentControllerTest.java"
                )
        ));
        facts.put("deliveryEvidence", Map.of(
                "mergeCandidateCommit", "abc123student",
                "diffSummary", "Added StudentController, StudentService, StudentRepository and regression tests."
        ));
        facts.put("tickets", List.of());
        return facts;
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
                Map.of("smoke", true)
        );
    }

    private boolean isWithinWriteScope(String path, List<String> writeScopes) {
        String normalizedPath = path.replace('\\', '/');
        return writeScopes.stream()
                .map(scope -> scope.replace('\\', '/'))
                .anyMatch(scope -> normalizedPath.equals(scope) || normalizedPath.startsWith(scope + "/"));
    }

    private String toJson(Map<String, Object> payload) {
        try {
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(payload);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("failed to serialize smoke context pack", exception);
        }
    }
}
