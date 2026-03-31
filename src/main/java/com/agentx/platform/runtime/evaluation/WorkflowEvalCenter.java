package com.agentx.platform.runtime.evaluation;

import com.agentx.platform.domain.execution.model.GitWorkspaceStatus;
import com.agentx.platform.domain.execution.model.TaskRun;
import com.agentx.platform.domain.execution.model.TaskRunEvent;
import com.agentx.platform.domain.execution.model.TaskRunStatus;
import com.agentx.platform.domain.flow.model.WorkflowNodeRun;
import com.agentx.platform.domain.flow.model.WorkflowNodeRunStatus;
import com.agentx.platform.domain.intake.model.RequirementStatus;
import com.agentx.platform.domain.intake.model.TicketStatus;
import com.agentx.platform.domain.shared.model.JsonPayload;
import com.agentx.platform.domain.shared.model.WriteScope;
import com.agentx.platform.runtime.agentkernel.architect.PlanningGraphSpec;
import com.agentx.platform.runtime.application.workflow.TaskTemplateCatalog;
import com.agentx.platform.runtime.application.workflow.WorkflowProfileRef;
import com.agentx.platform.runtime.application.workflow.WorkflowRuntimeSnapshot;
import com.agentx.platform.runtime.application.workflow.profile.ActiveStackProfileSnapshot;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

