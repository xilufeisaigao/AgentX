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
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;

@Component
public class LocalWorkerTaskExecutor implements WorkerTaskExecutorPort {

    private static final String MOCK_PROVIDER = "mock";
    private static final String BAILIAN_PROVIDER = "bailian";
    private static final String LANGCHAIN4J_FRAMEWORK = "langchain4j";

    private final ObjectMapper objectMapper;
    private final RuntimeLlmConfigUseCase runtimeLlmConfigUseCase;
    private final String gitExecutable;
    private final Path repoRoot;
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
        @Value("${agentx.worker-runtime.command-timeout-ms:120000}") int commandTimeoutMs,
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
        ProcessResult dirtyCheck = runGit(List.of("status", "--porcelain"), worktree, Set.of(0));
        if (!dirtyCheck.stdout().isBlank()) {
            return ExecutionResult.failed("VERIFY run must be read-only, but worktree became dirty.");
        }
        String workReport = report.isEmpty()
            ? "VERIFY completed successfully."
            : "VERIFY completed successfully.\n" + report;
        return ExecutionResult.succeeded(workReport, null, null);
    }

    private ProcessResult runVerifyCommand(String command, Path worktree, int timeoutMs) {
        if (!verifyUseDocker) {
            return runShell(command, worktree, timeoutMs);
        }
        return runVerifyInDocker(command, worktree, timeoutMs);
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
        PlannerResult plannerResult = proposePlan(
            taskPackage,
            taskSkillText,
            outputLanguage,
            false,
            null
        );
        if ("NEED_CLARIFICATION".equals(plannerResult.outcome()) && hasResolvedClarifications(taskPackage.taskContext())) {
            plannerResult = proposePlan(
                taskPackage,
                taskSkillText,
                outputLanguage,
                true,
                plannerResult.message()
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
        for (FileEdit edit : edits) {
            if (!isWriteScopeAllowed(edit.path(), taskPackage.writeScope())) {
                return ExecutionResult.failed("Edit path is outside write_scope: " + edit.path());
            }
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
        return ExecutionResult.succeeded(workReport, deliveryCommit, null);
    }

    private PlannerResult proposePlan(TaskPackage taskPackage, String taskSkillText, String outputLanguage) {
        return proposePlan(taskPackage, taskSkillText, outputLanguage, false, null);
    }

    private PlannerResult proposePlan(
        TaskPackage taskPackage,
        String taskSkillText,
        String outputLanguage,
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
                        llmConfig.outputLanguage(),
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
        String outcome = root.path("outcome").asText("SUCCEEDED").trim().toUpperCase(Locale.ROOT);
        String message = root.path("message").asText("").trim();
        String dataJson = root.path("data_json").isMissingNode() ? null : root.path("data_json").toString();

        List<FileEdit> edits = new ArrayList<>();
        JsonNode editsNode = root.path("edits");
        if (editsNode.isArray()) {
            for (JsonNode editNode : editsNode) {
                if (!editNode.isObject()) {
                    continue;
                }
                String path = editNode.path("path").asText("").trim();
                String content = editNode.path("content").asText("");
                if (!path.isBlank()) {
                    edits.add(new FileEdit(path, content));
                }
            }
        }

        return new PlannerResult(outcome, message, edits, dataJson);
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
        Path resolved = repoRoot.resolve(worktreePath.trim()).toAbsolutePath().normalize();
        if (!resolved.startsWith(repoRoot)) {
            throw new IllegalArgumentException("worktreePath escapes repo root: " + worktreePath);
        }
        return resolved;
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
            boolean finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS);
            if (!finished) {
                process.destroyForcibly();
                throw new IllegalStateException("Command timeout: " + String.join(" ", command));
            }
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            int exitCode = process.exitValue();
            if (!allowedExitCodes.contains(exitCode)) {
                throw new IllegalStateException(
                    "Command failed (exit " + exitCode + "): "
                        + String.join(" ", command) + ", output=" + output
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
            - For tmpl.init.v0, avoid generic requirement questions and produce a minimal bootstrap baseline directly.
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
        String outputLanguage,
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
        return root.toString();
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
        if (isChinese(outputLanguage)) {
            return "requirement_ref=%s；architecture_refs=%d；prior_run_refs=%d；decision_refs=%d"
                .formatted(
                    defaultText(taskContext.requirementRef(), "N/A"),
                    architectureCount,
                    priorRunCount,
                    decisionRefs.size()
                );
        }
        return "requirement_ref=%s; architecture_refs=%d; prior_run_refs=%d; decision_refs=%d"
            .formatted(
                defaultText(taskContext.requirementRef(), "N/A"),
                architectureCount,
                priorRunCount,
                decisionRefs.size()
            );
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
        return compact.substring(0, 2_000);
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    private record ProcessResult(int exitCode, String stdout) {
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

