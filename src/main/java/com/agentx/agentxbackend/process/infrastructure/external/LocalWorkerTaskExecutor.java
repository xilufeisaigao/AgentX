package com.agentx.agentxbackend.process.infrastructure.external;

import com.agentx.agentxbackend.execution.domain.model.RunKind;
import com.agentx.agentxbackend.execution.domain.model.TaskPackage;
import com.agentx.agentxbackend.process.application.port.in.RuntimeLlmConfigUseCase;
import com.agentx.agentxbackend.process.application.port.out.WorkerTaskExecutorPort;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.openai.OpenAiChatModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

@Component
public class LocalWorkerTaskExecutor implements WorkerTaskExecutorPort {

    private static final String MOCK_PROVIDER = "mock";
    private static final String BAILIAN_PROVIDER = "bailian";
    private static final String LANGCHAIN4J_FRAMEWORK = "langchain4j";
    private static final String WORKTREES_PREFIX = "worktrees/";
    private static final int MAX_CAPTURED_PROCESS_OUTPUT_CHARS = 256_000;
    private static final int MAX_WORKSPACE_FILES_TO_SCORE = 6_000;
    private static final int MAX_WORKSPACE_PATH_CANDIDATES_FOR_CONTENT = 240;
    private static final int MAX_WORKSPACE_QUERY_CHARS = 2_200;
    private static final int MAX_WORKSPACE_RELEVANCE_HINT_TERMS = 24;
    private static final Pattern QUERY_IDENTIFIER_PATTERN = Pattern.compile("[A-Za-z_][A-Za-z0-9_]{2,}");
    private static final Pattern QUERY_CHINESE_PHRASE_PATTERN = Pattern.compile("[\\p{IsHan}]{2,}");

    private final ObjectMapper objectMapper;
    private final RuntimeLlmConfigUseCase runtimeLlmConfigUseCase;
    private final String gitExecutable;
    private final Path repoRoot;
    private final String sessionRepoPrefix;
    private final int commandTimeoutMs;
    private final int maxEditsPerRun;
    private final Set<String> verifyCommandPrefixAllowlist;
    private final boolean verifyUseDocker;
    private final String verifyDockerExecutable;
    private final String verifyDockerImage;
    private final String verifyDockerMemory;
    private final String verifyDockerCpus;
    private final int verifyDockerPidsLimit;

    public LocalWorkerTaskExecutor(
        RuntimeLlmConfigUseCase runtimeLlmConfigUseCase,
        ObjectMapper objectMapper,
        @Value("${agentx.worker-runtime.git.executable:git}") String gitExecutable,
        @Value("${agentx.worker-runtime.repo-root:.}") String repoRoot,
        @Value("${agentx.workspace.git.session-repo-prefix:sessions}") String sessionRepoPrefix,
        @Value("${agentx.worker-runtime.execution.command-timeout-ms:${agentx.worker-runtime.command-timeout-ms:600000}}") int commandTimeoutMs,
        @Value("${agentx.worker-runtime.max-edits-per-run:20}") int maxEditsPerRun,
        @Value("${agentx.worker-runtime.verify.allowed-command-prefixes:mvn,./mvnw,gradle,./gradlew,python,pytest,git}") String verifyAllowedCommandPrefixes,
        @Value("${agentx.worker-runtime.verify.use-docker:false}") boolean verifyUseDocker,
        @Value("${agentx.worker-runtime.verify.docker.executable:docker}") String verifyDockerExecutable,
        @Value("${agentx.worker-runtime.verify.docker.image:maven:3.9.11-eclipse-temurin-21}") String verifyDockerImage,
        @Value("${agentx.worker-runtime.verify.docker.memory:1g}") String verifyDockerMemory,
        @Value("${agentx.worker-runtime.verify.docker.cpus:1.0}") String verifyDockerCpus,
        @Value("${agentx.worker-runtime.verify.docker.pids-limit:256}") int verifyDockerPidsLimit
    ) {
        this.runtimeLlmConfigUseCase = runtimeLlmConfigUseCase;
        this.objectMapper = objectMapper;
        this.gitExecutable = gitExecutable == null || gitExecutable.isBlank() ? "git" : gitExecutable.trim();
        this.repoRoot = Path.of(repoRoot == null || repoRoot.isBlank() ? "." : repoRoot.trim())
            .toAbsolutePath()
            .normalize();
        this.sessionRepoPrefix = normalizeRelativePrefix(sessionRepoPrefix, "sessions");
        this.commandTimeoutMs = Math.max(5_000, commandTimeoutMs);
        this.maxEditsPerRun = Math.max(1, maxEditsPerRun);
        this.verifyCommandPrefixAllowlist = parseVerifyCommandAllowlist(verifyAllowedCommandPrefixes);
        this.verifyUseDocker = verifyUseDocker;
        this.verifyDockerExecutable = verifyDockerExecutable == null || verifyDockerExecutable.isBlank()
            ? "docker"
            : verifyDockerExecutable.trim();
        this.verifyDockerImage = verifyDockerImage == null || verifyDockerImage.isBlank()
            ? "maven:3.9.11-eclipse-temurin-21"
            : verifyDockerImage.trim();
        this.verifyDockerMemory = verifyDockerMemory == null || verifyDockerMemory.isBlank()
            ? "1g"
            : verifyDockerMemory.trim();
        this.verifyDockerCpus = verifyDockerCpus == null || verifyDockerCpus.isBlank()
            ? "1.0"
            : verifyDockerCpus.trim();
        this.verifyDockerPidsLimit = Math.max(32, verifyDockerPidsLimit);
    }

    @Override
    public ExecutionResult execute(TaskPackage taskPackage) {
        if (taskPackage == null) {
            return ExecutionResult.failed("taskPackage must not be null");
        }
        Path worktree = resolveWorktree(taskPackage.git().worktreePath());
        if (!Files.exists(worktree)) {
            return ExecutionResult.failed("worktree does not exist: " + worktree);
        }
        if (taskPackage.runKind() == RunKind.VERIFY) {
            return executeVerify(taskPackage, worktree);
        }
        return executeImpl(taskPackage, worktree);
    }

    private ExecutionResult executeVerify(TaskPackage taskPackage, Path worktree) {
        if (taskPackage.verifyCommands() == null || taskPackage.verifyCommands().isEmpty()) {
            return ExecutionResult.failed("VERIFY run requires non-empty verify_commands");
        }
        StringBuilder report = new StringBuilder();
        for (String verifyCommand : taskPackage.verifyCommands()) {
            if (verifyCommand == null || verifyCommand.isBlank()) {
                continue;
            }
            String validationError = validateVerifyCommand(verifyCommand);
            if (validationError != null) {
                return ExecutionResult.failed(
                    "VERIFY command rejected by policy: " + verifyCommand + ", reason=" + validationError
                );
            }

            ProcessResult result;
            try {
                result = runVerifyCommand(verifyCommand, worktree, commandTimeoutMs);
            } catch (RuntimeException ex) {
                return ExecutionResult.failed(
                    "VERIFY command failed: " + verifyCommand + ", reason=" + ex.getMessage()
                );
            }
            if (result.exitCode() != 0) {
                return ExecutionResult.failed(
                    "VERIFY command failed: " + verifyCommand + ", output=" + result.stdout()
                );
            }
            if (!result.stdout().isBlank()) {
                report.append("[verify] ").append(verifyCommand).append('\n');
                report.append(trimForReport(result.stdout())).append('\n');
            }
        }
        List<String> unexpectedChanges = findUnexpectedVerifyChanges(worktree);
        if (!unexpectedChanges.isEmpty()) {
            return ExecutionResult.failed(
                "VERIFY run must be read-only, but worktree became dirty: "
                    + summarizeVerifyChanges(unexpectedChanges)
            );
        }
        String workReport = report.isEmpty()
            ? "VERIFY completed successfully."
            : "VERIFY completed successfully.\n" + report;
        return ExecutionResult.succeeded(workReport, null, null);
    }

    private ProcessResult runVerifyCommand(String command, Path worktree, int timeoutMs) {
        PreparedVerifyCommand preparedCommand = prepareVerifyCommand(command);
        try {
            if (!verifyUseDocker) {
                return runShell(preparedCommand.command(), worktree, timeoutMs);
            }
            return runVerifyInDocker(preparedCommand.command(), worktree, timeoutMs);
        } finally {
            preparedCommand.cleanup();
        }
    }

    private ProcessResult runVerifyInDocker(String command, Path worktree, int timeoutMs) {
        List<String> dockerCommand = new ArrayList<>();
        dockerCommand.add(verifyDockerExecutable);
        dockerCommand.add("run");
        dockerCommand.add("--rm");
        dockerCommand.add("--network");
        dockerCommand.add("none");
        dockerCommand.add("--cpus");
        dockerCommand.add(verifyDockerCpus);
        dockerCommand.add("--memory");
        dockerCommand.add(verifyDockerMemory);
        dockerCommand.add("--pids-limit");
        dockerCommand.add(String.valueOf(verifyDockerPidsLimit));
        dockerCommand.add("-v");
        dockerCommand.add(toDockerMountPath(worktree) + ":/workspace");
        dockerCommand.add("-w");
        dockerCommand.add("/workspace");
        dockerCommand.add(verifyDockerImage);
        dockerCommand.add("sh");
        dockerCommand.add("-lc");
        dockerCommand.add(command);
        return runProcess(dockerCommand, worktree, timeoutMs, Set.of(0));
    }

