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
import com.agentx.platform.runtime.agentkernel.model.ModelGateway;
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
import com.agentx.platform.runtime.application.workflow.TaskTemplateCatalog;
import com.agentx.platform.runtime.evaluation.EvalDimensionId;
import com.agentx.platform.runtime.evaluation.EvalDimensionResult;
import com.agentx.platform.runtime.evaluation.EvalFinding;
import com.agentx.platform.runtime.evaluation.EvalFindingSeverity;
import com.agentx.platform.runtime.evaluation.EvalScorecard;
import com.agentx.platform.runtime.evaluation.EvalStatus;
import com.agentx.platform.runtime.evaluation.WorkflowEvalCenter;
import com.agentx.platform.runtime.evaluation.WorkflowEvalProperties;
import com.agentx.platform.runtime.tooling.CompiledToolCatalog;
import com.agentx.platform.runtime.tooling.ToolCall;
import com.agentx.platform.runtime.tooling.ToolCatalogEntry;
import com.agentx.platform.runtime.tooling.ToolRegistry;
import com.agentx.platform.support.TestStackProfiles;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class AgentKernelEvalBaselineTests {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void shouldWriteOfflineAgentEvalBaselineReport() throws Exception {
        Path outputDir = Path.of("target", "agent-eval");
        Files.createDirectories(outputDir);

        ToolRegistry toolRegistry = new ToolRegistry();
        List<EvalScenarioResult> results = new ArrayList<>();
        results.add(requirementNeedInputScenario());
        results.add(architectPlanReadyScenario());
        results.add(codingToolCallScenario(toolRegistry));
        results.add(verifyPassScenario());
        results.add(invalidCodingStructureScenario());

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("generatedAt", LocalDateTime.now().toString());
        report.put("baseline", "agent-kernel-eval-v1");
        report.put("results", results);
        Files.writeString(outputDir.resolve("baseline-report.json"), objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(report));

        WorkflowEvalProperties properties = new WorkflowEvalProperties();
        properties.setArtifactRoot(Path.of("target", "eval-center"));
        WorkflowEvalCenter evalCenter = new WorkflowEvalCenter(objectMapper, TestStackProfiles.taskTemplateCatalog(), properties);
        EvalScorecard scorecard = new EvalScorecard(
                "agent-kernel-baseline",
                "baseline-agent-kernel",
                results.stream().allMatch(EvalScenarioResult::passed) ? EvalStatus.PASS : EvalStatus.FAIL,
                LocalDateTime.now(),
                List.of(new EvalDimensionResult(
                        EvalDimensionId.NODE_CONTRACT,
                        results.stream().allMatch(EvalScenarioResult::passed) ? EvalStatus.PASS : EvalStatus.FAIL,
                        results.stream().allMatch(EvalScenarioResult::passed) ? 100 : 60,
                        "离线 agent kernel baseline 共覆盖 " + results.size() + " 个场景。",
                        results.stream()
                                .filter(result -> !result.passed())
                                .map(result -> new EvalFinding(
                                        result.scenarioId(),
                                        EvalFindingSeverity.ERROR,
                                        "baseline 场景失败",
                                        result.detail(),
                                        List.of("baseline:" + result.scenarioId())
                                ))
                                .toList(),
                        Map.of("scenarioCount", results.size())
                )),
                results.stream()
                        .filter(result -> !result.passed())
                        .map(result -> new EvalFinding(
                                result.scenarioId(),
                                EvalFindingSeverity.ERROR,
                                "baseline 场景失败",
                                result.detail(),
                                List.of("baseline:" + result.scenarioId())
                        ))
                        .toList(),
                results.stream()
                        .filter(result -> !result.passed())
                        .map(result -> new EvalFinding(
                                result.scenarioId(),
                                EvalFindingSeverity.ERROR,
                                "baseline 场景失败",
                                result.detail(),
                                List.of("baseline:" + result.scenarioId())
                        ))
                        .toList(),
                Map.of("baselineReport", outputDir.resolve("baseline-report.json").toString()),
                Map.of()
        );
        String markdown = """
                # Agent Kernel Baseline Report

                - scenarioId: `agent-kernel-baseline`
                - runLabel: `baseline-agent-kernel`

                ## 场景结果
                %s
                """.formatted(results.stream()
                .map(result -> "- `%s` -> `%s`: %s".formatted(result.scenarioId(), result.passed() ? "PASS" : "FAIL", result.detail()))
                .reduce((left, right) -> left + System.lineSeparator() + right)
                .orElse("- none"));
        evalCenter.writeStandaloneArtifacts("agent-kernel-baseline", "baseline-agent-kernel", report, scorecard, markdown);

        assertThat(results).allMatch(EvalScenarioResult::passed);
    }

    private EvalScenarioResult requirementNeedInputScenario() {
        RequirementConversationAgent agent = new RequirementConversationAgent(
                fixedGateway(new RequirementAgentDecision(
                        RequirementDecisionType.NEED_INPUT,
                        List.of("缺少验收边界"),
                        List.of("学生删除后是否需要支持恢复？"),
                        null,
                        null,
                        "need more detail"
                )),
                objectMapper
        );
        StructuredModelResult<RequirementAgentDecision> result = agent.evaluate(
                agent("requirement-agent"),
                new RequirementConversationContext(
                        "workflow-eval-1",
                        "学生管理系统",
                        "学生管理系统",
                        "做一个学生管理系统",
                        Optional.empty(),
                        List.of(),
                        "SEED",
                        "做一个学生管理系统"
                )
        );
        RequirementAgentDecision decision = result.value();
        boolean passed = decision.decision() == RequirementDecisionType.NEED_INPUT
                && !decision.gaps().isEmpty()
                && !decision.questions().isEmpty();
        return new EvalScenarioResult("requirement-need-input", passed, decision.summary());
    }

    private EvalScenarioResult architectPlanReadyScenario() {
        PlanningGraphSpec planningGraph = new PlanningGraphSpec(
                "student management baseline graph",
                List.of(new PlanningGraphSpec.ModulePlan("student-core", "学生核心", "学生管理核心模块")),
                List.of(new PlanningGraphSpec.TaskPlan(
                        "student-impl",
                        "student-core",
                        "实现学生管理后端骨架",
                        "交付学生 CRUD 的最小后端骨架",
                        "java-backend-code",
                        List.of(new WriteScope("src/main/java"), new WriteScope("src/test/java")),
                        "cap-java-backend-coding"
                )),
                List.of()
        );
        ArchitectConversationAgent agent = new ArchitectConversationAgent(
                fixedGateway(new ArchitectDecision(
                        ArchitectDecisionType.PLAN_READY,
                        List.of(),
                        List.of(),
                        "plan is ready",
                        planningGraph
                )),
                new TaskTemplateCatalog()
        );
        StructuredModelResult<ArchitectDecision> result = agent.evaluate(agent("architect-agent"), compiledPack(ContextPackType.ARCHITECT));
        boolean passed = result.value().decision() == ArchitectDecisionType.PLAN_READY
                && result.value().planningGraph() != null
                && !result.value().planningGraph().tasks().isEmpty()
                && !result.value().planningGraph().tasks().get(0).taskTemplateId().isBlank()
                && !result.value().planningGraph().tasks().get(0).capabilityPackId().isBlank();
        return new EvalScenarioResult("architect-plan-ready", passed, result.value().summary());
    }

    private EvalScenarioResult codingToolCallScenario(ToolRegistry toolRegistry) {
        CodingConversationAgent agent = new CodingConversationAgent(
                fixedGateway(new CodingAgentDecision(
                        CodingDecisionType.TOOL_CALL,
                        new ToolCall(
                                "tool-filesystem",
                                "list_directory",
                                Map.of("path", "src/main/java"),
                                "browse source tree"
                        ),
                        null,
                        null,
                        "browse source tree"
                ))
                ,
                TestStackProfiles.registry()
        );
        StructuredModelResult<CodingAgentDecision> result = agent.evaluate(
                agent("coding-agent-java"),
                compiledPack(ContextPackType.CODING),
                "no prior turns"
        );
        CompiledToolCatalog catalog = new CompiledToolCatalog(List.of(
                new ToolCatalogEntry("tool-filesystem", "Filesystem", "DIRECT", List.of("read_file", "read_range", "head_file", "tail_file", "list_directory", "glob_files", "grep_text", "write_file", "delete_file"), "schema://tool-filesystem", ""),
                new ToolCatalogEntry("tool-shell", "Shell", "DIRECT", List.of("run_command", "run_exploration_command"), "schema://tool-shell", "")
        ));
        toolRegistry.validate(catalog, result.value().toolCall());
        boolean passed = result.value().decisionType() == CodingDecisionType.TOOL_CALL
                && "src/main/java".equals(result.value().toolCall().arguments().get("path"));
        return new EvalScenarioResult("coding-tool-call", passed, result.value().summary());
    }

    private EvalScenarioResult verifyPassScenario() {
        VerifyDecisionAgent agent = new VerifyDecisionAgent(
                fixedGateway(new VerifyDecision(
                        VerifyDecisionType.PASS,
                        "verify passed",
                        null,
                        null
                )),
                TestStackProfiles.registry()
        );
        StructuredModelResult<VerifyDecision> result = agent.evaluate(agent("verify-agent-java"), compiledPack(ContextPackType.VERIFY));
        boolean passed = result.value().decision() == VerifyDecisionType.PASS
                && !result.value().summary().isBlank();
        return new EvalScenarioResult("verify-pass", passed, result.value().summary());
    }

    private EvalScenarioResult invalidCodingStructureScenario() {
        CodingConversationAgent agent = new CodingConversationAgent(
                new RejectingGateway("toolCall must be present for TOOL_CALL decisions"),
                TestStackProfiles.registry()
        );
        boolean rejected;
        try {
            agent.evaluate(agent("coding-agent-java"), compiledPack(ContextPackType.CODING), "no prior turns");
            rejected = false;
        } catch (IllegalArgumentException exception) {
            rejected = exception.getMessage().contains("toolCall");
        }
        return new EvalScenarioResult("invalid-coding-structure-rejected", rejected, "invalid tool-call payload rejected");
    }

    private AgentDefinition agent(String agentId) {
        return new AgentDefinition(
                agentId,
                agentId,
                "eval",
                "SYSTEM",
                "in-process",
                "stub-model",
                1,
                false,
                false,
                true,
                true
        );
    }

    private CompiledContextPack compiledPack(ContextPackType packType) {
        ContextScope scope = packType == ContextPackType.CODING || packType == ContextPackType.VERIFY
                ? ContextScope.task("workflow-eval-1", "task-eval-1", "run-eval-1", packType.name().toLowerCase(), null)
                : ContextScope.workflow("workflow-eval-1", packType.name().toLowerCase());
        Map<String, Object> facts = Map.of(
                "workflow", Map.of("workflowRunId", "workflow-eval-1", "title", "学生管理系统"),
                "runtimePlatform", "LINUX_CONTAINER",
                "shellFamily", "POSIX_SH",
                "workspaceRoot", "/workspace",
                "repoRoot", "/workspace",
                "explorationRoots", List.of(".", "src/main/java"),
                "workspaceReadPolicy", "BROAD_WORKSPACE",
                "runtimeGuardrails", Map.of(
                        "runtimePlatform", "LINUX_CONTAINER",
                        "shellFamily", "POSIX_SH",
                        "workspaceRoot", "/workspace",
                        "repoRoot", "/workspace",
                        "explorationRoots", List.of(".", "src/main/java"),
                        "workspaceReadPolicy", "BROAD_WORKSPACE",
                        "toolCatalog", List.of("tool-filesystem.list_directory", "tool-filesystem.grep_text", "tool-shell.run_exploration_command", "tool-shell.run_command"),
                        "allowedCommandCatalog", Map.of("maven-test", List.of("sh", "-lc", "mvn -q test")),
                        "explorationCommandCatalog", Map.of("grep-text", Map.of("description", "Readonly recursive grep inside the workspace.")),
                        "writeScopes", List.of("src/main/java")
                )
        );
        return new CompiledContextPack(
                packType,
                scope,
                "eval-fingerprint-" + packType.name().toLowerCase(),
                "eval://" + packType.name().toLowerCase(),
                objectToJson(facts),
                new FactBundle(facts),
                new RetrievalBundle(List.of()),
                LocalDateTime.now()
        );
    }

    private String objectToJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception exception) {
            throw new IllegalStateException("failed to write eval json", exception);
        }
    }

    private <T> ModelGateway fixedGateway(T value) {
        return new ModelGateway() {
            @Override
            @SuppressWarnings("unchecked")
            public <R> StructuredModelResult<R> generateStructuredObject(
                    AgentDefinition agentDefinition,
                    String systemPrompt,
                    String userPrompt,
                    Class<R> responseType
            ) {
                return new StructuredModelResult<>((R) value, "stub", agentDefinition.model(), "{\"stub\":true}");
            }
        };
    }

    private static class RejectingGateway implements ModelGateway {

        private final String message;

        private RejectingGateway(String message) {
            this.message = message;
        }

        @Override
        public <T> StructuredModelResult<T> generateStructuredObject(
                AgentDefinition agentDefinition,
                String systemPrompt,
                String userPrompt,
                Class<T> responseType
        ) {
            throw new IllegalArgumentException(message);
        }
    }

    private record EvalScenarioResult(
            String scenarioId,
            boolean passed,
            String detail
    ) {
    }
}