@Component
public class WorkflowEvalCenter {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };
    private static final TypeReference<List<Map<String, Object>>> LIST_OF_MAPS_TYPE = new TypeReference<>() {
    };
    private static final DateTimeFormatter RUN_STAMP_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private final ObjectMapper objectMapper;
    private final ObjectMapper artifactObjectMapper;
    private final TaskTemplateCatalog taskTemplateCatalog;
    private final WorkflowEvalProperties properties;

    public WorkflowEvalCenter(
            ObjectMapper objectMapper,
            TaskTemplateCatalog taskTemplateCatalog,
            WorkflowEvalProperties properties
    ) {
        this.objectMapper = objectMapper;
        this.artifactObjectMapper = objectMapper.copy().findAndRegisterModules();
        this.taskTemplateCatalog = taskTemplateCatalog;
        this.properties = properties;
    }

    public EvalReportArtifacts generateWorkflowReport(EvalScenario scenario, EvalEvidenceBundle evidence) {
        ActiveStackProfileSnapshot activeProfile = resolveActiveProfile(evidence.workflowSnapshot());
        ResolvedArtifactPaths artifactPaths = resolveArtifactPaths(
                scenario.scenarioId(),
                evidence.workflowSnapshot().workflowRun().workflowRunId()
        );
        Map<String, String> artifactRefs = mergedArtifactRefs(evidence.artifactRefs(), artifactPaths);
        EvalScorecard scorecard = scoreWorkflow(scenario, evidence, activeProfile, artifactRefs);
        Map<String, Object> rawEvidence = rawEvidencePayload(scenario, evidence, scorecard, artifactPaths, activeProfile, artifactRefs);
        String markdown = markdownReport(scenario, evidence, scorecard, artifactPaths, activeProfile, artifactRefs);
        return writeResolvedArtifacts(
                artifactPaths,
                rawEvidence,
                scorecard,
                markdown,
                profileSnapshotPayload(activeProfile)
        );
    }

    public EvalReportArtifacts writeStandaloneArtifacts(
            String scenarioId,
            String runLabel,
            Object rawEvidence,
            EvalScorecard scorecard,
            String markdownReport
    ) {
        return writeResolvedArtifacts(
                resolveArtifactPaths(scenarioId, runLabel),
                rawEvidence,
                scorecard,
                markdownReport,
                Map.of("profileResolved", false)
        );
    }

    private EvalReportArtifacts writeResolvedArtifacts(
            ResolvedArtifactPaths artifactPaths,
            Object rawEvidence,
            EvalScorecard scorecard,
            String markdownReport,
            Object profileSnapshot
    ) {
        try {
            Files.createDirectories(artifactPaths.outputDirectory());
            writeJson(artifactPaths.rawEvidencePath(), augmentRawEvidence(rawEvidence, artifactPaths));
            writeJson(artifactPaths.scorecardPath(), scorecard);
            writeJson(artifactPaths.profileSnapshotPath(), profileSnapshot);
            Files.writeString(artifactPaths.markdownPath(), markdownReport, StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new IllegalStateException("failed to write eval artifacts to " + artifactPaths.outputDirectory(), exception);
        }
        return new EvalReportArtifacts(
                artifactPaths.outputDirectory(),
                artifactPaths.rawEvidencePath(),
                artifactPaths.scorecardPath(),
                artifactPaths.markdownPath(),
                artifactPaths.profileSnapshotPath()
        );
    }

    public EvalScorecard scoreWorkflow(EvalScenario scenario, EvalEvidenceBundle evidence) {
        return scoreWorkflow(scenario, evidence, resolveActiveProfile(evidence.workflowSnapshot()), evidence.artifactRefs());
    }

    private EvalScorecard scoreWorkflow(
            EvalScenario scenario,
            EvalEvidenceBundle evidence,
            ActiveStackProfileSnapshot activeProfile,
            Map<String, String> artifactRefs
    ) {
        List<EvalDimensionResult> dimensions = List.of(
                nodeContractDimension(evidence),
                workflowTrajectoryDimension(scenario, evidence),
                dagDimension(evidence, activeProfile),
                ragDimension(scenario, evidence),
                toolProtocolDimension(scenario, evidence),
                deliveryArtifactDimension(evidence, activeProfile),
                runtimeRobustnessDimension(evidence),
                humanInLoopDimension(evidence),
                efficiencyDimension(evidence)
        );
        List<EvalFinding> hardGates = dimensions.stream()
                .flatMap(result -> result.findings().stream())
                .filter(finding -> finding.severity() == EvalFindingSeverity.ERROR)
                .toList();
        List<EvalFinding> findings = dimensions.stream()
                .flatMap(result -> result.findings().stream())
                .toList();
        EvalStatus overallStatus = dimensions.stream().anyMatch(result -> result.status() == EvalStatus.FAIL)
                ? EvalStatus.FAIL
                : dimensions.stream().anyMatch(result -> result.status() == EvalStatus.WARN)
                ? EvalStatus.WARN
                : EvalStatus.PASS;
        return new EvalScorecard(
                scenario.scenarioId(),
                evidence.workflowSnapshot().workflowRun().workflowRunId(),
                overallStatus,
                LocalDateTime.now(),
                dimensions,
                hardGates,
                findings,
                artifactRefs,
                Map.of()
        );
    }

    private EvalDimensionResult nodeContractDimension(EvalEvidenceBundle evidence) {
        Map<String, Object> evaluationPlan = mapValue(evidence.supplementalArtifacts().get("evaluationPlan"));
        List<EvalFinding> findings = new ArrayList<>();
        int selectionCount = 0;
        int fallbackCount = 0;
        for (Map.Entry<String, Object> entry : evaluationPlan.entrySet()) {
            if (!(entry.getValue() instanceof Map<?, ?>)) {
                continue;
            }
            selectionCount++;
            Map<String, Object> selection = mapValue(entry.getValue());
            if (!booleanValue(selection.get("useReal"))) {
                fallbackCount++;
                findings.add(new EvalFinding(
                        "node-fallback-" + entry.getKey(),
                        EvalFindingSeverity.WARN,
                        "节点输出触发 fallback",
                        entry.getKey() + " 使用 fallback。原因：" + stringValue(selection.get("fallbackReason")),
                        List.of("supplemental:evaluationPlan." + entry.getKey())
                ));
            }
            Map<String, Object> selectedValue = mapValue(selection.get("selectedValue"));
            Map<String, Object> toolCall = mapValue(selectedValue.get("toolCall"));
            String path = stringValue(mapValue(toolCall.get("arguments")).get("path"));
            if (path.startsWith("/")) {
                findings.add(new EvalFinding(
                        "tool-absolute-path-" + entry.getKey(),
                        EvalFindingSeverity.ERROR,
                        "工具路径使用绝对路径",
                        entry.getKey() + " 使用绝对路径 `" + path + "`，不符合 workspace 相对路径协议。",
                        List.of("supplemental:evaluationPlan." + entry.getKey())
                ));
            }
        }
        if (selectionCount == 0) {
            for (WorkflowNodeRun nodeRun : evidence.workflowSnapshot().nodeRuns()) {
                if (nodeRun.status() == WorkflowNodeRunStatus.FAILED
                        || nodeRun.status() == WorkflowNodeRunStatus.CANCELED) {
                    findings.add(new EvalFinding(
                        "node-run-failed-" + nodeRun.nodeId(),
                        EvalFindingSeverity.ERROR,
                        "节点运行未成功结束",
                        nodeRun.nodeId() + " 以 " + nodeRun.status().name() + " 结束。",
                            List.of("nodeRun:" + nodeRun.nodeRunId())
                    ));
                }
            }
        }
        return dimension(
                EvalDimensionId.NODE_CONTRACT,
                findings,
                100 - fallbackCount * 18 - errorCount(findings) * 25 - warnCount(findings) * 5,
                selectionCount == 0
                        ? "未提供 smoke selection，按 node run 成败给出契约级结论。"
                        : "共检查 " + selectionCount + " 个节点决策，fallback " + fallbackCount + " 次。",
                Map.of(
                        "selectionCount", selectionCount,
                        "fallbackCount", fallbackCount,
                        "errorCount", errorCount(findings)
                )
        );
    }

    private EvalDimensionResult workflowTrajectoryDimension(EvalScenario scenario, EvalEvidenceBundle evidence) {
        WorkflowRuntimeSnapshot snapshot = evidence.workflowSnapshot();
        List<EvalFinding> findings = new ArrayList<>();
        if (!snapshot.tasks().isEmpty() && snapshot.requirementDoc().map(doc -> doc.status() != RequirementStatus.CONFIRMED).orElse(true)) {
            findings.add(new EvalFinding(
                    "requirement-not-confirmed-before-task-graph",
                    EvalFindingSeverity.ERROR,
                    "需求未确认即进入任务图",
                    "workflow 已经存在 tasks，但 requirement 仍未 CONFIRMED。",
                    List.of("workflow:" + snapshot.workflowRun().workflowRunId())
            ));
        }
        if (snapshot.workflowRun().status().name().equals("COMPLETED")
                && snapshot.tasks().stream().anyMatch(task -> !task.status().name().equals("DONE"))) {
            findings.add(new EvalFinding(
                    "completed-workflow-has-undone-task",
                    EvalFindingSeverity.ERROR,
                    "workflow 已完成但任务未全部 DONE",
                    "workflow 状态为 COMPLETED，但仍有 task 未处于 DONE。",
                    List.of("workflow:" + snapshot.workflowRun().workflowRunId())
            ));
        }
        long openHumanTickets = snapshot.tickets().stream()
                .filter(ticket -> ticket.assignee().type().name().equals("HUMAN") && ticket.status() == TicketStatus.OPEN)
                .count();
        if (openHumanTickets > 0 && !snapshot.workflowRun().status().name().equals("WAITING_ON_HUMAN")) {
            findings.add(new EvalFinding(
                    "open-human-ticket-without-waiting-state",
                    EvalFindingSeverity.ERROR,
                    "存在未处理人工 ticket 但 workflow 未停驻",
                    "存在 " + openHumanTickets + " 个 OPEN HUMAN ticket，但 workflow 当前状态为 "
                            + snapshot.workflowRun().status().name() + "。",
                    List.of("workflow:" + snapshot.workflowRun().workflowRunId())
            ));
        }
        List<String> seenOrder = snapshot.nodeRuns().stream()
                .sorted(Comparator.comparing(WorkflowNodeRun::startedAt))
                .map(WorkflowNodeRun::nodeId)
                .distinct()
                .toList();
        if (!seenOrder.isEmpty() && !containsOrderedSubsequence(seenOrder, scenario.expectedNodeOrder())) {
            findings.add(new EvalFinding(
                    "unexpected-node-order",
                    EvalFindingSeverity.WARN,
                    "节点执行顺序偏离固定主链",
                    "观察到的节点顺序为 " + seenOrder + "，预期主链为 " + scenario.expectedNodeOrder() + "。",
                    List.of("workflow:" + snapshot.workflowRun().workflowRunId())
            ));
        }
        return dimension(
                EvalDimensionId.WORKFLOW_TRAJECTORY,
                findings,
                100 - errorCount(findings) * 25 - warnCount(findings) * 10,
                "workflow 最终状态为 " + snapshot.workflowRun().status().name()
                        + "，共记录 " + snapshot.nodeRuns().size() + " 次 node run。",
                Map.of(
                        "workflowStatus", snapshot.workflowRun().status().name(),
                        "nodeRunCount", snapshot.nodeRuns().size(),
                        "openHumanTickets", openHumanTickets,
                        "observedNodeOrder", seenOrder
                )
        );
    }

    private EvalDimensionResult dagDimension(EvalEvidenceBundle evidence, ActiveStackProfileSnapshot activeProfile) {
        PlanningGraphSpec planningGraph = planningGraph(evidence);
        List<EvalFinding> findings = new ArrayList<>();
        Optional<String> terminalRunnerStepType = terminalRunnerStepType(evidence);
        boolean architectReached = evidence.workflowSnapshot().nodeRuns().stream()
                .anyMatch(nodeRun -> "architect".equals(nodeRun.nodeId()));
        if (planningGraph == null) {
            if (!architectReached && terminalRunnerStepType.isPresent()) {
                findings.add(new EvalFinding(
                        "dag-not-reached",
                        EvalFindingSeverity.INFO,
                        "未进入 DAG 规划阶段",
                        "workflow 在进入 architect 前已因 " + terminalRunnerStepType.orElseThrow()
                                + " 中止，DAG 维度仅记录未到达，不判定规划质量。",
                        List.of("workflowResult")
                ));
                return dimension(
                        EvalDimensionId.DAG_QUALITY,
                        findings,
                        100,
                        "workflow 未进入 architect，DAG 维度暂不判责。",
                        Map.of(
                                "taskCount", evidence.workflowSnapshot().tasks().size(),
                                "architectReached", false,
                                "terminalRunnerStepType", terminalRunnerStepType.orElseThrow()
                        )
                );
            }
            findings.add(new EvalFinding(
                    "missing-planning-graph",
                    EvalFindingSeverity.WARN,
                    "缺少 planning graph 证据",
                    "未在 supplemental artifact 或 architect node payload 中找到 PlanningGraphSpec。",
                    List.of("workflow:" + evidence.workflowSnapshot().workflowRun().workflowRunId())
            ));
            return dimension(
                    EvalDimensionId.DAG_QUALITY,
                    findings,
                    70 - warnCount(findings) * 5,
                    "没有可评估的 DAG 规划对象。",
                    Map.of("taskCount", evidence.workflowSnapshot().tasks().size())
            );
        }
        Map<String, PlanningGraphSpec.TaskPlan> tasksByKey = new LinkedHashMap<>();
        for (PlanningGraphSpec.TaskPlan taskPlan : planningGraph.tasks()) {
            tasksByKey.put(taskPlan.taskKey(), taskPlan);
            Optional<TaskTemplateCatalog.TaskTemplateDefinition> template = taskTemplateCatalog.find(
                    activeProfile.profileId(),
                    taskPlan.taskTemplateId()
            );
            if (template.isEmpty()) {
                findings.add(new EvalFinding(
                        "invalid-task-template-" + taskPlan.taskKey(),
                        EvalFindingSeverity.ERROR,
                        "taskTemplateId 不在平台目录内",
                        taskPlan.taskKey() + " 使用了不受支持的 taskTemplateId `" + taskPlan.taskTemplateId() + "`。",
                        List.of("planningGraph:task:" + taskPlan.taskKey())
                ));
                continue;
            }
            if (!template.orElseThrow().capabilityPackId().equals(taskPlan.capabilityPackId())) {
                findings.add(new EvalFinding(
                        "invalid-capability-pack-" + taskPlan.taskKey(),
                        EvalFindingSeverity.ERROR,
                        "capabilityPackId 与模板不匹配",
                        taskPlan.taskKey() + " 使用 capabilityPackId `" + taskPlan.capabilityPackId()
                                + "`，但模板要求 `" + template.orElseThrow().capabilityPackId() + "`。",
                        List.of("planningGraph:task:" + taskPlan.taskKey())
                ));
            }
            for (WriteScope writeScope : taskPlan.writeScopes()) {
                if (!taskTemplateCatalog.allowsWriteScope(template.orElseThrow(), writeScope)) {
                    findings.add(new EvalFinding(
                        "write-scope-out-of-template-" + taskPlan.taskKey(),
                        EvalFindingSeverity.ERROR,
                        "write scope 超出模板允许边界",
                            taskPlan.taskKey() + " 声明了 write scope `" + writeScope.path() + "`，超出模板默认边界。",
                            List.of("planningGraph:task:" + taskPlan.taskKey())
                    ));
                }
            }
        }
        Map<String, List<String>> adjacency = new LinkedHashMap<>();
        Map<String, Integer> indegree = new LinkedHashMap<>();
        for (String taskKey : tasksByKey.keySet()) {
            adjacency.put(taskKey, new ArrayList<>());
            indegree.put(taskKey, 0);
        }
        for (PlanningGraphSpec.DependencyPlan dependency : planningGraph.dependencies()) {
            if (!tasksByKey.containsKey(dependency.taskKey()) || !tasksByKey.containsKey(dependency.dependsOnTaskKey())) {
                findings.add(new EvalFinding(
                        "missing-dependency-endpoint-" + dependency.taskKey(),
                        EvalFindingSeverity.ERROR,
                        "依赖指向不存在的 taskKey",
                        dependency.taskKey() + " -> " + dependency.dependsOnTaskKey() + " 中至少一端不存在。",
                        List.of("planningGraph:dependency:" + dependency.taskKey())
                ));
                continue;
            }
            adjacency.get(dependency.dependsOnTaskKey()).add(dependency.taskKey());
            indegree.computeIfPresent(dependency.taskKey(), (key, value) -> value + 1);
        }
        boolean hasCycle = hasCycle(adjacency, indegree);
        if (hasCycle) {
            findings.add(new EvalFinding(
                    "planning-graph-cycle",
                    EvalFindingSeverity.ERROR,
                    "DAG 出现环",
                    "规划图包含循环依赖，不符合固定 task graph 约束。",
                    List.of("planningGraph")
            ));
        }
        return dimension(
                EvalDimensionId.DAG_QUALITY,
                findings,
                100 - errorCount(findings) * 20 - warnCount(findings) * 5,
                "检查到 " + planningGraph.tasks().size() + " 个 task，依赖数 " + planningGraph.dependencies().size() + "。",
                Map.of(
                        "moduleCount", planningGraph.modules().size(),
                        "taskCount", planningGraph.tasks().size(),
                        "dependencyCount", planningGraph.dependencies().size(),
                        "fanOutMax", adjacency.values().stream().mapToInt(List::size).max().orElse(0),
                        "fanInMax", indegree.values().stream().mapToInt(Integer::intValue).max().orElse(0),
                        "maxDepth", maxDepth(adjacency, indegree, hasCycle),
                        "criticalPathLength", criticalPath(adjacency, indegree, hasCycle)
                )
        );
    }

    private EvalDimensionResult ragDimension(EvalScenario scenario, EvalEvidenceBundle evidence) {
        List<EvalFinding> findings = new ArrayList<>();
        List<WorkflowEvalContextArtifact> contextArtifacts = evidence.contextArtifacts();
        Optional<String> terminalRunnerStepType = terminalRunnerStepType(evidence);
        boolean repoContextStageReached = evidence.workflowSnapshot().nodeRuns().stream()
                .anyMatch(nodeRun -> "architect".equals(nodeRun.nodeId())
                        || "coding".equals(nodeRun.nodeId())
                        || "verify".equals(nodeRun.nodeId()));
        if (!repoContextStageReached && terminalRunnerStepType.isPresent()) {
            findings.add(new EvalFinding(
                    "rag-not-reached",
                    EvalFindingSeverity.INFO,
                    "未进入 RAG 评测阶段",
                    "workflow 在进入 architect/coding/verify 前已因 " + terminalRunnerStepType.orElseThrow()
                            + " 中止，RAG 维度仅记录未到达，不把缺少检索结果判成质量问题。",
                    List.of("workflowResult")
            ));
            Map<String, Object> metrics = new LinkedHashMap<>();
            metrics.put("contextArtifactCount", contextArtifacts.size());
            metrics.put("retrievalArtifactCount", 0);
            metrics.put("expectedFactCount", scenario.expectedFacts().size());
            metrics.put("factHits", 0);
            metrics.put("factRecallRate", 0.0);
            metrics.put("expectedSnippetCount", scenario.expectedSnippetRefs().size());
            metrics.put("snippetHits", 0);
            metrics.put("snippetHitRate", 0.0);
            metrics.put("retrievalSnippetCount", 0);
            metrics.put("repoContextStageReached", false);
            metrics.put("terminalRunnerStepType", terminalRunnerStepType.orElseThrow());
            return dimension(
                    EvalDimensionId.RAG_QUALITY,
                    findings,
                    100,
                    "workflow 未进入 architect/coding/verify，RAG 维度暂不判责。",
                    metrics
            );
        }
        long repoBackedArtifacts = contextArtifacts.stream()
                .filter(artifact -> artifact.packType().name().equals("CODING")
                        || artifact.packType().name().equals("VERIFY")
                        || artifact.packType().name().equals("ARCHITECT"))
                .filter(artifact -> !artifact.retrievalSnippets().isEmpty())
                .count();
        if (scenario.repoContextRequired() && repoBackedArtifacts == 0) {
            findings.add(new EvalFinding(
                    "missing-repo-retrieval",
                    EvalFindingSeverity.ERROR,
                    "场景需要 repo context，但没有检索结果",
                    "scenario 标记为 repoContextRequired，但 coding/architect/verify context 中没有 retrieval snippets。",
                    List.of("contextArtifacts")
            ));
        }
        boolean verifyReached = evidence.workflowSnapshot().nodeRuns().stream()
                .anyMatch(nodeRun -> "verify".equals(nodeRun.nodeId()));
        boolean hasVerifyContext = contextArtifacts.stream().anyMatch(artifact -> artifact.packType().name().equals("VERIFY"));
        if (verifyReached && !hasVerifyContext) {
            findings.add(new EvalFinding(
                    "missing-verify-context",
                    EvalFindingSeverity.ERROR,
                    "verify 阶段缺少 context pack",
                    "workflow 已进入 verify，但未采集到 VERIFY context pack。",
                    List.of("contextArtifacts")
            ));
        }
        int factHits = 0;
        for (String expectedFact : scenario.expectedFacts()) {
            if (containsFact(contextArtifacts, expectedFact)) {
                factHits++;
            } else {
                findings.add(new EvalFinding(
                        "missing-golden-fact-" + sanitize(expectedFact),
                        EvalFindingSeverity.WARN,
                        "golden fact 未进入 context",
                        "未在 facts/retrieval 中找到预期事实 `" + expectedFact + "`。",
                        List.of("contextArtifacts")
                ));
            }
        }
        int snippetHits = 0;
        for (String expectedSnippet : scenario.expectedSnippetRefs()) {
            if (containsSnippetRef(contextArtifacts, expectedSnippet)) {
                snippetHits++;
            }
        }
        double factRecall = ratio(factHits, scenario.expectedFacts().size());
        double snippetRecall = ratio(snippetHits, scenario.expectedSnippetRefs().size());
        return dimension(
                EvalDimensionId.RAG_QUALITY,
                findings,
                (int) Math.round(50 + factRecall * 30 + snippetRecall * 20) - errorCount(findings) * 20 - warnCount(findings) * 5,
                "共采集 " + contextArtifacts.size() + " 个 context pack，golden fact recall="
                        + percentage(factRecall) + "，snippet hit=" + percentage(snippetRecall) + "。",
                Map.of(
                        "contextArtifactCount", contextArtifacts.size(),
                        "retrievalArtifactCount", repoBackedArtifacts,
                        "expectedFactCount", scenario.expectedFacts().size(),
                        "factHits", factHits,
                        "factRecallRate", factRecall,
                        "expectedSnippetCount", scenario.expectedSnippetRefs().size(),
                        "snippetHits", snippetHits,
                        "snippetHitRate", snippetRecall,
                        "retrievalSnippetCount", contextArtifacts.stream().mapToInt(artifact -> artifact.retrievalSnippets().size()).sum()
                )
        );
    }

    private EvalDimensionResult toolProtocolDimension(EvalScenario scenario, EvalEvidenceBundle evidence) {
        List<EvalFinding> findings = new ArrayList<>();
        List<Map<String, Object>> toolCalls = codingToolCalls(evidence);
        long reusedCalls = 0;
        for (Map<String, Object> toolCall : toolCalls) {
            Map<String, Object> arguments = mapValue(toolCall.get("arguments"));
            String path = stringValue(arguments.get("path"));
            if (path.startsWith("/")) {
                findings.add(new EvalFinding(
                        "absolute-tool-path-" + stringValue(toolCall.get("callId")),
                        EvalFindingSeverity.ERROR,
                        "工具调用使用绝对路径",
                        "tool call `" + stringValue(toolCall.get("callId")) + "` 使用了绝对路径 `" + path + "`。",
                        List.of("toolCall:" + stringValue(toolCall.get("callId")))
                ));
            }
            if (!path.isBlank() && !scenario.expectedToolPathPrefixes().isEmpty()) {
                boolean matchesPrefix = scenario.expectedToolPathPrefixes().stream()
                        .anyMatch(prefix -> path.equals(prefix) || path.startsWith(prefix + "/") || ".".equals(path));
                if (!matchesPrefix) {
                    findings.add(new EvalFinding(
                            "unexpected-tool-path-" + stringValue(toolCall.get("callId")),
                            EvalFindingSeverity.WARN,
                            "工具调用路径偏离预期写域",
                            "tool call `" + stringValue(toolCall.get("callId")) + "` 访问了 `" + path + "`。",
                            List.of("toolCall:" + stringValue(toolCall.get("callId")))
                    ));
                }
            }
            if (booleanValue(toolCall.get("toolCallReused"))) {
                reusedCalls++;
            }
        }
        return dimension(
                EvalDimensionId.TOOL_PROTOCOL,
                findings,
                100 - errorCount(findings) * 25 - warnCount(findings) * 8,
                "共分析 " + toolCalls.size() + " 个 coding tool call。",
                Map.of(
                        "toolCallCount", toolCalls.size(),
                        "reusedCallCount", reusedCalls,
                        "invalidCallCount", errorCount(findings),
                        "warningCallCount", warnCount(findings)
                )
        );
    }

    private EvalDimensionResult deliveryArtifactDimension(EvalEvidenceBundle evidence, ActiveStackProfileSnapshot activeProfile) {
        WorkflowRuntimeSnapshot snapshot = evidence.workflowSnapshot();
        List<EvalFinding> findings = new ArrayList<>();
        String reviewBundle = evidence.artifactRefs().getOrDefault("reviewBundle", "");
        boolean deliveryReached = snapshot.workspaces().stream().anyMatch(workspace -> workspace.headCommit() != null && !workspace.headCommit().isBlank())
                || snapshot.tasks().stream().anyMatch(task -> task.status().name().equals("DELIVERED") || task.status().name().equals("DONE"))
                || snapshot.nodeRuns().stream().anyMatch(nodeRun -> "merge-gate".equals(nodeRun.nodeId()) || "verify".equals(nodeRun.nodeId()));
        Map<String, TaskRun> latestRunsByTaskId = latestTaskRunsByTaskId(snapshot.taskRuns());
        Map<String, String> latestWorkspaceStateByTaskId = latestWorkspaceStateByTaskId(snapshot);
        List<String> terminalTasksWithoutSuccessfulRun = snapshot.tasks().stream()
                .filter(task -> task.status().name().equals("DELIVERED") || task.status().name().equals("DONE"))
                .map(task -> task.taskId())
                .filter(taskId -> {
                    TaskRun latestRun = latestRunsByTaskId.get(taskId);
                    return latestRun == null || latestRun.status() != TaskRunStatus.SUCCEEDED;
                })
                .toList();
        List<String> terminalTasksWithoutFinalizedWorkspace = snapshot.tasks().stream()
                .filter(task -> task.status().name().equals("DELIVERED") || task.status().name().equals("DONE"))
                .map(task -> task.taskId())
                .filter(taskId -> {
                    String workspaceState = latestWorkspaceStateByTaskId.get(taskId);
                    return workspaceState == null
                            || (!GitWorkspaceStatus.MERGED.name().equals(workspaceState)
                            && !GitWorkspaceStatus.CLEANED.name().equals(workspaceState));
                })
                .toList();
        if (snapshot.workflowRun().status().name().equals("COMPLETED") && reviewBundle.isBlank()) {
            findings.add(new EvalFinding(
                    "missing-review-bundle",
                    EvalFindingSeverity.ERROR,
                    "缺少 review bundle",
                    "workflow 已完成，但 artifactRefs 中没有 reviewBundle。",
                    List.of("artifactRefs")
            ));
        }
        if (!terminalTasksWithoutFinalizedWorkspace.isEmpty()) {
            findings.add(new EvalFinding(
                    "workspace-not-merged-or-cleaned",
                    EvalFindingSeverity.ERROR,
                    "存在未完成 merge/cleanup 的 workspace",
                    "交付阶段任务 " + terminalTasksWithoutFinalizedWorkspace + " 的最新 workspace 既不是 MERGED 也不是 CLEANED。",
                    List.of("workspaces")
            ));
        }
        if (!terminalTasksWithoutSuccessfulRun.isEmpty()) {
            findings.add(new EvalFinding(
                    "task-run-not-succeeded",
                    EvalFindingSeverity.ERROR,
                    "存在未成功结束的 task run",
                    "交付阶段任务 " + terminalTasksWithoutSuccessfulRun + " 缺少最终成功结束的最新 task run。",
                    List.of("taskRuns")
            ));
        }
        Map<String, Long> reviewBundleRoleCounts = reviewBundle.isBlank()
                ? Map.of()
                : activeProfile.inspectReviewBundle(Path.of(reviewBundle));
        if (deliveryReached
                && snapshot.requirementDoc().map(doc -> doc.status() == RequirementStatus.CONFIRMED).orElse(false)
                && !activeProfile.matchesRequiredArtifactRoles(reviewBundleRoleCounts)) {
            findings.add(new EvalFinding(
                    "missing-required-artifact-roles",
                    EvalFindingSeverity.ERROR,
                    "交付物缺少 profile 要求的角色产物",
                    "review bundle 缺少这些必需角色：" + missingRequiredRoles(activeProfile, reviewBundleRoleCounts) + "。",
                    List.of("artifact:" + reviewBundle)
            ));
        }
        return dimension(
                EvalDimensionId.DELIVERY_ARTIFACT,
                findings,
                100 - errorCount(findings) * 25 - warnCount(findings) * 5,
                "review bundle 角色分布：" + formatRoleCounts(reviewBundleRoleCounts) + "。",
                Map.of(
                        "workspaceCount", snapshot.workspaces().size(),
                        "terminalTaskCount", snapshot.tasks().stream()
                                .filter(task -> task.status().name().equals("DELIVERED") || task.status().name().equals("DONE"))
                                .count(),
                        "reviewBundlePresent", !reviewBundle.isBlank(),
                        "reviewBundleRoleCounts", reviewBundleRoleCounts,
                        "requiredArtifactRoles", activeProfile.manifest().eval().requiredArtifactRoles()
                )
        );
    }

    private EvalDimensionResult runtimeRobustnessDimension(EvalEvidenceBundle evidence) {
        WorkflowRuntimeSnapshot snapshot = evidence.workflowSnapshot();
        List<EvalFinding> findings = new ArrayList<>();
        Map<String, Object> workflowResult = workflowResult(evidence);
        Optional<String> terminalRunnerStepType = terminalRunnerStepType(evidence);
        long failedRuns = snapshot.taskRuns().stream().filter(run -> run.status() == TaskRunStatus.FAILED).count();
        if ("PROVIDER_FAILURE".equals(terminalRunnerStepType.orElse(""))) {
            findings.add(new EvalFinding(
                    "provider-failure-abort",
                    EvalFindingSeverity.ERROR,
                    "真实模型 provider 失败导致流程中止",
                    "strict runner 在稳定边界前收到 provider 失败："
                            + stringValue(workflowResult.get("stopReason"))
                            + "。",
                    List.of("workflowResult")
            ));
        } else if ("SCHEMA_FAILURE".equals(terminalRunnerStepType.orElse(""))) {
            findings.add(new EvalFinding(
                    "schema-failure-abort",
                    EvalFindingSeverity.ERROR,
                    "真实模型 schema 解析失败导致流程中止",
                    "strict runner 在稳定边界前收到 schema/JSON 解析失败："
                            + stringValue(workflowResult.get("stopReason"))
                            + "。",
                    List.of("workflowResult")
            ));
        } else if ("RUN_TIMEOUT".equals(terminalRunnerStepType.orElse(""))) {
            findings.add(new EvalFinding(
                    "run-timeout-abort",
                    EvalFindingSeverity.ERROR,
                    "真实流程超时导致中止",
                    "strict runner 在稳定边界前超时："
                            + stringValue(workflowResult.get("stopReason"))
                            + "。",
                    List.of("workflowResult")
            ));
        }
        Map<String, String> taskStatuses = snapshot.tasks().stream()
                .collect(java.util.stream.Collectors.toMap(
                        task -> task.taskId(),
                        task -> task.status().name(),
                        (left, right) -> right,
                        LinkedHashMap::new
                ));
        LinkedHashSet<String> failedTaskIds = snapshot.taskRuns().stream()
                .filter(run -> run.status() == TaskRunStatus.FAILED)
                .map(TaskRun::taskId)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        LinkedHashSet<String> blockerTicketTaskIds = snapshot.tickets().stream()
                .filter(ticket -> ticket.taskId() != null && !ticket.taskId().isBlank())
                .filter(ticket -> ticket.blockingScope().name().equals("TASK_BLOCKING"))
                .collect(java.util.stream.Collectors.mapping(
                        ticket -> ticket.taskId(),
                        java.util.stream.Collectors.toCollection(LinkedHashSet::new)
                ));
        long unfinishedWorkspaces = snapshot.workspaces().stream()
                .filter(workspace -> !workspace.cleanupStatus().name().equals("DONE"))
                .count();
        List<String> blockedFailedTaskIds = failedTaskIds.stream()
                .filter(taskId -> Objects.equals(taskStatuses.get(taskId), "BLOCKED"))
                .toList();
        List<String> blockedFailedTaskIdsWithoutTicket = blockedFailedTaskIds.stream()
                .filter(taskId -> !blockerTicketTaskIds.contains(taskId))
                .toList();
        if (!blockedFailedTaskIdsWithoutTicket.isEmpty()) {
            findings.add(new EvalFinding(
                    "failed-run-without-task-blocking-ticket",
                    EvalFindingSeverity.ERROR,
                    "失败运行没有升级成 task blocker 证据",
                    "检测到失败后进入 BLOCKED 的 task " + blockedFailedTaskIdsWithoutTicket
                            + "，但没有对应 task blocker ticket。",
                    List.of("taskRuns", "tickets")
            ));
        }
        List<String> recoveredFailedTaskIds = failedTaskIds.stream()
                .filter(taskId -> !blockedFailedTaskIds.contains(taskId))
                .toList();
        if (!recoveredFailedTaskIds.isEmpty()) {
            findings.add(new EvalFinding(
                    "retryable-failed-run-observed",
                    EvalFindingSeverity.WARN,
                    "检测到已回收的失败运行",
                    "检测到失败 task run，但对应 task 当前状态为 "
                            + recoveredFailedTaskIds.stream()
                            .map(taskId -> taskId + ":" + taskStatuses.getOrDefault(taskId, "UNKNOWN"))
                            .toList()
                            + "，说明 runtime 已回收到可重试或后续阶段。",
                    List.of("taskRuns", "tasks")
            ));
        }
        if (unfinishedWorkspaces > 0) {
            findings.add(new EvalFinding(
                    "workspace-cleanup-pending",
                    EvalFindingSeverity.WARN,
                    "存在未完成 cleanup 的 workspace",
                    "检测到 " + unfinishedWorkspaces + " 个 workspace 的 cleanupStatus 不是 DONE。",
                    List.of("workspaces")
            ));
        }
        return dimension(
                EvalDimensionId.RUNTIME_ROBUSTNESS,
                findings,
                100 - errorCount(findings) * 25 - warnCount(findings) * 10,
                "失败 run=" + failedRuns + "，待 cleanup workspace=" + unfinishedWorkspaces
                        + (terminalRunnerStepType.isPresent() ? "，runner终态=" + terminalRunnerStepType.orElseThrow() : "") + "。",
                Map.of(
                        "failedRunCount", failedRuns,
                        "failedTaskCount", failedTaskIds.size(),
                        "blockedFailedTaskCount", blockedFailedTaskIds.size(),
                        "recoveredFailedTaskCount", recoveredFailedTaskIds.size(),
                        "activeRunCount", snapshot.taskRuns().stream().filter(run -> run.status() == TaskRunStatus.RUNNING || run.status() == TaskRunStatus.QUEUED).count(),
                        "pendingCleanupCount", unfinishedWorkspaces,
                        "runnerStatus", stringValue(workflowResult.get("runnerStatus")),
                        "terminalRunnerStepType", terminalRunnerStepType.orElse("")
                )
        );
    }

    private Map<String, TaskRun> latestTaskRunsByTaskId(List<TaskRun> taskRuns) {
        Map<String, TaskRun> latestRuns = new LinkedHashMap<>();
        taskRuns.stream()
                .sorted(Comparator
                        .comparing(TaskRun::startedAt, Comparator.nullsFirst(LocalDateTime::compareTo))
                        .thenComparing(TaskRun::finishedAt, Comparator.nullsFirst(LocalDateTime::compareTo))
                        .thenComparing(TaskRun::runId, Comparator.nullsFirst(String::compareTo)))
                .forEach(run -> latestRuns.put(run.taskId(), run));
        return latestRuns;
    }

    private Map<String, String> latestWorkspaceStateByTaskId(WorkflowRuntimeSnapshot snapshot) {
        Map<String, String> workspaceStateByTaskId = new LinkedHashMap<>();
        Map<String, TaskRun> latestRunsByTaskId = latestTaskRunsByTaskId(snapshot.taskRuns());
        Map<String, GitWorkspaceStatus> workspaceStatusesByRunId = snapshot.workspaces().stream()
                .collect(java.util.stream.Collectors.toMap(
                        workspace -> workspace.runId(),
                        workspace -> workspace.status(),
                        (left, right) -> right,
                        LinkedHashMap::new
                ));
        latestRunsByTaskId.forEach((taskId, run) -> {
            GitWorkspaceStatus status = workspaceStatusesByRunId.get(run.runId());
            if (status != null) {
                workspaceStateByTaskId.put(taskId, status.name());
            }
        });
        return workspaceStateByTaskId;
    }

    private EvalDimensionResult humanInLoopDimension(EvalEvidenceBundle evidence) {
        WorkflowRuntimeSnapshot snapshot = evidence.workflowSnapshot();
        List<EvalFinding> findings = new ArrayList<>();
        long humanTickets = snapshot.tickets().stream().filter(ticket -> ticket.assignee().type().name().equals("HUMAN")).count();
        long openHumanTickets = snapshot.tickets().stream()
                .filter(ticket -> ticket.assignee().type().name().equals("HUMAN") && ticket.status() == TicketStatus.OPEN)
                .count();
        if (snapshot.workflowRun().status().name().equals("COMPLETED") && openHumanTickets > 0) {
            findings.add(new EvalFinding(
                    "completed-workflow-has-open-human-ticket",
                    EvalFindingSeverity.ERROR,
                    "workflow 已完成但仍有 OPEN HUMAN ticket",
                    "完成态 workflow 仍保留 " + openHumanTickets + " 个 OPEN HUMAN ticket。",
                    List.of("tickets")
            ));
        }
        if (humanTickets > 1) {
            findings.add(new EvalFinding(
                    "multiple-human-interruptions",
                    EvalFindingSeverity.WARN,
                    "人工打断次数偏多",
                    "当前 workflow 共创建了 " + humanTickets + " 个 HUMAN ticket，可后续检查是否存在可避免追问。",
                    List.of("tickets")
            ));
        }
        return dimension(
                EvalDimensionId.HUMAN_IN_LOOP,
                findings,
                100 - errorCount(findings) * 25 - warnCount(findings) * 8,
                "workflow 共产生 " + humanTickets + " 个 HUMAN ticket。",
                Map.of(
                        "humanTicketCount", humanTickets,
                        "openHumanTicketCount", openHumanTickets,
                        "answeredTicketCount", snapshot.tickets().stream().filter(ticket -> ticket.status().name().equals("ANSWERED")).count()
                )
        );
    }

    private EvalDimensionResult efficiencyDimension(EvalEvidenceBundle evidence) {
        WorkflowRuntimeSnapshot snapshot = evidence.workflowSnapshot();
        List<EvalFinding> findings = new ArrayList<>();
        Map<String, Object> evaluationPlan = mapValue(evidence.supplementalArtifacts().get("evaluationPlan"));
        long fallbackCount = evaluationPlan.values().stream()
                .filter(Map.class::isInstance)
                .map(this::mapValue)
                .filter(selection -> !booleanValue(selection.get("useReal")))
                .count();
        if (fallbackCount > 0) {
            findings.add(new EvalFinding(
                    "node-fallback-rate",
                    EvalFindingSeverity.WARN,
                    "存在节点 fallback",
                    "本次 workflow 有 " + fallbackCount + " 个节点未直接接受真实输出。",
                    List.of("supplemental:evaluationPlan")
            ));
        }
        long codingTurns = evidence.taskRunEventsByRun().values().stream()
                .flatMap(Collection::stream)
                .filter(event -> event.eventType().startsWith("CODING_"))
                .count();
        long toolCalls = codingToolCalls(evidence).size();
        Duration duration = workflowDuration(snapshot.nodeRuns());
        return dimension(
                EvalDimensionId.EFFICIENCY_REGRESSION,
                findings,
                100 - warnCount(findings) * 10 - (codingTurns > 12 ? 10 : 0),
                "codingTurn=" + codingTurns + "，toolCall=" + toolCalls + "，duration=" + duration.toSeconds() + "s。",
                Map.of(
                        "codingTurnCount", codingTurns,
                        "toolCallCount", toolCalls,
                        "fallbackCount", fallbackCount,
                        "workflowDurationSeconds", duration.toSeconds(),
                        "comparison", Map.of(
                                "passAt1", snapshot.workflowRun().status().name().equals("COMPLETED") ? 1.0 : 0.0,
                                "baselineDelta", "N/A"
                        )
                )
        );
    }

    private Map<String, Object> rawEvidencePayload(
            EvalScenario scenario,
            EvalEvidenceBundle evidence,
            EvalScorecard scorecard,
            ResolvedArtifactPaths artifactPaths,
            ActiveStackProfileSnapshot activeProfile,
            Map<String, String> artifactRefs
    ) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("evaluationMetadata", evaluationMetadata(scenario, evidence, scorecard, artifactPaths, activeProfile));
        payload.put("profileSnapshot", profileSnapshotPayload(activeProfile));
        payload.put("scenario", scenario);
        payload.put("workflowSnapshot", evidence.workflowSnapshot());
        payload.put("taskRunEvents", normalizeTaskRunEvents(evidence.taskRunEventsByRun()));
        payload.put("contextArtifacts", evidence.contextArtifacts());
        payload.put("supplementalArtifacts", evidence.supplementalArtifacts());
        payload.put("artifactRefs", artifactRefs);
        Map<String, Object> scorecardSummary = new LinkedHashMap<>();
        scorecardSummary.put("overallStatus", scorecard.overallStatus().name());
        scorecardSummary.put("dimensionCount", scorecard.dimensions().size());
        scorecardSummary.put("hardGateCount", scorecard.hardGates().size());
        payload.put("scorecardSummary", scorecardSummary);
        return payload;
    }

    private String markdownReport(
            EvalScenario scenario,
            EvalEvidenceBundle evidence,
            EvalScorecard scorecard,
            ResolvedArtifactPaths artifactPaths,
            ActiveStackProfileSnapshot activeProfile,
            Map<String, String> artifactRefs
    ) {
        StringBuilder builder = new StringBuilder();
        WorkflowRuntimeSnapshot snapshot = evidence.workflowSnapshot();
        Map<String, Object> workflowResult = mapValue(evidence.supplementalArtifacts().get("workflowResult"));
        builder.append("# Workflow Eval Report").append(System.lineSeparator()).append(System.lineSeparator());
        builder.append("## 评测基本信息").append(System.lineSeparator());
        builder.append("- evaluationTime: `").append(scorecard.generatedAt()).append("`").append(System.lineSeparator());
        builder.append("- scenarioTitle: ").append(scenario.title()).append(System.lineSeparator());
        builder.append("- evaluationContent: `").append(scenario.requirementSeed()).append("`").append(System.lineSeparator());
        if (!scenario.description().isBlank()) {
            builder.append("- evaluationGoal: ").append(scenario.description()).append(System.lineSeparator());
        }
        builder.append("- workflowTitle: ").append(snapshot.workflowRun().title()).append(System.lineSeparator());
        builder.append("- workflowRunId: `").append(snapshot.workflowRun().workflowRunId()).append("`").append(System.lineSeparator());
        builder.append("- workflowStatus: `").append(snapshot.workflowRun().status().name()).append("`").append(System.lineSeparator());
        builder.append("- profileId: `").append(activeProfile.profileId()).append("`").append(System.lineSeparator());
        builder.append("- profileVersion: `").append(activeProfile.version()).append("`").append(System.lineSeparator());
        builder.append("- profileDigest: `").append(activeProfile.digest()).append("`").append(System.lineSeparator());
        builder.append("- profileLabels: `").append(activeProfile.manifest().reporting().labels()).append("`").append(System.lineSeparator());
        builder.append("- reportDirectory: `").append(artifactPaths.outputDirectory()).append("`").append(System.lineSeparator())
                .append(System.lineSeparator());

        builder.append("## 原始文件索引").append(System.lineSeparator());
        builder.append("- workflow-eval-report.md: `").append(artifactPaths.markdownPath()).append("`").append(System.lineSeparator());
        builder.append("- raw-evidence.json: `").append(artifactPaths.rawEvidencePath()).append("`").append(System.lineSeparator());
        builder.append("- scorecard.json: `").append(artifactPaths.scorecardPath()).append("`").append(System.lineSeparator());
        builder.append("- profile-snapshot.json: `").append(artifactPaths.profileSnapshotPath()).append("`").append(System.lineSeparator());
        for (Map.Entry<String, String> artifactRef : artifactRefs.entrySet()) {
            builder.append("- ").append(artifactRef.getKey()).append(": `").append(artifactRef.getValue()).append("`")
                    .append(System.lineSeparator());
        }
        builder.append(System.lineSeparator());

        builder.append("## 报告头").append(System.lineSeparator());
        builder.append("- scenarioId: `").append(scenario.scenarioId()).append("`").append(System.lineSeparator());
        builder.append("- workflowRunId: `").append(snapshot.workflowRun().workflowRunId()).append("`").append(System.lineSeparator());
        builder.append("- generatedAt: `").append(scorecard.generatedAt()).append("`").append(System.lineSeparator());
        builder.append("- workflowStatus: `").append(snapshot.workflowRun().status().name()).append("`").append(System.lineSeparator());
        builder.append("- profileId: `").append(activeProfile.profileId()).append("`").append(System.lineSeparator());
        builder.append("- profileVersion: `").append(activeProfile.version()).append("`").append(System.lineSeparator());
        builder.append("- profileDigest: `").append(activeProfile.digest()).append("`").append(System.lineSeparator());
        builder.append("- workspaceShape: `").append(activeProfile.workspaceShapeSummary()).append("`").append(System.lineSeparator());
        builder.append("- title: ").append(snapshot.workflowRun().title()).append(System.lineSeparator()).append(System.lineSeparator());

        builder.append("## 执行摘要").append(System.lineSeparator());
        builder.append("- overallStatus: `").append(scorecard.overallStatus().name()).append("`").append(System.lineSeparator());
        builder.append("- hardGateCount: `").append(scorecard.hardGates().size()).append("`").append(System.lineSeparator());
        if (!workflowResult.isEmpty()) {
            builder.append("- runnerStatus: `").append(stringValue(workflowResult.get("runnerStatus"))).append("`").append(System.lineSeparator());
            builder.append("- stopReason: ").append(stringValue(workflowResult.get("stopReason"))).append(System.lineSeparator());
            if (!stringValue(workflowResult.get("terminalError")).isBlank()) {
                builder.append("- terminalError: `").append(stringValue(workflowResult.get("terminalError"))).append("`").append(System.lineSeparator());
            }
        }
        builder.append(System.lineSeparator());
        builder.append("| 维度 | 状态 | 分数 | 摘要 |").append(System.lineSeparator());
        builder.append("| --- | --- | ---: | --- |").append(System.lineSeparator());
        for (EvalDimensionResult result : scorecard.dimensions()) {
            builder.append("| ").append(result.dimensionId().name())
                    .append(" | ").append(result.status().name())
                    .append(" | ").append(result.score())
                    .append(" | ").append(result.summary().replace('\n', ' '))
                    .append(" |").append(System.lineSeparator());
        }
        builder.append(System.lineSeparator());

        builder.append("## Workflow 全流程时间线").append(System.lineSeparator());
        List<WorkflowNodeRun> orderedNodeRuns = snapshot.nodeRuns().stream()
                .sorted(Comparator.comparing(WorkflowNodeRun::startedAt))
                .toList();
        if (orderedNodeRuns.isEmpty()) {
            builder.append("- 无 node run 记录").append(System.lineSeparator());
        } else {
            for (WorkflowNodeRun nodeRun : orderedNodeRuns) {
                builder.append("- `").append(nodeRun.nodeId()).append("` -> `")
                        .append(nodeRun.status().name()).append("` @ ").append(nodeRun.startedAt());
                if (nodeRun.finishedAt() != null) {
                    builder.append(" ~ ").append(nodeRun.finishedAt());
                }
                builder.append(System.lineSeparator());
            }
        }
        builder.append(System.lineSeparator());

        builder.append("## 维度详细评测").append(System.lineSeparator());
        for (EvalDimensionResult result : scorecard.dimensions()) {
            builder.append("### ").append(result.dimensionId().name()).append(System.lineSeparator());
            builder.append("- status: `").append(result.status().name()).append("`").append(System.lineSeparator());
            builder.append("- score: `").append(result.score()).append("`").append(System.lineSeparator());
            builder.append("- summary: ").append(result.summary()).append(System.lineSeparator());
            if (!result.metrics().isEmpty()) {
                builder.append("- metrics: `").append(oneLineJson(result.metrics())).append("`").append(System.lineSeparator());
            }
            if (result.findings().isEmpty()) {
                builder.append("- findings: none").append(System.lineSeparator());
            } else {
                for (EvalFinding finding : result.findings()) {
                    builder.append("- [").append(finding.severity().name()).append("] ")
                            .append(finding.title()).append(": ")
                            .append(finding.detail()).append(System.lineSeparator());
                }
            }
            builder.append(System.lineSeparator());
        }

        builder.append("## 节点专项章节").append(System.lineSeparator());
        Map<String, Object> evaluationPlan = mapValue(evidence.supplementalArtifacts().get("evaluationPlan"));
        if (!evaluationPlan.isEmpty()) {
            for (String nodeKey : List.of("requirementFirst", "requirementSecond", "architect", "codingImplementation", "codingTests", "verify")) {
                if (evaluationPlan.containsKey(nodeKey)) {
                    builder.append("### ").append(nodeKey).append(System.lineSeparator());
                    builder.append("- selection: `").append(oneLineJson(evaluationPlan.get(nodeKey))).append("`")
                            .append(System.lineSeparator()).append(System.lineSeparator());
                }
            }
        } else {
            Map<String, WorkflowNodeRun> latestNodeRuns = latestNodeRuns(snapshot.nodeRuns());
            if (latestNodeRuns.isEmpty()) {
                builder.append("- 无节点专项证据").append(System.lineSeparator()).append(System.lineSeparator());
            } else {
                for (Map.Entry<String, WorkflowNodeRun> entry : latestNodeRuns.entrySet()) {
                    builder.append("### ").append(entry.getKey()).append(System.lineSeparator());
                    builder.append("- status: `").append(entry.getValue().status().name()).append("`").append(System.lineSeparator());
                    builder.append("- startedAt: `").append(entry.getValue().startedAt()).append("`").append(System.lineSeparator());
                    builder.append("- finishedAt: `").append(entry.getValue().finishedAt()).append("`").append(System.lineSeparator());
                    builder.append("- outputPayload: `").append(oneLineJson(payloadMap(entry.getValue().outputPayloadJson()))).append("`")
                            .append(System.lineSeparator()).append(System.lineSeparator());
                }
            }
        }

        builder.append("## DAG 专项").append(System.lineSeparator());
        PlanningGraphSpec planningGraph = planningGraph(evidence);
        if (planningGraph == null) {
            builder.append("- 未找到 planning graph").append(System.lineSeparator());
        } else {
            builder.append("- modules: `").append(planningGraph.modules().size()).append("`").append(System.lineSeparator());
            builder.append("- tasks: `").append(planningGraph.tasks().size()).append("`").append(System.lineSeparator());
            builder.append("- dependencies: `").append(planningGraph.dependencies().size()).append("`").append(System.lineSeparator());
            for (PlanningGraphSpec.TaskPlan taskPlan : planningGraph.tasks()) {
                builder.append("- task `").append(taskPlan.taskKey())
                        .append("` / template `").append(taskPlan.taskTemplateId())
                        .append("` / capability `").append(taskPlan.capabilityPackId())
                        .append("` / writeScopes `").append(taskPlan.writeScopes().stream().map(WriteScope::path).toList())
                        .append("`").append(System.lineSeparator());
            }
        }
        builder.append(System.lineSeparator());

        builder.append("## RAG 专项").append(System.lineSeparator());
        builder.append("- expectedFacts: `").append(scenario.expectedFacts()).append("`").append(System.lineSeparator());
        builder.append("- expectedSnippetRefs: `").append(scenario.expectedSnippetRefs()).append("`").append(System.lineSeparator());
        for (WorkflowEvalContextArtifact artifact : evidence.contextArtifacts()) {
            builder.append("- ").append(artifact.packType().name())
                    .append(" / ").append(Optional.ofNullable(artifact.originNodeId()).orElse("unknown"))
                    .append(" / snippets=").append(artifact.retrievalSnippets().size())
                    .append(" / artifactRef=`").append(artifact.artifactRef()).append("`")
                    .append(System.lineSeparator());
        }
        builder.append(System.lineSeparator());

        builder.append("## 工具与工件专项").append(System.lineSeparator());
        for (Map<String, Object> toolCall : codingToolCalls(evidence)) {
            builder.append("- toolCall `").append(stringValue(toolCall.get("callId"))).append("` ")
                    .append(stringValue(toolCall.get("toolId"))).append(".")
                    .append(stringValue(toolCall.get("operation"))).append(" args=`")
                    .append(oneLineJson(mapValue(toolCall.get("arguments")))).append("`")
                    .append(System.lineSeparator());
        }
        String reviewBundlePath = stringValue(artifactRefs.get("reviewBundle"));
        Map<String, Long> reviewBundleRoleCounts = !reviewBundlePath.isBlank()
                ? activeProfile.inspectReviewBundle(Path.of(reviewBundlePath))
                : Map.of();
        builder.append("- reviewBundleRoleCounts: `").append(oneLineJson(reviewBundleRoleCounts)).append("`").append(System.lineSeparator());
        builder.append("- requiredArtifactRoles: `").append(activeProfile.manifest().eval().requiredArtifactRoles()).append("`")
                .append(System.lineSeparator());
        for (Map.Entry<String, String> artifactRef : artifactRefs.entrySet()) {
            builder.append("- artifact `").append(artifactRef.getKey()).append("`: `")
                    .append(artifactRef.getValue()).append("`").append(System.lineSeparator());
        }
        builder.append(System.lineSeparator());

        builder.append("## 优化候选项").append(System.lineSeparator());
        for (EvalFinding finding : scorecard.findings()) {
            builder.append("- [").append(finding.severity().name()).append("] ")
                    .append(finding.title()).append(": ")
                    .append(finding.code()).append(System.lineSeparator());
        }
        return builder.toString();
    }

    private Map<String, WorkflowNodeRun> latestNodeRuns(List<WorkflowNodeRun> nodeRuns) {
        Map<String, WorkflowNodeRun> latest = new LinkedHashMap<>();
        nodeRuns.stream()
                .sorted(Comparator.comparing(WorkflowNodeRun::startedAt))
                .forEach(nodeRun -> latest.put(nodeRun.nodeId(), nodeRun));
        return latest;
    }

    private List<Map<String, Object>> codingToolCalls(EvalEvidenceBundle evidence) {
        List<Map<String, Object>> toolCalls = new ArrayList<>();
        for (List<TaskRunEvent> events : evidence.taskRunEventsByRun().values()) {
            for (TaskRunEvent event : events) {
                if (!event.eventType().equals("CODING_TURN_COMPLETED") && !event.eventType().equals("CODING_TURN_REUSED")) {
                    continue;
                }
                Map<String, Object> payload = payloadMap(event.dataJson());
                Map<String, Object> decision = mapValue(payload.get("decision"));
                if (!"TOOL_CALL".equals(stringValue(decision.get("decisionType")))) {
                    continue;
                }
                Map<String, Object> toolCall = new LinkedHashMap<>(mapValue(decision.get("toolCall")));
                toolCall.put("toolCallReused", booleanValue(payload.get("toolCallReused")));
                toolCalls.add(toolCall);
            }
        }
        return toolCalls;
    }

    private PlanningGraphSpec planningGraph(EvalEvidenceBundle evidence) {
        Map<String, Object> evaluationPlan = mapValue(evidence.supplementalArtifacts().get("evaluationPlan"));
        Map<String, Object> architect = mapValue(evaluationPlan.get("architect"));
        Map<String, Object> selectedValue = mapValue(architect.get("selectedValue"));
        if (selectedValue.containsKey("planningGraph")) {
            PlanningGraphSpec planningGraph = objectMapper.convertValue(selectedValue.get("planningGraph"), PlanningGraphSpec.class);
            if (hasPlanningContent(planningGraph) || evidence.workflowSnapshot().tasks().isEmpty()) {
                return planningGraph;
            }
        }
        return evidence.workflowSnapshot().nodeRuns().stream()
                .filter(nodeRun -> nodeRun.nodeId().equals("architect"))
                .sorted(Comparator.comparing(WorkflowNodeRun::startedAt).reversed())
                .map(nodeRun -> mapValue(payloadMap(nodeRun.outputPayloadJson()).get("planningGraph")))
                .filter(map -> !map.isEmpty())
                .map(map -> objectMapper.convertValue(map, PlanningGraphSpec.class))
                .filter(this::hasPlanningContent)
                .findFirst()
                .orElse(null);
    }

    private boolean hasPlanningContent(PlanningGraphSpec planningGraph) {
        return planningGraph != null
                && (!planningGraph.modules().isEmpty()
                || !planningGraph.tasks().isEmpty()
                || !planningGraph.dependencies().isEmpty());
    }

    private Map<String, Object> normalizeTaskRunEvents(Map<String, List<TaskRunEvent>> eventsByRun) {
        Map<String, Object> normalized = new LinkedHashMap<>();
        for (Map.Entry<String, List<TaskRunEvent>> entry : eventsByRun.entrySet()) {
            normalized.put(entry.getKey(), entry.getValue().stream()
                    .map(event -> Map.of(
                            "eventId", event.eventId(),
                            "eventType", event.eventType(),
                            "body", event.body(),
                            "data", payloadMap(event.dataJson())
                    ))
                    .toList());
        }
        return normalized;
    }

    private boolean containsFact(List<WorkflowEvalContextArtifact> artifacts, String expectedFact) {
        String normalized = expectedFact.toLowerCase(Locale.ROOT);
        return artifacts.stream().anyMatch(artifact -> containsValue(artifact.factSections(), normalized)
                || artifact.retrievalSnippets().stream().anyMatch(snippet ->
                snippet.excerpt().toLowerCase(Locale.ROOT).contains(normalized)
                        || snippet.title().toLowerCase(Locale.ROOT).contains(normalized)));
    }

    private boolean containsSnippetRef(List<WorkflowEvalContextArtifact> artifacts, String expectedSnippetRef) {
        String normalized = expectedSnippetRef.toLowerCase(Locale.ROOT);
        return artifacts.stream().flatMap(artifact -> artifact.retrievalSnippets().stream())
                .anyMatch(snippet -> snippet.sourceRef().toLowerCase(Locale.ROOT).contains(normalized)
                        || snippet.title().toLowerCase(Locale.ROOT).contains(normalized));
    }

    private boolean containsValue(Object candidate, String expectedLowerCase) {
        if (candidate == null) {
            return false;
        }
        if (candidate instanceof Map<?, ?> mapValue) {
            return mapValue.values().stream().anyMatch(value -> containsValue(value, expectedLowerCase));
        }
        if (candidate instanceof Iterable<?> iterable) {
            for (Object value : iterable) {
                if (containsValue(value, expectedLowerCase)) {
                    return true;
                }
            }
            return false;
        }
        return String.valueOf(candidate).toLowerCase(Locale.ROOT).contains(expectedLowerCase);
    }

    private boolean containsOrderedSubsequence(List<String> seenOrder, List<String> expectedOrder) {
        int cursor = 0;
        for (String seen : seenOrder) {
            if (cursor < expectedOrder.size() && expectedOrder.get(cursor).equals(seen)) {
                cursor++;
            }
        }
        return cursor == expectedOrder.size();
    }

    private int maxDepth(Map<String, List<String>> adjacency, Map<String, Integer> indegree, boolean hasCycle) {
        return hasCycle ? -1 : criticalPath(adjacency, indegree, false);
    }

    private int criticalPath(Map<String, List<String>> adjacency, Map<String, Integer> indegree, boolean hasCycle) {
        if (hasCycle || adjacency.isEmpty()) {
            return -1;
        }
        Map<String, Integer> indegreeCopy = new LinkedHashMap<>(indegree);
        Deque<String> queue = new ArrayDeque<>();
        Map<String, Integer> distance = new HashMap<>();
        indegreeCopy.forEach((taskKey, degree) -> {
            if (degree == 0) {
                queue.add(taskKey);
                distance.put(taskKey, 1);
            }
        });
        int best = 0;
        while (!queue.isEmpty()) {
            String current = queue.removeFirst();
            best = Math.max(best, distance.getOrDefault(current, 1));
            for (String downstream : adjacency.getOrDefault(current, List.of())) {
                distance.put(downstream, Math.max(distance.getOrDefault(downstream, 1), distance.getOrDefault(current, 1) + 1));
                indegreeCopy.computeIfPresent(downstream, (key, value) -> value - 1);
                if (indegreeCopy.get(downstream) == 0) {
                    queue.addLast(downstream);
                }
            }
        }
        return best;
    }

    private boolean hasCycle(Map<String, List<String>> adjacency, Map<String, Integer> indegree) {
        Map<String, Integer> indegreeCopy = new LinkedHashMap<>(indegree);
        Deque<String> queue = new ArrayDeque<>();
        indegreeCopy.forEach((taskKey, degree) -> {
            if (degree == 0) {
                queue.add(taskKey);
            }
        });
        int visited = 0;
        while (!queue.isEmpty()) {
            String current = queue.removeFirst();
            visited++;
            for (String downstream : adjacency.getOrDefault(current, List.of())) {
                indegreeCopy.computeIfPresent(downstream, (key, value) -> value - 1);
                if (indegreeCopy.get(downstream) == 0) {
                    queue.addLast(downstream);
                }
            }
        }
        return visited != adjacency.size();
    }

    private Duration workflowDuration(List<WorkflowNodeRun> nodeRuns) {
        Optional<LocalDateTime> started = nodeRuns.stream().map(WorkflowNodeRun::startedAt).min(LocalDateTime::compareTo);
        Optional<LocalDateTime> finished = nodeRuns.stream()
                .map(nodeRun -> nodeRun.finishedAt() == null ? nodeRun.startedAt() : nodeRun.finishedAt())
                .max(LocalDateTime::compareTo);
        if (started.isEmpty() || finished.isEmpty()) {
            return Duration.ZERO;
        }
        return Duration.between(started.orElseThrow(), finished.orElseThrow());
    }

    private EvalDimensionResult dimension(
            EvalDimensionId dimensionId,
            List<EvalFinding> findings,
            int rawScore,
            String summary,
            Map<String, Object> metrics
    ) {
        return new EvalDimensionResult(
                dimensionId,
                statusFor(findings),
                boundedScore(rawScore),
                summary,
                findings,
                metrics
        );
    }

    private EvalStatus statusFor(List<EvalFinding> findings) {
        if (findings.stream().anyMatch(finding -> finding.severity() == EvalFindingSeverity.ERROR)) {
            return EvalStatus.FAIL;
        }
        if (findings.stream().anyMatch(finding -> finding.severity() == EvalFindingSeverity.WARN)) {
            return EvalStatus.WARN;
        }
        return EvalStatus.PASS;
    }

    private int errorCount(List<EvalFinding> findings) {
        return (int) findings.stream().filter(finding -> finding.severity() == EvalFindingSeverity.ERROR).count();
    }

    private int warnCount(List<EvalFinding> findings) {
        return (int) findings.stream().filter(finding -> finding.severity() == EvalFindingSeverity.WARN).count();
    }

    private int boundedScore(int rawScore) {
        return Math.max(0, Math.min(100, rawScore));
    }

    private double ratio(int numerator, int denominator) {
        if (denominator <= 0) {
            return 1.0;
        }
        return (double) numerator / denominator;
    }

    private String percentage(double value) {
        return String.format(Locale.ROOT, "%.0f%%", value * 100.0);
    }

    private Map<String, Object> payloadMap(JsonPayload payload) {
        if (payload == null) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(payload.json(), MAP_TYPE);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("failed to parse payload json", exception);
        }
    }

    private Map<String, Object> mapValue(Object value) {
        if (value == null) {
            return Map.of();
        }
        if (value instanceof Map<?, ?> mapValue) {
            Map<String, Object> normalized = new LinkedHashMap<>();
            mapValue.forEach((key, candidate) -> normalized.put(String.valueOf(key), candidate));
            return normalized;
        }
        return artifactObjectMapper.convertValue(value, MAP_TYPE);
    }

    private List<Map<String, Object>> listOfMaps(Object value) {
        if (value == null) {
            return List.of();
        }
        if (value instanceof List<?> listValue && listValue.isEmpty()) {
            return List.of();
        }
        return artifactObjectMapper.convertValue(value, LIST_OF_MAPS_TYPE);
    }

    private boolean booleanValue(Object value) {
        if (value instanceof Boolean booleanValue) {
            return booleanValue;
        }
        return Boolean.parseBoolean(String.valueOf(value));
    }

    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private String sanitize(String rawValue) {
        return rawValue.replaceAll("[^a-zA-Z0-9._-]", "-");
    }

    private Map<String, Object> workflowResult(EvalEvidenceBundle evidence) {
        return mapValue(evidence.supplementalArtifacts().get("workflowResult"));
    }

    private Optional<String> terminalRunnerStepType(EvalEvidenceBundle evidence) {
        return listOfMaps(workflowResult(evidence).get("stepHistory")).stream()
                .map(step -> stringValue(step.get("stepType")))
                .filter(stepType -> !stepType.isBlank())
                .reduce((left, right) -> right);
    }

    private void writeJson(Path path, Object payload) throws IOException {
        Files.writeString(path, artifactObjectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(payload), StandardCharsets.UTF_8);
    }

    private ActiveStackProfileSnapshot resolveActiveProfile(WorkflowRuntimeSnapshot snapshot) {
        WorkflowProfileRef profileRef = snapshot.workflowProfile()
                .orElseGet(() -> taskTemplateCatalog.defaultProfile().toProfileRef());
        return taskTemplateCatalog.resolveProfile(profileRef.profileId());
    }

    private Map<String, String> mergedArtifactRefs(Map<String, String> evidenceArtifactRefs, ResolvedArtifactPaths artifactPaths) {
        Map<String, String> merged = new LinkedHashMap<>(evidenceArtifactRefs);
        merged.put("reportDirectory", artifactPaths.outputDirectory().toString());
        merged.put("rawEvidence", artifactPaths.rawEvidencePath().toString());
        merged.put("scorecard", artifactPaths.scorecardPath().toString());
        merged.put("workflowEvalReport", artifactPaths.markdownPath().toString());
        merged.put("profileSnapshot", artifactPaths.profileSnapshotPath().toString());
        return Map.copyOf(merged);
    }

    private Object profileSnapshotPayload(ActiveStackProfileSnapshot activeProfile) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("profileId", activeProfile.profileId());
        payload.put("displayName", activeProfile.displayName());
        payload.put("version", activeProfile.version());
        payload.put("digest", activeProfile.digest());
        payload.put("workspaceShape", activeProfile.manifest().identity().workspaceShape());
        payload.put("packageManager", activeProfile.manifest().identity().packageManager());
        payload.put("manifest", activeProfile.manifest());
        return payload;
    }

    private ResolvedArtifactPaths resolveArtifactPaths(String scenarioId, String runLabel) {
        String runStamp = RUN_STAMP_FORMAT.format(LocalDateTime.now()) + "-" + sanitize(runLabel);
        Path outputDirectory = properties.getArtifactRoot().resolve(scenarioId).resolve(runStamp).toAbsolutePath().normalize();
        return new ResolvedArtifactPaths(
                outputDirectory,
                outputDirectory.resolve("raw-evidence.json"),
                outputDirectory.resolve("scorecard.json"),
                outputDirectory.resolve("workflow-eval-report.md"),
                outputDirectory.resolve("profile-snapshot.json")
        );
    }

    private Map<String, Object> evaluationMetadata(
            EvalScenario scenario,
            EvalEvidenceBundle evidence,
            EvalScorecard scorecard,
            ResolvedArtifactPaths artifactPaths,
            ActiveStackProfileSnapshot activeProfile
    ) {
        WorkflowRuntimeSnapshot snapshot = evidence.workflowSnapshot();
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("generatedAt", scorecard.generatedAt());
        metadata.put("scenarioId", scenario.scenarioId());
        metadata.put("scenarioTitle", scenario.title());
        metadata.put("evaluationContent", scenario.requirementSeed());
        metadata.put("evaluationGoal", scenario.description());
        metadata.put("workflowTitle", snapshot.workflowRun().title());
        metadata.put("workflowRunId", snapshot.workflowRun().workflowRunId());
        metadata.put("workflowStatus", snapshot.workflowRun().status().name());
        metadata.put("profileId", activeProfile.profileId());
        metadata.put("profileVersion", activeProfile.version());
        metadata.put("profileDigest", activeProfile.digest());
        metadata.put("profileDisplayName", activeProfile.displayName());
        metadata.put("workspaceShape", activeProfile.manifest().identity().workspaceShape());
        metadata.put("packageManager", activeProfile.manifest().identity().packageManager());
        metadata.put("reportDirectory", artifactPaths.outputDirectory().toString());
        metadata.put("profileSnapshotPath", artifactPaths.profileSnapshotPath().toString());
        return metadata;
    }

    private Object augmentRawEvidence(Object rawEvidence, ResolvedArtifactPaths artifactPaths) {
        if (!(rawEvidence instanceof Map<?, ?> rawEvidenceMap)) {
            return rawEvidence;
        }
        Map<String, Object> enriched = new LinkedHashMap<>();
        rawEvidenceMap.forEach((key, value) -> enriched.put(String.valueOf(key), value));
        Map<String, String> reportArtifacts = new LinkedHashMap<>();
        reportArtifacts.put("outputDirectory", artifactPaths.outputDirectory().toString());
        reportArtifacts.put("rawEvidence", artifactPaths.rawEvidencePath().toString());
        reportArtifacts.put("scorecard", artifactPaths.scorecardPath().toString());
        reportArtifacts.put("workflowEvalReport", artifactPaths.markdownPath().toString());
        reportArtifacts.put("profileSnapshot", artifactPaths.profileSnapshotPath().toString());
        enriched.put("reportArtifacts", reportArtifacts);
        return enriched;
    }

    private List<String> missingRequiredRoles(ActiveStackProfileSnapshot activeProfile, Map<String, Long> roleCounts) {
        return activeProfile.manifest().eval().requiredArtifactRoles().stream()
                .filter(role -> roleCounts.getOrDefault(role, 0L) <= 0)
                .toList();
    }

    private String formatRoleCounts(Map<String, Long> roleCounts) {
        if (roleCounts.isEmpty()) {
            return "{}";
        }
        return roleCounts.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> entry.getKey() + "=" + entry.getValue())
                .reduce((left, right) -> left + ", " + right)
                .orElse("{}");
    }

    private String oneLineJson(Object payload) {
        try {
            return artifactObjectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException exception) {
            return String.valueOf(payload);
        }
    }

    private record ResolvedArtifactPaths(
            Path outputDirectory,
            Path rawEvidencePath,
            Path scorecardPath,
            Path markdownPath,
            Path profileSnapshotPath
    ) {
    }
}