    private ExecutionResult executeImpl(TaskPackage taskPackage, Path worktree) {
        String outputLanguage = resolveOutputLanguage();
        String taskSkillText = readTaskSkill(taskPackage.taskSkillRef());
        JsonNode taskContextPack = readTaskContextPack(taskPackage.taskContextRef());
        String workspaceSnapshot = buildWorkspaceSnapshotForWorker(taskPackage, worktree, taskSkillText, outputLanguage);
        PlannerResult plannerResult = proposePlan(
            taskPackage,
            taskSkillText,
            taskContextPack,
            outputLanguage,
            workspaceSnapshot,
            false,
            null
        );
        if ("NEED_CLARIFICATION".equals(plannerResult.outcome()) && hasResolvedClarifications(taskPackage.taskContext())) {
            plannerResult = proposePlan(
                taskPackage,
                taskSkillText,
                taskContextPack,
                outputLanguage,
                workspaceSnapshot,
                true,
                plannerResult.message()
            );
        }
        if ("SUCCEEDED".equals(plannerResult.outcome()) && plannerResult.edits().isEmpty()) {
            plannerResult = retryPlannerWithForcedExecution(
                taskPackage,
                taskSkillText,
                taskContextPack,
                outputLanguage,
                workspaceSnapshot,
                localize(
                    outputLanguage,
                    "上一个规划结果没有返回任何可执行 edits。请直接检查现有脚手架与需求约束的冲突，并输出至少一组真实文件改动。",
                    "The previous plan returned no executable edits. Inspect the current scaffold against the requirement constraints and return at least one real file change."
                )
            );
        }
        if ("NEED_CLARIFICATION".equals(plannerResult.outcome())) {
            return ExecutionResult.needInput(
                "NEED_CLARIFICATION",
                defaultText(
                    plannerResult.message(),
                    localize(
                        outputLanguage,
                        "Worker 需要更多澄清信息才能继续执行。",
                        "Worker needs clarification before continuing."
                    )
                ),
                plannerResult.dataJson()
            );
        }
        if ("NEED_DECISION".equals(plannerResult.outcome())) {
            return ExecutionResult.needInput(
                "NEED_DECISION",
                defaultText(
                    plannerResult.message(),
                    localize(
                        outputLanguage,
                        "Worker 需要明确决策才能继续执行。",
                        "Worker needs a decision before continuing."
                    )
                ),
                plannerResult.dataJson()
            );
        }
        if ("FAILED".equals(plannerResult.outcome())) {
            return ExecutionResult.failed(
                defaultText(
                    plannerResult.message(),
                    localize(outputLanguage, "Worker 规划失败。", "Worker planner failed.")
                )
            );
        }

        List<FileEdit> edits = plannerResult.edits();
        if (edits.isEmpty()) {
            return ExecutionResult.needInput(
                "NEED_CLARIFICATION",
                localize(
                    outputLanguage,
                    "规划器未返回可执行的文件修改，请补充更明确的上下文或约束。",
                    "Planner returned no concrete file edits."
                ),
                plannerResult.dataJson()
            );
        }
        if (edits.size() > maxEditsPerRun) {
            return ExecutionResult.failed("Planner returned too many edits: " + edits.size());
        }
        List<String> rejectedEditPaths = new ArrayList<>();
        List<FileEdit> applicableEdits = filterApplicableEdits(edits, taskPackage.writeScope(), rejectedEditPaths);
        if (applicableEdits.isEmpty()) {
            Optional<ExecutionResult> alreadySatisfied = tryCompleteAlreadySatisfiedTask(
                taskPackage,
                worktree,
                outputLanguage
            );
            if (alreadySatisfied.isPresent()) {
                return alreadySatisfied.get();
            }
            return ExecutionResult.failed(
                "Planner returned only out-of-scope edits: " + String.join(", ", rejectedEditPaths)
            );
        }
        List<FileEdit> effectiveEdits = filterEffectiveEdits(applicableEdits, worktree);
        if (effectiveEdits.isEmpty()) {
            plannerResult = retryPlannerWithForcedExecution(
                taskPackage,
                taskSkillText,
                taskContextPack,
                outputLanguage,
                workspaceSnapshot,
                localize(
                    outputLanguage,
                    "上一个规划结果写回后没有产生任何文件差异。请直接修正当前仓库中不符合需求的文件，返回会真正改变内容的 edits。",
                    "The previous plan produced no file diff after application. Correct the files that still violate the requirement and return edits that will actually change content."
                )
            );
            if ("NEED_CLARIFICATION".equals(plannerResult.outcome())) {
                return ExecutionResult.needInput(
                    "NEED_CLARIFICATION",
                    defaultText(
                        plannerResult.message(),
                        localize(
                            outputLanguage,
                            "Worker 需要更多澄清信息才能继续执行。",
                            "Worker needs clarification before continuing."
                        )
                    ),
                    plannerResult.dataJson()
                );
            }
            if ("NEED_DECISION".equals(plannerResult.outcome())) {
                return ExecutionResult.needInput(
                    "NEED_DECISION",
                    defaultText(
                        plannerResult.message(),
                        localize(
                            outputLanguage,
                            "Worker 需要明确决策才能继续执行。",
                            "Worker needs a decision before continuing."
                        )
                    ),
                    plannerResult.dataJson()
                );
            }
            if ("FAILED".equals(plannerResult.outcome())) {
                return ExecutionResult.failed(
                    defaultText(
                        plannerResult.message(),
                        localize(outputLanguage, "Worker 规划失败。", "Worker planner failed.")
                    )
                );
            }
            rejectedEditPaths = new ArrayList<>();
            applicableEdits = filterApplicableEdits(plannerResult.edits(), taskPackage.writeScope(), rejectedEditPaths);
            if (applicableEdits.isEmpty()) {
                Optional<ExecutionResult> alreadySatisfied = tryCompleteAlreadySatisfiedTask(
                    taskPackage,
                    worktree,
                    outputLanguage
                );
                if (alreadySatisfied.isPresent()) {
                    return alreadySatisfied.get();
                }
                return ExecutionResult.failed(
                    "Planner returned only out-of-scope edits after forced retry: "
                        + String.join(", ", rejectedEditPaths)
                );
            }
            effectiveEdits = filterEffectiveEdits(applicableEdits, worktree);
            if (effectiveEdits.isEmpty()) {
                Optional<ExecutionResult> alreadySatisfied = tryCompleteAlreadySatisfiedTask(
                    taskPackage,
                    worktree,
                    outputLanguage
                );
                if (alreadySatisfied.isPresent()) {
                    return alreadySatisfied.get();
                }
                return ExecutionResult.needInput(
                    "NEED_CLARIFICATION",
                    localize(
                        outputLanguage,
                        "规划器连续两次都没有返回会产生实际代码变更的 edits，请检查任务拆分或约束是否过宽。",
                        "Planner failed twice to return edits that change the worktree."
                    ),
                    plannerResult.dataJson()
                );
            }
        }
        for (FileEdit edit : effectiveEdits) {
            Path targetPath = resolveEditPath(worktree, edit.path());
            try {
                if (targetPath.getParent() != null) {
                    Files.createDirectories(targetPath.getParent());
                }
                Files.writeString(targetPath, edit.content(), StandardCharsets.UTF_8);
            } catch (IOException ex) {
                return ExecutionResult.failed("Failed to write file: " + edit.path() + ", error=" + ex.getMessage());
            }
        }

        String deliveryCommit = createCommit(taskPackage.runId(), worktree);
        if (deliveryCommit == null) {
            return ExecutionResult.needInput(
                "NEED_CLARIFICATION",
                localize(
                    outputLanguage,
                    "应用规划修改后未产生代码变更，请确认任务目标或约束。",
                    "No code changes were produced after applying planner edits."
                ),
                null
            );
        }
        String workReport = defaultText(
            plannerResult.message(),
            "Implementation completed and commit created by worker runtime."
        );
        if (!rejectedEditPaths.isEmpty()) {
            workReport = workReport
                + "\nIgnored out-of-scope edits: "
                + String.join(", ", rejectedEditPaths);
        }
        return ExecutionResult.succeeded(workReport, deliveryCommit, null);
    }

    private Optional<ExecutionResult> tryCompleteAlreadySatisfiedTask(
        TaskPackage taskPackage,
        Path worktree,
        String outputLanguage
    ) {
        if (taskPackage == null || worktree == null) {
            return Optional.empty();
        }
        if (!"tmpl.test.v0".equals(normalizeTemplate(taskPackage.taskTemplateId()))) {
            return Optional.empty();
        }
        if (!hasExistingFileInScope(worktree, taskPackage.writeScope())) {
            return Optional.empty();
        }
        List<String> validationCommands = detectAlreadySatisfiedValidationCommands(taskPackage, worktree);
        if (validationCommands.isEmpty()) {
            return Optional.empty();
        }
        for (String validationCommand : validationCommands) {
            try {
                ProcessResult result = runVerifyCommand(validationCommand, worktree, commandTimeoutMs);
                if (result.exitCode() != 0) {
                    return Optional.empty();
                }
            } catch (RuntimeException ex) {
                return Optional.empty();
            }
        }
        String headCommit = resolveHeadCommit(worktree);
        if (headCommit == null || headCommit.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(ExecutionResult.succeeded(
            localize(
                outputLanguage,
                "仓库中的现有测试已满足当前任务要求，无需额外代码改动。",
                "Existing tests in the repository already satisfy this task, so no additional code change was required."
            ),
            headCommit,
            null
        ));
    }

    private PlannerResult proposePlan(
        TaskPackage taskPackage,
        String taskSkillText,
        JsonNode taskContextPack,
        String outputLanguage
    ) {
        return proposePlan(taskPackage, taskSkillText, taskContextPack, outputLanguage, "", false, null);
    }

    private PlannerResult proposePlan(
        TaskPackage taskPackage,
        String taskSkillText,
        JsonNode taskContextPack,
        String outputLanguage,
        String workspaceSnapshot,
        boolean forceExecutionMode,
        String previousClarificationQuestion
    ) {
        ActiveWorkerLlmConfig llmConfig = resolveActiveWorkerLlm(outputLanguage);
        if (MOCK_PROVIDER.equals(llmConfig.provider())) {
            return mockPlan(taskPackage, outputLanguage);
        }
        if (!BAILIAN_PROVIDER.equals(llmConfig.provider())) {
            return PlannerResult.needClarification(
                localize(
                    outputLanguage,
                    "不支持的 Worker LLM provider: " + llmConfig.provider(),
                    "Unsupported worker LLM provider: " + llmConfig.provider()
                ),
                null
            );
        }
        if (llmConfig.apiKey().isBlank()) {
            return PlannerResult.needClarification(
                localize(outputLanguage, "缺少 Worker LLM 的 api-key 配置。", "worker LLM api-key is missing."),
                null
            );
        }
        if (!LANGCHAIN4J_FRAMEWORK.equals(llmConfig.framework())) {
            return PlannerResult.needClarification(
                localize(
                    outputLanguage,
                    "不支持的 Worker LLM framework: " + llmConfig.framework(),
                    "Unsupported worker LLM framework: " + llmConfig.framework()
                ),
                null
            );
        }
        try {
            OpenAiChatModel chatModel = OpenAiChatModel.builder()
                .apiKey(llmConfig.apiKey())
                .baseUrl(normalizeBaseUrl(llmConfig.baseUrl()))
                .modelName(llmConfig.model())
                .temperature(0.1)
                .timeout(Duration.ofMillis(llmConfig.timeoutMs()))
                .build();
            ChatRequest chatRequest = ChatRequest.builder()
                .messages(List.of(
                    SystemMessage.from(buildSystemPrompt(llmConfig.outputLanguage(), forceExecutionMode)),
                    UserMessage.from(buildUserPrompt(
                        taskPackage,
                        taskSkillText,
                        taskContextPack,
                        llmConfig.outputLanguage(),
                        workspaceSnapshot,
                        forceExecutionMode,
                        previousClarificationQuestion
                    ))
                ))
                .build();
            ChatResponse chatResponse = chatModel.chat(chatRequest);
            String content = chatResponse == null || chatResponse.aiMessage() == null
                ? ""
                : chatResponse.aiMessage().text();
            if (content == null || content.isBlank()) {
                return PlannerResult.needClarification(
                    localize(outputLanguage, "LLM 返回为空。", "LLM response is blank."),
                    null
                );
            }
            PlannerResult parsed = parsePlannerResult(content);
            if (parsed == null) {
                return PlannerResult.needClarification(
                    localize(outputLanguage, "LLM 返回内容不是有效 JSON。", "LLM response is not valid JSON."),
                    null
                );
            }
            return parsed;
        } catch (RuntimeException ex) {
            return PlannerResult.needClarification(
                localize(
                    outputLanguage,
                    "Worker LLM 调用失败: " + ex.getMessage(),
                    "Worker LLM invocation failed: " + ex.getMessage()
                ),
                null
            );
        }
    }

    private PlannerResult parsePlannerResult(String rawContent) {
        String cleaned = stripMarkdownFence(rawContent);
        JsonNode root = parseJson(cleaned);
        if (root == null || !root.isObject()) {
            return null;
        }
        String outcome = readPlannerText(root, "outcome", "status", "result");
        if (outcome.isBlank()) {
            outcome = "SUCCEEDED";
        }
        outcome = outcome.trim().toUpperCase(Locale.ROOT);
        String message = readPlannerText(root, "message", "summary", "reason", "analysis").trim();
        JsonNode dataNode = firstPlannerNode(root, "data_json", "data", "details");
        String dataJson = dataNode == null ? null : dataNode.toString();

        List<FileEdit> edits = readPlannerEdits(root);

        return new PlannerResult(outcome, message, edits, dataJson);
    }

    private PlannerResult retryPlannerWithForcedExecution(
        TaskPackage taskPackage,
        String taskSkillText,
        JsonNode taskContextPack,
        String outputLanguage,
        String workspaceSnapshot,
        String reason
    ) {
        return proposePlan(
            taskPackage,
            taskSkillText,
            taskContextPack,
            outputLanguage,
            workspaceSnapshot,
            true,
            reason
        );
    }

    private PlannerResult mockPlan(TaskPackage taskPackage, String outputLanguage) {
        if (taskPackage.writeScope() == null || taskPackage.writeScope().isEmpty()) {
            return PlannerResult.needClarification(
                localize(outputLanguage, "任务没有可写入的 write_scope。", "No writable scope available for task."),
                null
            );
        }
        String targetPath = chooseMockTargetPath(taskPackage.writeScope());
        StringBuilder content = new StringBuilder();
        content.append("# AgentX Runtime Mock Output\n\n");
        content.append("- run_id: ").append(taskPackage.runId()).append('\n');
        content.append("- task_id: ").append(taskPackage.taskId()).append('\n');
        content.append("- task_template_id: ").append(taskPackage.taskTemplateId()).append('\n');
        content.append("- generated_by: mock_worker_runtime_planner\n");

        List<FileEdit> edits = List.of(new FileEdit(targetPath, content.toString()));
        return new PlannerResult(
            "SUCCEEDED",
            localize(
                outputLanguage,
                "Mock 规划器已生成一个最小可编辑文件。",
                "Mock planner generated one minimal editable file."
            ),
            edits,
            null
        );
    }

    private String chooseMockTargetPath(List<String> writeScope) {
        for (String scope : writeScope) {
            if (scope == null || scope.isBlank()) {
                continue;
            }
            String normalized = normalizeScope(scope);
            if (normalized.isEmpty() || ".".equals(normalized)) {
                return "AGENTX_AUTOGEN_NOTE.md";
            }
            if (normalized.endsWith("/")) {
                return normalized + "AGENTX_AUTOGEN_NOTE.md";
            }
            if (normalized.contains(".")) {
                return normalized;
            }
            return normalized + "/AGENTX_AUTOGEN_NOTE.md";
        }
        return "AGENTX_AUTOGEN_NOTE.md";
    }

    private Path resolveWorktree(String worktreePath) {
        if (worktreePath == null || worktreePath.isBlank()) {
            throw new IllegalArgumentException("worktreePath must not be blank");
        }
        String normalized = normalizeWorktreePath(worktreePath);
        Path sessionScoped = tryResolveSessionScopedWorktree(normalized, worktreePath);
        if (sessionScoped != null) {
            return sessionScoped;
        }
        Path legacy = repoRoot.resolve(normalized).toAbsolutePath().normalize();
        if (!legacy.startsWith(repoRoot)) {
            throw new IllegalArgumentException("worktreePath escapes repo root: " + worktreePath);
        }
        return legacy;
    }

    private Path tryResolveSessionScopedWorktree(String normalizedWorktreePath, String rawWorktreePath) {
        if (!normalizedWorktreePath.startsWith(WORKTREES_PREFIX)) {
            return null;
        }
        String sessionId = extractSessionIdFromWorktreePath(normalizedWorktreePath, rawWorktreePath);
        Path sessionRepoRoot = resolveSessionRepoPath(sessionId);
        Path resolved = sessionRepoRoot.resolve(normalizedWorktreePath).toAbsolutePath().normalize();
        if (!resolved.startsWith(sessionRepoRoot)) {
            throw new IllegalArgumentException("worktreePath escapes session repo root: " + rawWorktreePath);
        }
        if (Files.exists(resolved)) {
            return resolved;
        }
        Path legacy = repoRoot.resolve(normalizedWorktreePath).toAbsolutePath().normalize();
        if (!legacy.startsWith(repoRoot)) {
            throw new IllegalArgumentException("worktreePath escapes repo root: " + rawWorktreePath);
        }
        if (Files.exists(legacy)) {
            return legacy;
        }
        return resolved;
    }

    private String extractSessionIdFromWorktreePath(String normalizedWorktreePath, String rawWorktreePath) {
        String suffix = normalizedWorktreePath.substring(WORKTREES_PREFIX.length());
        int separatorIndex = suffix.indexOf('/');
        if (separatorIndex <= 0) {
            throw new IllegalArgumentException("worktreePath must include sessionId and runId: " + rawWorktreePath);
        }
        return suffix.substring(0, separatorIndex);
    }

    private Path resolveSessionRepoPath(String sessionId) {
        String safeSessionId = sessionId
            .toLowerCase(Locale.ROOT)
            .replaceAll("[^a-z0-9._\\-]+", "-")
            .replaceAll("^-+|-+$", "");
        if (safeSessionId.isBlank()) {
            throw new IllegalArgumentException("sessionId has no safe characters: " + sessionId);
        }
        Path sessionRepoPath = repoRoot
            .resolve(sessionRepoPrefix)
            .resolve(safeSessionId)
            .resolve("repo")
            .toAbsolutePath()
            .normalize();
        if (!sessionRepoPath.startsWith(repoRoot)) {
            throw new IllegalArgumentException("session repo path escapes repo root: " + sessionId);
        }
        return sessionRepoPath;
    }

    private Path resolveEditPath(Path worktree, String relativePath) {
        if (relativePath == null || relativePath.isBlank()) {
            throw new IllegalArgumentException("edit path must not be blank");
        }
        Path resolved = worktree.resolve(relativePath).toAbsolutePath().normalize();
        if (!resolved.startsWith(worktree)) {
            throw new IllegalArgumentException("edit path escapes worktree: " + relativePath);
        }
        return resolved;
    }

    private boolean isWriteScopeAllowed(String editPath, List<String> writeScope) {
        if (writeScope == null || writeScope.isEmpty()) {
            return false;
        }
        String normalizedEditPath = normalizePath(editPath);
        for (String scope : writeScope) {
            String normalizedScope = normalizeScope(scope);
            if (normalizedScope.isEmpty() || ".".equals(normalizedScope)) {
                return true;
            }
            if (normalizedEditPath.equals(normalizedScope)) {
                return true;
            }
            String prefix = normalizedScope.endsWith("/") ? normalizedScope : normalizedScope + "/";
            if (normalizedEditPath.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    private String createCommit(String runId, Path worktree) {
        runGit(List.of("add", "-A"), worktree, Set.of(0));
        ProcessResult hasStagedChanges = runGit(List.of("diff", "--cached", "--quiet"), worktree, Set.of(0, 1));
        if (hasStagedChanges.exitCode() == 0) {
            return null;
        }
        runGit(
            List.of("commit", "-m", "AgentX runtime commit for " + runId),
            worktree,
            Set.of(0)
        );
        return runGit(List.of("rev-parse", "HEAD"), worktree, Set.of(0)).stdout().trim();
    }

    private String resolveHeadCommit(Path worktree) {
        try {
            return runGit(List.of("rev-parse", "HEAD"), worktree, Set.of(0)).stdout().trim();
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private ProcessResult runGit(List<String> args, Path worktree, Set<Integer> allowedExitCodes) {
        List<String> command = new ArrayList<>();
        command.add(gitExecutable);
        command.addAll(args);
        return runProcess(command, worktree, commandTimeoutMs, allowedExitCodes);
    }

    private ProcessResult runShell(String command, Path worktree, int timeoutMs) {
        List<String> shellCommand = new ArrayList<>();
        if (isWindows()) {
            shellCommand.add("powershell");
            shellCommand.add("-NoProfile");
            shellCommand.add("-Command");
            shellCommand.add(command);
        } else {
            shellCommand.add("bash");
            shellCommand.add("-lc");
            shellCommand.add(command);
        }
        return runProcess(shellCommand, worktree, timeoutMs, Set.of(0));
    }

    private String validateVerifyCommand(String verifyCommand) {
        String command = verifyCommand == null ? "" : verifyCommand.trim();
        if (command.isEmpty()) {
            return "empty command";
        }
        if (command.length() > 512) {
            return "command is too long";
        }
        if (containsForbiddenShellToken(command)) {
            return "contains forbidden shell operator";
        }
        String prefix = command.split("\\s+")[0].toLowerCase(Locale.ROOT);
        if (!verifyCommandPrefixAllowlist.contains(prefix)) {
            return "command prefix is not in allowlist: " + prefix;
        }
        return null;
    }

    private PreparedVerifyCommand prepareVerifyCommand(String command) {
        String normalized = command == null ? "" : command.trim();
        if (!isMavenCommand(normalized) || normalized.contains("-Dproject.build.directory=")) {
            return PreparedVerifyCommand.noop(normalized);
        }
        if (verifyUseDocker) {
            String dockerBuildDir = "/tmp/agentx-verify-maven-" + UUID.randomUUID();
            return PreparedVerifyCommand.noop(
                injectMavenBuildDirectory(normalized, quoteShellArgument(dockerBuildDir))
            );
        }
        try {
            Path tempBuildDir = Files.createTempDirectory("agentx-verify-maven-");
            String rewritten = injectMavenBuildDirectory(normalized, quoteShellArgument(tempBuildDir.toString()));
            return new PreparedVerifyCommand(rewritten, tempBuildDir);
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to prepare isolated Maven build directory", ex);
        }
    }

    private static boolean isMavenCommand(String command) {
        if (command == null || command.isBlank()) {
            return false;
        }
        String prefix = command.split("\\s+")[0].toLowerCase(Locale.ROOT);
        return "mvn".equals(prefix) || "./mvnw".equals(prefix);
    }

    private static String injectMavenBuildDirectory(String command, String quotedBuildDirectory) {
        int firstWhitespace = command.indexOf(' ');
        if (firstWhitespace < 0) {
            return command + " -Dproject.build.directory=" + quotedBuildDirectory;
        }
        return command.substring(0, firstWhitespace)
            + " -Dproject.build.directory=" + quotedBuildDirectory
            + command.substring(firstWhitespace);
    }

    private static String quoteShellArgument(String value) {
        String safeValue = value == null ? "" : value;
        if (isWindows()) {
            return "'" + safeValue.replace("'", "''") + "'";
        }
        return "'" + safeValue.replace("'", "'\"'\"'") + "'";
    }

    private static void deletePathRecursively(Path path) {
        if (path == null || !Files.exists(path)) {
            return;
        }
        try (Stream<Path> stream = Files.walk(path)) {
            stream.sorted(Comparator.reverseOrder()).forEach(current -> {
                try {
                    Files.deleteIfExists(current);
                } catch (IOException ex) {
                    throw new IllegalStateException("Failed to delete path: " + current, ex);
                }
            });
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to cleanup path: " + path, ex);
        }
    }

    private static boolean containsForbiddenShellToken(String command) {
        return command.contains("&&")
            || command.contains("||")
            || command.contains(";")
            || command.contains("|")
            || command.contains("`")
            || command.contains("$(")
            || command.contains("<")
            || command.contains(">")
            || command.contains("\n")
            || command.contains("\r");
    }

    private List<String> findUnexpectedVerifyChanges(Path worktree) {
        ProcessResult dirtyCheck = runGit(List.of("status", "--porcelain"), worktree, Set.of(0));
        if (dirtyCheck.stdout().isBlank()) {
            return List.of();
        }
        List<String> unexpectedPaths = new ArrayList<>();
        for (String line : dirtyCheck.stdout().split("\\R")) {
            if (line == null || line.isBlank()) {
                continue;
            }
            VerifyStatusEntry entry = parseVerifyStatusEntry(line);
            if (entry == null) {
                unexpectedPaths.add(line.trim());
                continue;
            }
            if (entry.untracked() && isIgnorableVerifyDirtyPath(entry.path())) {
                continue;
            }
            unexpectedPaths.add(entry.path());
        }
        return List.copyOf(unexpectedPaths);
    }

    private static VerifyStatusEntry parseVerifyStatusEntry(String line) {
        if (line == null) {
            return null;
        }
        String rawLine = line.stripTrailing();
        if (rawLine.length() < 4) {
            return null;
        }
        String status = rawLine.substring(0, 2);
        String rawPath = rawLine.substring(3).trim();
        if (rawPath.isBlank()) {
            return null;
        }
        int renameArrow = rawPath.indexOf(" -> ");
        String path = renameArrow >= 0 ? rawPath.substring(renameArrow + 4).trim() : rawPath;
        if (path.startsWith("\"") && path.endsWith("\"") && path.length() > 1) {
            path = path.substring(1, path.length() - 1);
        }
        String normalizedPath = normalizePath(path);
        if (normalizedPath.isBlank()) {
            return null;
        }
        return new VerifyStatusEntry("??".equals(status), normalizedPath);
    }

    private static boolean isIgnorableVerifyDirtyPath(String relativePath) {
        String normalizedPath = normalizePath(relativePath);
        return isPromptIgnoredPath(normalizedPath)
            || normalizedPath.startsWith(".gradle/")
            || normalizedPath.startsWith(".pytest_cache/")
            || normalizedPath.startsWith("__pycache__/")
            || normalizedPath.startsWith(".ruff_cache/")
            || normalizedPath.startsWith(".tox/")
            || normalizedPath.startsWith("coverage/")
            || ".coverage".equals(normalizedPath)
            || ".mvn/wrapper/maven-wrapper.jar".equals(normalizedPath);
    }

    private static String summarizeVerifyChanges(List<String> changes) {
        if (changes == null || changes.isEmpty()) {
            return "n/a";
        }
        int limit = Math.min(5, changes.size());
        String summary = String.join(", ", changes.subList(0, limit));
        if (changes.size() > limit) {
            summary = summary + " (+" + (changes.size() - limit) + " more)";
        }
        return summary;
    }

    private static Set<String> parseVerifyCommandAllowlist(String raw) {
        if (raw == null || raw.isBlank()) {
            return Set.of("mvn", "./mvnw", "gradle", "./gradlew", "python", "pytest", "git");
        }
        LinkedHashSet<String> parsed = new LinkedHashSet<>();
        for (String token : raw.split(",")) {
            if (token == null || token.isBlank()) {
                continue;
            }
            parsed.add(token.trim().toLowerCase(Locale.ROOT));
        }
        if (parsed.isEmpty()) {
            return Set.of("mvn", "./mvnw", "gradle", "./gradlew", "python", "pytest", "git");
        }
        return Collections.unmodifiableSet(parsed);
    }

    private List<String> detectAlreadySatisfiedValidationCommands(TaskPackage taskPackage, Path worktree) {
        LinkedHashSet<String> commands = new LinkedHashSet<>();
        if (Files.exists(worktree.resolve("pom.xml"))) {
            if (Files.exists(worktree.resolve("mvnw"))) {
                commands.add("./mvnw -q test");
            }
            commands.add("mvn -q test");
        }
        if (Files.exists(worktree.resolve("build.gradle")) || Files.exists(worktree.resolve("build.gradle.kts"))) {
            if (Files.exists(worktree.resolve("gradlew"))) {
                commands.add("./gradlew test");
            }
            commands.add("gradle test");
        }
        boolean hasPythonTests = Files.isDirectory(worktree.resolve("tests"));
        if (hasPythonTests && (
            Files.exists(worktree.resolve("pyproject.toml"))
                || Files.exists(worktree.resolve("requirements.txt"))
                || Files.exists(worktree.resolve("setup.py"))
        )) {
            commands.add("python -m pytest -q");
        }
        if (commands.isEmpty() && taskPackage != null && taskPackage.requiredToolpacks() != null) {
            if (hasToolpack(taskPackage.requiredToolpacks(), "TP-MAVEN-3")) {
                commands.add("mvn -q test");
            } else if (taskPackage.requiredToolpacks().stream().anyMatch(
                id -> id != null && id.toUpperCase(Locale.ROOT).startsWith("TP-PYTHON")
            )) {
                commands.add("python -m pytest -q");
            }
        }
        return List.copyOf(commands);
    }

    private static String toDockerMountPath(Path worktree) {
        String raw = worktree.toAbsolutePath().normalize().toString();
        if (isWindows()) {
            return raw.replace("\\", "/");
        }
        return raw;
    }

    private ProcessResult runProcess(
        List<String> command,
        Path worktree,
        int timeoutMs,
        Set<Integer> allowedExitCodes
    ) {
        ProcessBuilder processBuilder = new ProcessBuilder(command);
        processBuilder.directory(worktree.toFile());
        processBuilder.redirectErrorStream(true);
        try {
            Process process = processBuilder.start();
            ProcessOutputCollector outputCollector = new ProcessOutputCollector(process.getInputStream());
            Thread outputThread = new Thread(
                outputCollector,
                "agentx-process-output-" + UUID.randomUUID().toString().substring(0, 8)
            );
            outputThread.setDaemon(true);
            outputThread.start();
            boolean finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS);
            if (!finished) {
                process.destroyForcibly();
                process.waitFor(5, TimeUnit.SECONDS);
                String output = awaitCollectedOutput(outputThread, outputCollector);
                String message = "Command timeout: " + String.join(" ", command);
                if (!output.isBlank()) {
                    message = message + ", output=" + trimForReport(output);
                }
                throw new IllegalStateException(message);
            }
            String output = awaitCollectedOutput(outputThread, outputCollector);
            int exitCode = process.exitValue();
            if (!allowedExitCodes.contains(exitCode)) {
                throw new IllegalStateException(
                    "Command failed (exit " + exitCode + "): "
                        + String.join(" ", command) + ", output=" + trimForReport(output)
                );
            }
            return new ProcessResult(exitCode, output);
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to execute command: " + String.join(" ", command), ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Command interrupted: " + String.join(" ", command), ex);
        }
    }

    private static String awaitCollectedOutput(Thread outputThread, ProcessOutputCollector collector)
        throws InterruptedException {
        if (outputThread != null) {
            outputThread.join(5_000L);
            if (outputThread.isAlive()) {
                outputThread.interrupt();
                outputThread.join(1_000L);
            }
        }
        IOException failure = collector == null ? null : collector.failure();
        if (failure != null) {
            throw new IllegalStateException("Failed to capture command output", failure);
        }
        return collector == null ? "" : collector.output();
    }

    private String readTaskSkill(String taskSkillRef) {
        if (taskSkillRef == null || taskSkillRef.isBlank()) {
            return "";
        }
        String normalized = taskSkillRef.trim();
        if (!normalized.startsWith("file:")) {
            return "";
        }
        Path path = Path.of(normalized.substring("file:".length()));
        if (!path.isAbsolute()) {
            path = repoRoot.resolve(path);
        }
        try {
            if (!Files.exists(path)) {
                return "";
            }
            String content = Files.readString(path, StandardCharsets.UTF_8);
            if (content.length() > 20_000) {
                return content.substring(0, 20_000);
            }
            return content;
        } catch (IOException ex) {
            return "";
        }
    }

    private JsonNode readTaskContextPack(String taskContextRef) {
        if (taskContextRef == null || taskContextRef.isBlank()) {
            return null;
        }
        String normalized = taskContextRef.trim();
        if (!normalized.startsWith("file:")) {
            return null;
        }
        Path path = Path.of(normalized.substring("file:".length()));
        if (!path.isAbsolute()) {
            path = repoRoot.resolve(path);
        }
        try {
            if (!Files.exists(path)) {
                return null;
            }
            String content = Files.readString(path, StandardCharsets.UTF_8);
            if (content.isBlank()) {
                return null;
            }
            // Cap to a sane amount in case the pack format is expanded in the future.
            if (content.length() > 80_000) {
                content = content.substring(0, 80_000);
            }
            JsonNode root = objectMapper.readTree(content);
            if (root == null || !root.isObject()) {
                return null;
            }
            return compactTaskContextPack(root);
        } catch (Exception ex) {
            return null;
        }
    }

    private JsonNode compactTaskContextPack(JsonNode raw) {
        if (raw == null || !raw.isObject()) {
            return null;
        }
        ObjectNode compact = objectMapper.createObjectNode();
        compact.put("pack_kind", "task_context_pack_v1");
        compact.put("snapshot_id", readTextOrEmpty(raw, "snapshot_id", "snapshotId"));
        compact.put("task_id", readTextOrEmpty(raw, "task_id", "taskId"));
        compact.put("run_kind", readTextOrEmpty(raw, "run_kind", "runKind"));
        compact.put("requirement_ref", readTextOrEmpty(raw, "requirement_ref", "requirementRef"));
        compact.put("module_ref", readTextOrEmpty(raw, "module_ref", "moduleRef"));
        compact.put("repo_baseline_ref", readTextOrEmpty(raw, "repo_baseline_ref", "repoBaselineRef"));
        compact.set("architecture_refs", capTextArray(raw, "architecture_refs", "architectureRefs", 24));
        compact.set("decision_refs", capTextArray(raw, "decision_refs", "decisionRefs", 24));
        compact.set("prior_run_refs", capTextArray(raw, "prior_run_refs", "priorRunRefs", 16));
        return compact;
    }

    private static String readTextOrEmpty(JsonNode root, String snakeCase, String camelCase) {
        if (root == null || !root.isObject()) {
            return "";
        }
        JsonNode node = root.path(snakeCase);
        if (node.isMissingNode()) {
            node = root.path(camelCase);
        }
        if (node == null || node.isNull()) {
            return "";
        }
        String value = node.asText("").trim();
        return value.isBlank() ? "" : value;
    }

    private ArrayNode capTextArray(JsonNode root, String snakeCase, String camelCase, int maxItems) {
        ArrayNode result = objectMapper.createArrayNode();
        if (root == null || !root.isObject()) {
            return result;
        }
        JsonNode node = root.path(snakeCase);
        if (node.isMissingNode()) {
            node = root.path(camelCase);
        }
        if (node == null || !node.isArray()) {
            return result;
        }
        int added = 0;
        for (JsonNode element : node) {
            if (added >= maxItems) {
                break;
            }
            if (element == null || !element.isTextual()) {
                continue;
            }
            String text = element.asText("").trim();
            if (text.isBlank()) {
                continue;
            }
            result.add(text);
            added++;
        }
        return result;
    }

    private JsonNode parseJson(String raw) {
        try {
            return objectMapper.readTree(raw);
        } catch (Exception ex) {
            return null;
        }
    }

    private static String buildSystemPrompt(String outputLanguage, boolean forceExecutionMode) {
        String basePrompt = """
            You are AgentX worker runtime planner.
            Return strict JSON only, no markdown fence.
            JSON schema:
            {
              "outcome":"SUCCEEDED|NEED_CLARIFICATION|NEED_DECISION|FAILED",
              "message":"string",
              "edits":[{"path":"relative/path","content":"full file content"}],
              "data_json": { "optional":"json object" }
            }
            Rules:
            - If facts are missing use NEED_CLARIFICATION.
            - If tradeoff decision is required use NEED_DECISION.
            - For SUCCEEDED, provide concrete file edits within write_scope.
            - Never return SUCCEEDED with an empty edits array.
            - Each SUCCEEDED edit must change current file contents or create a missing file.
            - Use workspace_snapshot as the authoritative source for existing project structure, package names, and build setup.
            - If workspace_snapshot already shows the answer, do not ask clarification about project structure or dependencies again.
            - If task_context.prior_run_refs mentions a failed VERIFY or FAILED run, treat that failure summary as authoritative evidence of what must be fixed next.
            - If a prior run already has a delivery_commit, do not claim the task is complete unless this run also returns the concrete edits required to resolve the latest blocker.
            - Never emit edits outside write_scope. If requirements mention out-of-scope files, leave them to other tasks instead of failing the current task.
            - When the current scaffold conflicts with explicit requirement values such as groupId, artifactId, package name, class name, endpoint contract, or runtime version, correct the conflicting scaffold in place.
            - Preserve explicit API literals exactly. If the requirement says /api/greeting and plain-text Hello responses, do not rename it to /api/hello and do not switch it to JSON.
            - Treat user-provided sample values in acceptance criteria as fixed literals too. Do not replace names like 张三 with Alice, 李四, or other substitutes unless the user explicitly broadens the examples.
            - For Spring Boot plain-text endpoints, treat framework-added charset suffixes such as text/plain;charset=UTF-8 as compatible with text/plain unless the requirement explicitly pins the exact raw header bytes.
            - When the requirement explicitly names a Spring query parameter and default value, preserve the literal annotation form such as @RequestParam("name") with defaultValue instead of switching to looser equivalents like required=false plus manual fallback.
            - If only part of the task can be completed within write_scope, return the in-scope edits now and explain the remaining out-of-scope work in message.
            - For tmpl.init.v0, avoid generic requirement questions and produce only a minimal bootstrap baseline directly.
            - For tmpl.init.v0, do not implement business endpoints, controllers, feature services, repositories, or feature tests.
            - For tmpl.init.v0, acceptable outputs are limited to scaffold files such as build config, app entrypoint, minimal runtime config, and project docs.
            - For tmpl.init.v0, the bootstrap baseline MUST honor any explicit framework, runtime, package, or build-stack requirements already present in task_context, resolved_clarifications, or task_skill_excerpt.
            - For tmpl.init.v0, if the confirmed requirement explicitly requires Spring Boot or another named framework, treat that framework as already approved baseline scope and do not ask permission to adopt it.
            - For Spring Boot tests, if a test autowires MockMvc, use @AutoConfigureMockMvc (or an equivalent test slice) rather than @AutoConfigureWebMvc.
            - For Spring Boot MockMvc assertions on plain-text String responses, use .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_PLAIN)) rather than exact MediaType.TEXT_PLAIN_VALUE because the framework often appends charset automatically.
            - Do not statically import or call a standalone contentType() matcher from MockMvcResultMatchers for this case; the correct matcher hangs off content().
            - If resolved_clarifications contains answered Q/A, treat those answers as authoritative requirements.
            - Do not ask clarification already answered in resolved_clarifications.
            - Keep edits minimal and executable.
            - All human-readable text fields MUST follow output_language.
            - Never repeat the same clarification question if recent user response already addressed it.
            output_language=%s
            """.formatted(outputLanguage);
        if (!forceExecutionMode) {
            return basePrompt;
        }
        return basePrompt + """
            
            force_execution_mode=true:
            - previous clarification was already answered.
            - return SUCCEEDED with concrete edits unless there is a hard technical blocker (e.g. invalid write_scope).
            - do not return the same clarification again.
            """;
    }

    private String buildUserPrompt(
        TaskPackage taskPackage,
        String taskSkillText,
        JsonNode taskContextPack,
        String outputLanguage,
        String workspaceSnapshot,
        boolean forceExecutionMode,
        String previousClarificationQuestion
    ) {
        List<ResolvedClarification> resolvedClarifications = extractResolvedClarifications(taskPackage.taskContext());
        ObjectNode root = objectMapper.createObjectNode();
        root.put("run_id", taskPackage.runId());
        root.put("task_id", taskPackage.taskId());
        root.put("task_title", defaultText(taskPackage.taskTitle(), ""));
        root.put("task_template_id", taskPackage.taskTemplateId());
        root.put("run_kind", taskPackage.runKind().name());
        root.put("module_id", taskPackage.moduleId());
        root.put("output_language", outputLanguage);
        root.putPOJO("write_scope", taskPackage.writeScope());
        root.putPOJO("read_scope", taskPackage.readScope());
        root.putPOJO("expected_outputs", taskPackage.expectedOutputs());
        root.putPOJO("stop_rules", taskPackage.stopRules());
        root.putPOJO("task_context", taskPackage.taskContext());
        root.put("task_context_ref", defaultText(taskPackage.taskContextRef(), ""));
        if (taskContextPack != null && taskContextPack.isObject()) {
            root.set("task_context_pack", taskContextPack);
        } else {
            root.putObject("task_context_pack");
        }
        root.putPOJO("resolved_clarifications", resolvedClarifications);
        root.put("resolved_clarifications_count", resolvedClarifications.size());
        root.putPOJO("recent_decision_refs", extractDecisionRefs(taskPackage.taskContext()));
        root.put("task_context_summary", buildTaskContextSummary(taskPackage.taskContext(), outputLanguage));
        root.put("force_execution_mode", forceExecutionMode);
        root.put(
            "previous_clarification_question",
            previousClarificationQuestion == null ? "" : previousClarificationQuestion
        );
        root.put("task_skill_excerpt", taskSkillText == null ? "" : taskSkillText);
        root.put("workspace_snapshot", workspaceSnapshot == null ? "" : workspaceSnapshot);
        return root.toString();
    }

    private String buildWorkspaceSnapshotForWorker(
        TaskPackage taskPackage,
        Path worktree,
        String taskSkillText,
        String outputLanguage
    ) {
        if (taskPackage == null) {
            return buildWorkspaceSnapshot(worktree, List.of("./"), List.of());
        }
        String query = buildWorkspaceQuery(taskPackage, taskSkillText);
        List<String> queryTokens = extractWorkspaceQueryTokens(query);
        if (queryTokens.isEmpty()) {
            return buildWorkspaceSnapshot(worktree, taskPackage.readScope(), taskPackage.writeScope());
        }
        try {
            return buildWorkspaceSnapshotWithLexicalIndex(
                worktree,
                taskPackage.readScope(),
                taskPackage.writeScope(),
                queryTokens,
                outputLanguage
            );
        } catch (RuntimeException ex) {
            // Snapshot is best-effort. If relevance indexing fails, fall back to deterministic v0 snapshot.
            return buildWorkspaceSnapshot(worktree, taskPackage.readScope(), taskPackage.writeScope());
        }
    }

    private static String buildWorkspaceQuery(TaskPackage taskPackage, String taskSkillText) {
        StringBuilder builder = new StringBuilder();
        appendQueryLine(builder, taskPackage.taskTitle());
        appendQueryLine(builder, taskPackage.taskTemplateId());
        if (taskPackage.taskContext() != null) {
            for (String ref : defaultList(taskPackage.taskContext().architectureRefs())) {
                if (ref == null) {
                    continue;
                }
                String trimmed = ref.trim();
                if (trimmed.startsWith("ticket-summary:")) {
                    appendQueryLine(builder, trimmed);
                }
            }
            for (String ref : defaultList(taskPackage.taskContext().priorRunRefs())) {
                appendQueryLine(builder, extractPriorRunSummary(ref));
            }
        }
        appendQueryLine(builder, extractSourceFragmentsSection(taskSkillText));
        String query = builder.toString().trim();
        if (query.length() <= MAX_WORKSPACE_QUERY_CHARS) {
            return query;
        }
        return query.substring(0, MAX_WORKSPACE_QUERY_CHARS);
    }

    private static void appendQueryLine(StringBuilder builder, String value) {
        if (builder == null || value == null) {
            return;
        }
        String normalized = value.trim();
        if (normalized.isBlank()) {
            return;
        }
        builder.append(normalized).append('\n');
    }

    private static String extractPriorRunSummary(String rawRef) {
        if (rawRef == null || rawRef.isBlank()) {
            return "";
        }
        String ref = rawRef.trim();
        int summaryIndex = ref.indexOf("|summary=");
        if (summaryIndex < 0) {
            return "";
        }
        return ref.substring(summaryIndex + "|summary=".length()).trim();
    }

    private static String extractSourceFragmentsSection(String taskSkillText) {
        if (taskSkillText == null || taskSkillText.isBlank()) {
            return "";
        }
        String normalized = taskSkillText.replace("\r\n", "\n");
        int start = normalized.indexOf("## Source Fragments");
        if (start < 0) {
            return "";
        }
        int end = normalized.indexOf("\n## ", start + 1);
        String section = end < 0 ? normalized.substring(start) : normalized.substring(start, end);
        if (section.length() > 6_000) {
            section = section.substring(0, 6_000);
        }
        return section;
    }

    private static List<String> extractWorkspaceQueryTokens(String query) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        LinkedHashSet<String> tokens = new LinkedHashSet<>();
        String normalized = query.toLowerCase(Locale.ROOT);

        Matcher identifierMatcher = QUERY_IDENTIFIER_PATTERN.matcher(normalized);
        while (identifierMatcher.find()) {
            tokens.add(identifierMatcher.group());
            if (tokens.size() >= MAX_WORKSPACE_RELEVANCE_HINT_TERMS) {
                break;
            }
        }

        if (tokens.size() < MAX_WORKSPACE_RELEVANCE_HINT_TERMS) {
            Matcher chineseMatcher = QUERY_CHINESE_PHRASE_PATTERN.matcher(normalized);
            while (chineseMatcher.find()) {
                String token = chineseMatcher.group();
                if (token.length() > 20) {
                    token = token.substring(0, 20);
                }
                tokens.add(token);
                if (tokens.size() >= MAX_WORKSPACE_RELEVANCE_HINT_TERMS) {
                    break;
                }
            }
        }

        return List.copyOf(tokens);
    }

    private static List<String> defaultList(List<String> value) {
        return value == null ? List.of() : value;
    }

    private static String buildWorkspaceSnapshotWithLexicalIndex(
        Path worktree,
        List<String> readScope,
        List<String> writeScope,
        List<String> queryTokens,
        String outputLanguage
    ) {
        if (worktree == null || !Files.exists(worktree)) {
            return "";
        }

        List<String> allVisibleFiles = new ArrayList<>();
        try (Stream<Path> stream = Files.walk(worktree)) {
            allVisibleFiles = stream
                .filter(Files::isRegularFile)
                .map(path -> normalizePath(worktree.relativize(path).toString()))
                .filter(path -> !path.isBlank())
                .filter(path -> !isPromptIgnoredPath(path))
                .filter(path -> isPathVisibleInSnapshot(path, readScope, writeScope))
                .limit(MAX_WORKSPACE_FILES_TO_SCORE)
                .toList();
        } catch (IOException ex) {
            return "workspace_snapshot_unavailable:" + ex.getMessage();
        }
        if (allVisibleFiles.isEmpty()) {
            return "workspace_snapshot_empty";
        }

        List<ScoredPath> scoredPaths = new ArrayList<>(allVisibleFiles.size());
        for (String path : allVisibleFiles) {
            int score = scorePathLexical(path, queryTokens, writeScope);
            scoredPaths.add(new ScoredPath(path, score));
        }
        scoredPaths.sort(Comparator
            .comparingInt(ScoredPath::score).reversed()
            .thenComparing(ScoredPath::path));

        List<ScoredPath> contentCandidates = new ArrayList<>();
        int taken = 0;
        for (ScoredPath scored : scoredPaths) {
            contentCandidates.add(scored);
            taken++;
            if (taken >= MAX_WORKSPACE_PATH_CANDIDATES_FOR_CONTENT) {
                break;
            }
        }

        List<ScoredPath> rescored = new ArrayList<>(contentCandidates.size());
        for (ScoredPath scored : contentCandidates) {
            int score = scored.score();
            if (isTextFileForPrompt(scored.path())) {
                score += scorePathContent(worktree, scored.path(), queryTokens);
            }
            rescored.add(new ScoredPath(scored.path(), score));
        }
        rescored.sort(Comparator
            .comparingInt(ScoredPath::score).reversed()
            .thenComparing(ScoredPath::path));

        List<String> selectedPaths = new ArrayList<>();
        for (ScoredPath scored : rescored) {
            if (selectedPaths.size() >= 40) {
                break;
            }
            selectedPaths.add(scored.path());
        }
        if (selectedPaths.isEmpty()) {
            return "workspace_snapshot_empty";
        }

        StringBuilder builder = new StringBuilder();
        builder.append("relevance_index: lexical_v0\n");
        builder.append("query_terms: ");
        builder.append(String.join(", ", queryTokens));
        builder.append("\n\nfiles:\n");
        for (String path : selectedPaths) {
            builder.append("- ").append(path).append('\n');
        }
        builder.append("\nfile_excerpts:\n");

        int excerptCount = 0;
        int remainingChars = 12_000;
        for (String relativePath : selectedPaths) {
            if (excerptCount >= 8 || remainingChars <= 0 || !isTextFileForPrompt(relativePath)) {
                continue;
            }
            String excerpt = excerptRelevantText(worktree, relativePath, queryTokens);
            if (excerpt.isBlank()) {
                continue;
            }
            if (excerpt.length() > remainingChars) {
                excerpt = excerpt.substring(0, remainingChars);
            }
            builder.append("[FILE ").append(relativePath).append("]\n");
            builder.append(excerpt).append("\n\n");
            remainingChars -= excerpt.length();
            excerptCount++;
        }
        if (excerptCount == 0) {
            builder.append(localize(
                outputLanguage,
                "<no text excerpts selected>",
                "<no text excerpts selected>"
            ));
        }
        return builder.toString().trim();
    }

    private static int scorePathLexical(String relativePath, List<String> queryTokens, List<String> writeScope) {
        String normalizedPath = normalizePath(relativePath).toLowerCase(Locale.ROOT);
        int score = 0;
        if (isImportantWorkspaceFile(normalizedPath)) {
            score += 1000;
        }
        if (isScopeAllowed(normalizedPath, writeScope)) {
            score += 250;
        }
        if (queryTokens == null || queryTokens.isEmpty()) {
            return score;
        }
        for (String token : queryTokens) {
            if (token == null || token.isBlank()) {
                continue;
            }
            String needle = token.toLowerCase(Locale.ROOT);
            if (needle.length() < 3) {
                continue;
            }
            int index = normalizedPath.indexOf(needle);
            if (index < 0) {
                continue;
            }
            score += 40;
            if (index == 0 || normalizedPath.charAt(Math.max(0, index - 1)) == '/' || normalizedPath.charAt(index) == '/') {
                score += 10;
            }
        }
        if (normalizedPath.endsWith(".md")) {
            score += 5;
        }
        if (normalizedPath.endsWith(".java") || normalizedPath.endsWith(".kt")) {
            score += 5;
        }
        return score;
    }

    private static int scorePathContent(Path worktree, String relativePath, List<String> queryTokens) {
        Path file = worktree.resolve(relativePath);
        try {
            if (!Files.exists(file)) {
                return 0;
            }
            String raw = Files.readString(file, StandardCharsets.UTF_8);
            if (raw.isBlank()) {
                return 0;
            }
            String content = raw.length() > 30_000 ? raw.substring(0, 30_000) : raw;
            String lower = content.toLowerCase(Locale.ROOT);
            int score = 0;
            for (String token : queryTokens) {
                if (token == null || token.isBlank()) {
                    continue;
                }
                String needle = token.toLowerCase(Locale.ROOT);
                if (needle.length() < 3) {
                    continue;
                }
                int found = 0;
                int from = 0;
                while (true) {
                    int idx = lower.indexOf(needle, from);
                    if (idx < 0) {
                        break;
                    }
                    found++;
                    from = idx + needle.length();
                    if (found >= 6) {
                        break;
                    }
                }
                if (found > 0) {
                    score += Math.min(40, found * 8);
                }
            }
            return score;
        } catch (Exception ex) {
            return 0;
        }
    }

    private static String excerptRelevantText(Path worktree, String relativePath, List<String> queryTokens) {
        Path file = worktree.resolve(relativePath);
        try {
            if (!Files.exists(file)) {
                return "";
            }
            String raw = Files.readString(file, StandardCharsets.UTF_8).trim();
            if (raw.isEmpty()) {
                return "";
            }
            String content = raw.length() > 50_000 ? raw.substring(0, 50_000) : raw;
            String lower = content.toLowerCase(Locale.ROOT);
            int bestIndex = -1;
            int bestTokenLength = 0;
            for (String token : queryTokens) {
                if (token == null || token.isBlank()) {
                    continue;
                }
                String needle = token.toLowerCase(Locale.ROOT);
                if (needle.length() < 3) {
                    continue;
                }
                int idx = lower.indexOf(needle);
                if (idx < 0) {
                    continue;
                }
                if (bestIndex < 0 || idx < bestIndex) {
                    bestIndex = idx;
                    bestTokenLength = needle.length();
                }
            }
            if (bestIndex < 0) {
                return content.length() > 1_500 ? content.substring(0, 1_500) : content;
            }
            int start = Math.max(0, bestIndex - 220);
            int end = Math.min(content.length(), start + 1_500);
            String excerpt = content.substring(start, end);
            if (start > 0) {
                excerpt = "... " + excerpt;
            }
            if (end < content.length()) {
                excerpt = excerpt + " ...";
            }
            if (bestTokenLength > 0 && excerpt.length() < 200 && content.length() > end) {
                // When a very short excerpt would be unhelpful, prefer the head to preserve structure.
                return content.length() > 1_500 ? content.substring(0, 1_500) : content;
            }
            return excerpt;
        } catch (Exception ex) {
            return "";
        }
    }

    private record ScoredPath(String path, int score) {
    }

    static String buildWorkspaceSnapshot(Path worktree, List<String> readScope, List<String> writeScope) {
        if (worktree == null || !Files.exists(worktree)) {
            return "";
        }
        List<String> candidatePaths = new ArrayList<>();
        try (Stream<Path> stream = Files.walk(worktree)) {
            candidatePaths = stream
                .filter(Files::isRegularFile)
                .map(path -> normalizePath(worktree.relativize(path).toString()))
                .filter(path -> !path.isBlank())
                .filter(path -> !isPromptIgnoredPath(path))
                .filter(path -> isPathVisibleInSnapshot(path, readScope, writeScope))
                .sorted((left, right) -> {
                    int score = Integer.compare(snapshotPriority(left, writeScope), snapshotPriority(right, writeScope));
                    return score != 0 ? score : left.compareTo(right);
                })
                .limit(40)
                .toList();
        } catch (IOException ex) {
            return "workspace_snapshot_unavailable:" + ex.getMessage();
        }
        if (candidatePaths.isEmpty()) {
            return "workspace_snapshot_empty";
        }

        StringBuilder builder = new StringBuilder();
        builder.append("files:\n");
        for (String path : candidatePaths) {
            builder.append("- ").append(path).append('\n');
        }
        builder.append("\nfile_excerpts:\n");

        int excerptCount = 0;
        int remainingChars = 12_000;
        for (String relativePath : candidatePaths) {
            if (excerptCount >= 8 || remainingChars <= 0 || !isTextFileForPrompt(relativePath)) {
                continue;
            }
            Path file = worktree.resolve(relativePath);
            try {
                String content = Files.readString(file, StandardCharsets.UTF_8).trim();
                if (content.isEmpty()) {
                    continue;
                }
                String trimmed = content.length() > 1_500 ? content.substring(0, 1_500) : content;
                if (trimmed.length() > remainingChars) {
                    trimmed = trimmed.substring(0, remainingChars);
                }
                builder.append("[FILE ").append(relativePath).append("]\n");
                builder.append(trimmed).append("\n\n");
                remainingChars -= trimmed.length();
                excerptCount++;
            } catch (IOException ex) {
                // best effort snapshot, unreadable files can be skipped
            }
        }
        return builder.toString().trim();
    }

    private String resolveOutputLanguage() {
        RuntimeLlmConfigUseCase.RuntimeConfigView runtimeConfig = runtimeLlmConfigUseCase.getCurrentConfig();
        if (runtimeConfig == null) {
            return "zh-CN";
        }
        return normalizeOutputLanguage(runtimeConfig.outputLanguage());
    }

    private static List<String> extractDecisionRefs(com.agentx.agentxbackend.execution.domain.model.TaskContext taskContext) {
        if (taskContext == null || taskContext.architectureRefs() == null || taskContext.architectureRefs().isEmpty()) {
            return List.of();
        }
        List<String> refs = new ArrayList<>();
        for (String ref : taskContext.architectureRefs()) {
            if (ref == null) {
                continue;
            }
            String normalized = ref.trim().toUpperCase(Locale.ROOT);
            if (normalized.contains("|DECISION") || normalized.contains("|CLARIFICATION")) {
                refs.add(ref.trim());
            }
        }
        return List.copyOf(refs);
    }

    private static boolean hasResolvedClarifications(
        com.agentx.agentxbackend.execution.domain.model.TaskContext taskContext
    ) {
        return !extractResolvedClarifications(taskContext).isEmpty();
    }

    private List<FileEdit> filterApplicableEdits(
        List<FileEdit> edits,
        List<String> writeScope,
        List<String> rejectedEditPaths
    ) {
        List<FileEdit> applicableEdits = new ArrayList<>();
        if (edits == null || edits.isEmpty()) {
            return applicableEdits;
        }
        for (FileEdit edit : edits) {
            if (!isWriteScopeAllowed(edit.path(), writeScope)) {
                if (rejectedEditPaths != null) {
                    rejectedEditPaths.add(edit.path());
                }
                continue;
            }
            applicableEdits.add(edit);
        }
        return applicableEdits;
    }

    private List<FileEdit> filterEffectiveEdits(List<FileEdit> edits, Path worktree) {
        List<FileEdit> effectiveEdits = new ArrayList<>();
        if (edits == null || edits.isEmpty()) {
            return effectiveEdits;
        }
        for (FileEdit edit : edits) {
            if (edit == null || edit.path() == null || edit.path().isBlank()) {
                continue;
            }
            Path targetPath = resolveEditPath(worktree, edit.path());
            try {
                if (Files.exists(targetPath)) {
                    String current = Files.readString(targetPath, StandardCharsets.UTF_8);
                    if (current.equals(edit.content())) {
                        continue;
                    }
                }
                effectiveEdits.add(edit);
            } catch (IOException ex) {
                effectiveEdits.add(edit);
            }
        }
        return effectiveEdits;
    }

    private boolean hasExistingFileInScope(Path worktree, List<String> writeScope) {
        if (worktree == null || writeScope == null || writeScope.isEmpty()) {
            return false;
        }
        for (String scope : writeScope) {
            String normalizedScope = normalizeScope(scope);
            if (normalizedScope.isEmpty() || ".".equals(normalizedScope)) {
                return hasAnyRegularFile(worktree);
            }
            Path candidate = resolveEditPath(worktree, normalizedScope);
            if (Files.isRegularFile(candidate)) {
                return true;
            }
            if (Files.isDirectory(candidate) && hasAnyRegularFile(candidate)) {
                return true;
            }
        }
        return false;
    }

    private boolean hasAnyRegularFile(Path path) {
        if (path == null || !Files.exists(path)) {
            return false;
        }
        if (Files.isRegularFile(path)) {
            return true;
        }
        try (Stream<Path> stream = Files.walk(path)) {
            return stream.anyMatch(Files::isRegularFile);
        } catch (IOException ex) {
            return false;
        }
    }

    private static boolean isPathVisibleInSnapshot(String relativePath, List<String> readScope, List<String> writeScope) {
        return isScopeAllowed(relativePath, writeScope)
            || isScopeAllowed(relativePath, readScope)
            || isImportantWorkspaceFile(relativePath);
    }

    private static boolean isScopeAllowed(String relativePath, List<String> scopes) {
        if (scopes == null || scopes.isEmpty()) {
            return false;
        }
        String normalizedPath = normalizePath(relativePath);
        for (String scope : scopes) {
            String normalizedScope = normalizeScope(scope);
            if (normalizedScope.isEmpty() || ".".equals(normalizedScope)) {
                return true;
            }
            if (normalizedPath.equals(normalizedScope)) {
                return true;
            }
            String prefix = normalizedScope.endsWith("/") ? normalizedScope : normalizedScope + "/";
            if (normalizedPath.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    private static int snapshotPriority(String relativePath, List<String> writeScope) {
        String normalizedPath = normalizePath(relativePath);
        if (isImportantWorkspaceFile(normalizedPath)) {
            return 0;
        }
        if (isScopeAllowed(normalizedPath, writeScope)) {
            return 1;
        }
        return 2;
    }

    private static boolean isImportantWorkspaceFile(String relativePath) {
        String normalizedPath = normalizePath(relativePath);
        return "pom.xml".equals(normalizedPath)
            || "build.gradle".equals(normalizedPath)
            || "settings.gradle".equals(normalizedPath)
            || "package.json".equals(normalizedPath)
            || "README.md".equalsIgnoreCase(normalizedPath)
            || normalizedPath.startsWith("src/main/")
            || normalizedPath.startsWith("src/test/");
    }

    private static boolean isPromptIgnoredPath(String relativePath) {
        String normalizedPath = normalizePath(relativePath);
        return normalizedPath.startsWith(".git/")
            || normalizedPath.startsWith(".agentx/")
            || normalizedPath.startsWith("target/")
            || normalizedPath.startsWith("node_modules/")
            || normalizedPath.startsWith("dist/")
            || normalizedPath.startsWith("build/")
            || normalizedPath.startsWith("worktrees/")
            || normalizedPath.startsWith("runtime-data/")
            || normalizedPath.startsWith("runtime-projects/")
            || normalizedPath.startsWith(".idea/")
            || normalizedPath.startsWith(".vscode/")
            || normalizedPath.equals(".env")
            || normalizedPath.startsWith(".env.")
            || normalizedPath.endsWith(".pem")
            || normalizedPath.endsWith(".p12")
            || normalizedPath.endsWith(".pfx")
            || normalizedPath.endsWith(".key");
    }

    private static boolean isTextFileForPrompt(String relativePath) {
        String normalizedPath = normalizePath(relativePath).toLowerCase(Locale.ROOT);
        return normalizedPath.endsWith(".java")
            || normalizedPath.endsWith(".kt")
            || normalizedPath.endsWith(".xml")
            || normalizedPath.endsWith(".yml")
            || normalizedPath.endsWith(".yaml")
            || normalizedPath.endsWith(".properties")
            || normalizedPath.endsWith(".md")
            || normalizedPath.endsWith(".txt")
            || normalizedPath.endsWith(".json");
    }

    private static List<ResolvedClarification> extractResolvedClarifications(
        com.agentx.agentxbackend.execution.domain.model.TaskContext taskContext
    ) {
        if (taskContext == null || taskContext.architectureRefs() == null || taskContext.architectureRefs().isEmpty()) {
            return List.of();
        }
        List<ResolvedClarification> resolved = new ArrayList<>();
        Set<String> dedupKeys = new LinkedHashSet<>();
        for (String ref : taskContext.architectureRefs()) {
            ResolvedClarification parsed = parseResolvedClarification(ref);
            if (parsed == null) {
                continue;
            }
            String dedupKey = normalizeFreeText(parsed.question()) + "|" + normalizeFreeText(parsed.answer());
            if (!dedupKeys.add(dedupKey)) {
                continue;
            }
            resolved.add(parsed);
            if (resolved.size() >= 8) {
                break;
            }
        }
        return List.copyOf(resolved);
    }

    private static ResolvedClarification parseResolvedClarification(String rawRef) {
        if (rawRef == null) {
            return null;
        }
        String ref = rawRef.trim();
        if (!ref.startsWith("ticket-summary:")) {
            return null;
        }
        int qIndex = ref.indexOf("|Q=");
        int aIndex = ref.indexOf("|A=");
        if (qIndex <= "ticket-summary:".length() || aIndex <= qIndex) {
            return null;
        }
        String ticketId = ref.substring("ticket-summary:".length(), qIndex).trim();
        String question = ref.substring(qIndex + 3, aIndex).trim();
        String answer = ref.substring(aIndex + 3).trim();
        if (answer.isBlank()) {
            return null;
        }
        return new ResolvedClarification(ticketId, question, answer);
    }

    private static String normalizeFreeText(String value) {
        if (value == null) {
            return "";
        }
        return value.replaceAll("\\s+", " ").trim().toLowerCase(Locale.ROOT);
    }

    private static String buildTaskContextSummary(
        com.agentx.agentxbackend.execution.domain.model.TaskContext taskContext,
        String outputLanguage
    ) {
        if (taskContext == null) {
            return localize(outputLanguage, "任务上下文为空。", "Task context is empty.");
        }
        int architectureCount = taskContext.architectureRefs() == null ? 0 : taskContext.architectureRefs().size();
        int priorRunCount = taskContext.priorRunRefs() == null ? 0 : taskContext.priorRunRefs().size();
        List<String> decisionRefs = extractDecisionRefs(taskContext);
        String priorRunHint = extractPriorRunHint(taskContext.priorRunRefs());
        if (isChinese(outputLanguage)) {
            String summary = "requirement_ref=%s；architecture_refs=%d；prior_run_refs=%d；decision_refs=%d"
                .formatted(
                    defaultText(taskContext.requirementRef(), "N/A"),
                    architectureCount,
                    priorRunCount,
                    decisionRefs.size()
                );
            if (priorRunHint != null) {
                return summary + "；latest_run_hint=" + priorRunHint;
            }
            return summary;
        }
        String summary = "requirement_ref=%s; architecture_refs=%d; prior_run_refs=%d; decision_refs=%d"
            .formatted(
                defaultText(taskContext.requirementRef(), "N/A"),
                architectureCount,
                priorRunCount,
                decisionRefs.size()
            );
        if (priorRunHint != null) {
            return summary + "; latest_run_hint=" + priorRunHint;
        }
        return summary;
    }

    private static String extractPriorRunHint(List<String> priorRunRefs) {
        if (priorRunRefs == null || priorRunRefs.isEmpty()) {
            return null;
        }
        for (String ref : priorRunRefs) {
            String normalized = ref == null ? "" : ref.trim();
            if (normalized.contains("|FAILED|VERIFY|") && normalized.contains("|summary=")) {
                return abbreviatePriorRunHint(readPriorRunSummary(normalized));
            }
        }
        for (String ref : priorRunRefs) {
            String summary = abbreviatePriorRunHint(readPriorRunSummary(ref));
            if (summary != null) {
                return summary;
            }
        }
        return null;
    }

    private static String readPriorRunSummary(String priorRunRef) {
        if (priorRunRef == null || priorRunRef.isBlank()) {
            return null;
        }
        int summaryIndex = priorRunRef.indexOf("|summary=");
        if (summaryIndex < 0) {
            return null;
        }
        String summary = priorRunRef.substring(summaryIndex + "|summary=".length()).trim();
        return summary.isBlank() ? null : summary;
    }

    private static String abbreviatePriorRunHint(String summary) {
        if (summary == null || summary.isBlank()) {
            return null;
        }
        return summary.length() <= 220 ? summary : summary.substring(0, 220);
    }

    private static String stripMarkdownFence(String raw) {
        String value = raw.trim();
        if (!value.startsWith("```")) {
            return value;
        }
        int firstBreak = value.indexOf('\n');
        if (firstBreak >= 0) {
            value = value.substring(firstBreak + 1);
        }
        if (value.endsWith("```")) {
            value = value.substring(0, value.length() - 3);
        }
        return value.trim();
    }

    private static List<FileEdit> readPlannerEdits(JsonNode root) {
        LinkedHashSet<FileEdit> edits = new LinkedHashSet<>();
        readPlannerEditContainer(firstPlannerNode(root, "edits", "file_edits", "files", "changes"), edits);
        if (edits.isEmpty()) {
            JsonNode singlePathNode = firstPlannerNode(root, "path", "file", "file_path", "relative_path", "target_path");
            String singlePath = singlePathNode == null ? "" : singlePathNode.asText("").trim();
            if (!singlePath.isBlank()) {
                String content = readPlannerText(root, "content", "new_content", "full_content", "body", "text");
                edits.add(new FileEdit(singlePath, content));
            }
        }
        return List.copyOf(edits);
    }

    private static void readPlannerEditContainer(JsonNode node, Set<FileEdit> sink) {
        if (node == null || sink == null) {
            return;
        }
        if (node.isArray()) {
            for (JsonNode element : node) {
                readPlannerEditContainer(element, sink);
            }
            return;
        }
        if (node.isObject()) {
            String path = readPlannerText(node, "path", "file", "file_path", "relative_path", "target_path").trim();
            if (!path.isBlank()) {
                String content = readPlannerText(node, "content", "new_content", "full_content", "body", "text");
                sink.add(new FileEdit(path, content));
                return;
            }
            if (node instanceof ObjectNode objectNode) {
                objectNode.properties().forEach(entry -> {
                    JsonNode value = entry.getValue();
                    if (value != null && value.isTextual() && entry.getKey() != null && !entry.getKey().isBlank()) {
                        sink.add(new FileEdit(entry.getKey().trim(), value.asText("")));
                    }
                });
            }
        }
    }

    private static JsonNode firstPlannerNode(JsonNode root, String... fieldNames) {
        if (root == null || !root.isObject() || fieldNames == null) {
            return null;
        }
        for (String fieldName : fieldNames) {
            if (fieldName == null || fieldName.isBlank()) {
                continue;
            }
            JsonNode node = root.path(fieldName);
            if (!node.isMissingNode() && !node.isNull()) {
                return node;
            }
        }
        return null;
    }

    private static String readPlannerText(JsonNode root, String... fieldNames) {
        JsonNode node = firstPlannerNode(root, fieldNames);
        if (node == null || node.isNull()) {
            return "";
        }
        if (node.isTextual()) {
            return node.asText("");
        }
        if (node.isNumber() || node.isBoolean()) {
            return node.asText("");
        }
        return node.toString();
    }

    private ActiveWorkerLlmConfig resolveActiveWorkerLlm(String outputLanguage) {
        RuntimeLlmConfigUseCase.RuntimeConfigView runtimeConfig = runtimeLlmConfigUseCase.resolveForRequestLanguage(outputLanguage);
        RuntimeLlmConfigUseCase.LlmProfile profile = runtimeConfig.workerRuntimeLlm();
        String provider = normalizeProvider(profile.provider());
        String framework = normalizeFramework(profile.framework());
        String baseUrl = resolveBaseUrl(provider, profile.baseUrl());
        String modelName = normalizeModel(profile.model());
        String apiKey = profile.apiKey() == null ? "" : profile.apiKey().trim();
        long timeoutMs = Math.max(1_000L, Math.min(300_000L, profile.timeoutMs()));
        return new ActiveWorkerLlmConfig(
            provider,
            framework,
            baseUrl,
            modelName,
            apiKey,
            timeoutMs,
            normalizeOutputLanguage(runtimeConfig.outputLanguage())
        );
    }

    private static String normalizeOutputLanguage(String value) {
        String normalized = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        if (normalized.isBlank()) {
            return "zh-CN";
        }
        if (normalized.equals("zh") || normalized.equals("zh-cn") || normalized.equals("cn")) {
            return "zh-CN";
        }
        if (normalized.equals("en") || normalized.equals("en-us")) {
            return "en-US";
        }
        if (normalized.equals("ja") || normalized.equals("ja-jp")) {
            return "ja-JP";
        }
        return value.trim();
    }

    private static String localize(String outputLanguage, String zhText, String enText) {
        return isChinese(outputLanguage) ? zhText : enText;
    }

    private static boolean isChinese(String outputLanguage) {
        return outputLanguage != null && outputLanguage.toLowerCase(Locale.ROOT).startsWith("zh");
    }

    private static String normalizeProvider(String value) {
        String normalized = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        if (normalized.isBlank()) {
            return MOCK_PROVIDER;
        }
        return normalized;
    }

    private static String normalizeFramework(String value) {
        String normalized = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        if (normalized.isBlank()) {
            return LANGCHAIN4J_FRAMEWORK;
        }
        return normalized;
    }

    private static String normalizeModel(String value) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isBlank()) {
            return "qwen3.5-plus-2026-02-15";
        }
        return normalized;
    }

    private static String resolveBaseUrl(String provider, String baseUrl) {
        String normalized = baseUrl == null ? "" : baseUrl.trim();
        if (normalized.isBlank()) {
            if (MOCK_PROVIDER.equals(provider)) {
                return "https://dashscope.aliyuncs.com/compatible-mode/v1";
            }
            throw new IllegalArgumentException("worker llm base-url must not be blank");
        }
        return normalized;
    }

    private static String normalizeBaseUrl(String baseUrl) {
        String normalized = baseUrl == null ? "" : baseUrl.trim();
        if (normalized.isBlank()) {
            throw new IllegalArgumentException("worker llm base-url must not be blank");
        }
        if (normalized.endsWith("/")) {
            return normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    private static String normalizeTemplate(String taskTemplateId) {
        if (taskTemplateId == null) {
            return "";
        }
        return taskTemplateId.trim().toLowerCase(Locale.ROOT);
    }

    private static boolean hasToolpack(List<String> toolpacks, String toolpackId) {
        if (toolpacks == null || toolpacks.isEmpty() || toolpackId == null) {
            return false;
        }
        for (String toolpack : toolpacks) {
            if (toolpackId.equalsIgnoreCase(toolpack)) {
                return true;
            }
        }
        return false;
    }

    private static String normalizeScope(String scope) {
        if (scope == null) {
            return "";
        }
        String normalized = scope.trim().replace("\\", "/");
        while (normalized.startsWith("./")) {
            normalized = normalized.substring(2);
        }
        while (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        if (normalized.endsWith("/") && normalized.length() > 1) {
            return normalized;
        }
        return normalized;
    }

    private static String normalizeWorktreePath(String worktreePath) {
        String normalized = worktreePath.trim().replace("\\", "/");
        while (normalized.startsWith("./")) {
            normalized = normalized.substring(2);
        }
        return normalized;
    }

    private static String normalizeRelativePrefix(String prefix, String fallback) {
        String normalized = prefix == null || prefix.isBlank() ? fallback : prefix.trim();
        normalized = normalized.replace("\\", "/");
        while (normalized.startsWith("./")) {
            normalized = normalized.substring(2);
        }
        while (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        if (normalized.isBlank() || normalized.contains("..")) {
            return fallback;
        }
        return normalized;
    }

    private static String normalizePath(String path) {
        String normalized = path == null ? "" : path.trim().replace("\\", "/");
        while (normalized.startsWith("./")) {
            normalized = normalized.substring(2);
        }
        while (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        return normalized;
    }

    private static String defaultText(String value, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return value.trim();
    }

    private static String trimForReport(String value) {
        if (value == null) {
            return "";
        }
        String compact = value.trim();
        if (compact.length() <= 2_000) {
            return compact;
        }
        String marker = "\n...[output truncated]...\n";
        int headLength = 1_000;
        int tailLength = 2_000 - headLength - marker.length();
        if (tailLength <= 0 || compact.length() <= headLength + marker.length()) {
            return compact.substring(0, 2_000);
        }
        return compact.substring(0, headLength)
            + marker
            + compact.substring(compact.length() - tailLength);
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    private record ProcessResult(int exitCode, String stdout) {
    }

    private static final class ProcessOutputCollector implements Runnable {

        private final InputStream inputStream;
        private final StringBuilder output = new StringBuilder();
        private volatile boolean truncated;
        private volatile IOException failure;

        private ProcessOutputCollector(InputStream inputStream) {
            this.inputStream = inputStream;
        }

        @Override
        public void run() {
            char[] buffer = new char[4096];
            try (Reader reader = new InputStreamReader(inputStream, StandardCharsets.UTF_8)) {
                int read;
                while ((read = reader.read(buffer)) >= 0) {
                    append(buffer, read);
                }
            } catch (IOException ex) {
                failure = ex;
            }
        }

        private synchronized void append(char[] buffer, int read) {
            if (read <= 0) {
                return;
            }
            int remaining = MAX_CAPTURED_PROCESS_OUTPUT_CHARS - output.length();
            if (remaining > 0) {
                output.append(buffer, 0, Math.min(read, remaining));
            }
            if (read > remaining) {
                truncated = true;
            }
        }

        private IOException failure() {
            return failure;
        }

        private synchronized String output() {
            if (!truncated) {
                return output.toString();
            }
            return output + "\n[output truncated]";
        }
    }

    private record VerifyStatusEntry(boolean untracked, String path) {
    }

    private record PreparedVerifyCommand(String command, Path cleanupPath) {

        private static PreparedVerifyCommand noop(String command) {
            return new PreparedVerifyCommand(command, null);
        }

        private void cleanup() {
            if (cleanupPath != null) {
                deletePathRecursively(cleanupPath);
            }
        }
    }

    private record FileEdit(String path, String content) {
    }

    private record PlannerResult(String outcome, String message, List<FileEdit> edits, String dataJson) {

        static PlannerResult needClarification(String message, String dataJson) {
            return new PlannerResult("NEED_CLARIFICATION", message, List.of(), dataJson);
        }
    }

    private record ResolvedClarification(String ticketId, String question, String answer) {
    }

    private record ActiveWorkerLlmConfig(
        String provider,
        String framework,
        String baseUrl,
        String model,
        String apiKey,
        long timeoutMs,
        String outputLanguage
    ) {
    }
}

