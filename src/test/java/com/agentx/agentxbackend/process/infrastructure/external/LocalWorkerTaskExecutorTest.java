package com.agentx.agentxbackend.process.infrastructure.external;

import com.agentx.agentxbackend.execution.domain.model.GitAlloc;
import com.agentx.agentxbackend.execution.domain.model.RunKind;
import com.agentx.agentxbackend.execution.domain.model.TaskContext;
import com.agentx.agentxbackend.execution.domain.model.TaskPackage;
import com.agentx.agentxbackend.process.application.port.in.RuntimeLlmConfigUseCase;
import com.agentx.agentxbackend.process.application.port.out.WorkerTaskExecutorPort;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class LocalWorkerTaskExecutorTest {

    @TempDir
    Path tempDir;

    @Test
    void executeImplShouldCreateCommitInMockMode() throws Exception {
        assumeTrue(canRunGit(), "git executable is required");
        Path repo = initGitRepo(tempDir.resolve("repo-impl"));
        LocalWorkerTaskExecutor executor = new LocalWorkerTaskExecutor(
            buildRuntimeConfigUseCase("mock", ""),
            new ObjectMapper(),
            "git",
            repo.toString(),
            120000,
            20,
            "mvn,./mvnw,gradle,./gradlew,python,pytest,git",
            false,
            "docker",
            "maven:3.9.11-eclipse-temurin-21",
            "1g",
            "1.0",
            256
        );

        WorkerTaskExecutorPort.ExecutionResult result = executor.execute(buildImplPackage("RUN-IMPL-1", "TASK-IMPL-1", "."));

        assertEquals(WorkerTaskExecutorPort.ExecutionStatus.SUCCEEDED, result.status());
        assertNotNull(result.deliveryCommit());
        assertFalse(result.deliveryCommit().isBlank());
        assertTrue(Files.exists(repo.resolve("AGENTX_AUTOGEN_NOTE.md")));
    }

    @Test
    void executeVerifyShouldRunCommandsAndStayReadOnly() throws Exception {
        assumeTrue(canRunGit(), "git executable is required");
        Path repo = initGitRepo(tempDir.resolve("repo-verify"));
        LocalWorkerTaskExecutor executor = new LocalWorkerTaskExecutor(
            buildRuntimeConfigUseCase("mock", ""),
            new ObjectMapper(),
            "git",
            repo.toString(),
            120000,
            20,
            "mvn,./mvnw,gradle,./gradlew,python,pytest,git",
            false,
            "docker",
            "maven:3.9.11-eclipse-temurin-21",
            "1g",
            "1.0",
            256
        );

        WorkerTaskExecutorPort.ExecutionResult result = executor.execute(buildVerifyPackage("RUN-VERIFY-1", "TASK-VERIFY-1", "."));

        assertEquals(WorkerTaskExecutorPort.ExecutionStatus.SUCCEEDED, result.status());
        String statusOutput = runGit(repo, List.of("status", "--porcelain")).trim();
        assertTrue(statusOutput.isEmpty());
    }

    @Test
    void executeVerifyShouldRejectCommandOutsideAllowlist() throws Exception {
        assumeTrue(canRunGit(), "git executable is required");
        Path repo = initGitRepo(tempDir.resolve("repo-verify-allowlist"));
        LocalWorkerTaskExecutor executor = new LocalWorkerTaskExecutor(
            buildRuntimeConfigUseCase("mock", ""),
            new ObjectMapper(),
            "git",
            repo.toString(),
            120000,
            20,
            "mvn,./mvnw,gradle,./gradlew,python,pytest,git",
            false,
            "docker",
            "maven:3.9.11-eclipse-temurin-21",
            "1g",
            "1.0",
            256
        );

        WorkerTaskExecutorPort.ExecutionResult result = executor.execute(
            buildVerifyPackage("RUN-VERIFY-2", "TASK-VERIFY-2", ".", List.of("cmd /c dir"))
        );

        assertEquals(WorkerTaskExecutorPort.ExecutionStatus.FAILED, result.status());
        assertTrue(result.failureReason().contains("rejected by policy"));
    }

    @Test
    void executeImplShouldReturnChineseNeedClarificationWhenApiKeyMissing() throws Exception {
        assumeTrue(canRunGit(), "git executable is required");
        Path repo = initGitRepo(tempDir.resolve("repo-lang-zh"));
        LocalWorkerTaskExecutor executor = new LocalWorkerTaskExecutor(
            buildRuntimeConfigUseCase("bailian", "", "zh-CN"),
            new ObjectMapper(),
            "git",
            repo.toString(),
            120000,
            20,
            "mvn,./mvnw,gradle,./gradlew,python,pytest,git",
            false,
            "docker",
            "maven:3.9.11-eclipse-temurin-21",
            "1g",
            "1.0",
            256
        );

        WorkerTaskExecutorPort.ExecutionResult result = executor.execute(buildImplPackage("RUN-LANG-ZH", "TASK-LANG-ZH", "."));

        assertEquals(WorkerTaskExecutorPort.ExecutionStatus.NEED_INPUT, result.status());
        assertEquals("NEED_CLARIFICATION", result.needEventType());
        assertTrue(result.needBody().contains("缺少 Worker LLM 的 api-key"));
    }

    @Test
    void executeImplShouldReturnEnglishNeedClarificationWhenApiKeyMissing() throws Exception {
        assumeTrue(canRunGit(), "git executable is required");
        Path repo = initGitRepo(tempDir.resolve("repo-lang-en"));
        LocalWorkerTaskExecutor executor = new LocalWorkerTaskExecutor(
            buildRuntimeConfigUseCase("bailian", "", "en-US"),
            new ObjectMapper(),
            "git",
            repo.toString(),
            120000,
            20,
            "mvn,./mvnw,gradle,./gradlew,python,pytest,git",
            false,
            "docker",
            "maven:3.9.11-eclipse-temurin-21",
            "1g",
            "1.0",
            256
        );

        WorkerTaskExecutorPort.ExecutionResult result = executor.execute(buildImplPackage("RUN-LANG-EN", "TASK-LANG-EN", "."));

        assertEquals(WorkerTaskExecutorPort.ExecutionStatus.NEED_INPUT, result.status());
        assertEquals("NEED_CLARIFICATION", result.needEventType());
        assertTrue(result.needBody().contains("api-key is missing"));
    }

    private static TaskPackage buildImplPackage(String runId, String taskId, String worktreePath) {
        return new TaskPackage(
            runId,
            taskId,
            "Implement mock task for " + taskId,
            "MOD-1",
            "CTXS-1",
            RunKind.IMPL,
            "tmpl.impl.v0",
            List.of("TP-GIT-2"),
            null,
            new TaskContext("task:" + taskId, List.of(), List.of(), "git:BASE"),
            List.of("./"),
            List.of("./"),
            List.of(),
            List.of("Need clarification if missing facts."),
            List.of("work_report", "delivery_commit"),
            new GitAlloc("BASE", "run/" + runId, worktreePath)
        );
    }

    private static TaskPackage buildVerifyPackage(String runId, String taskId, String worktreePath) {
        return buildVerifyPackage(runId, taskId, worktreePath, List.of("git status --porcelain"));
    }

    private static TaskPackage buildVerifyPackage(
        String runId,
        String taskId,
        String worktreePath,
        List<String> verifyCommands
    ) {
        return new TaskPackage(
            runId,
            taskId,
            "Verify task for " + taskId,
            "MOD-1",
            "CTXS-1",
            RunKind.VERIFY,
            "tmpl.verify.v0",
            List.of("TP-GIT-2"),
            null,
            new TaskContext("task:" + taskId, List.of(), List.of(), "git:BASE"),
            List.of("./"),
            List.of(),
            verifyCommands,
            List.of("Need clarification if missing facts."),
            List.of("work_report"),
            new GitAlloc("BASE", "run/" + runId, worktreePath)
        );
    }

    private static Path initGitRepo(Path repo) throws Exception {
        Files.createDirectories(repo);
        runGit(repo, List.of("init"));
        runGit(repo, List.of("config", "user.email", "agentx@test.local"));
        runGit(repo, List.of("config", "user.name", "AgentX Test"));
        Files.writeString(repo.resolve("README.md"), "# test", StandardCharsets.UTF_8);
        runGit(repo, List.of("add", "README.md"));
        runGit(repo, List.of("commit", "-m", "init"));
        return repo;
    }

    private static boolean canRunGit() {
        try {
            Process process = new ProcessBuilder("git", "--version").start();
            boolean finished = process.waitFor(5, TimeUnit.SECONDS);
            return finished && process.exitValue() == 0;
        } catch (Exception ex) {
            return false;
        }
    }

    private static String runGit(Path repo, List<String> args) throws Exception {
        ProcessBuilder builder = new ProcessBuilder();
        java.util.ArrayList<String> command = new java.util.ArrayList<>();
        command.add("git");
        command.addAll(args);
        builder.command(command);
        builder.directory(repo.toFile());
        builder.redirectErrorStream(true);
        Process process = builder.start();
        boolean finished = process.waitFor(30, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            throw new IllegalStateException("git command timeout: " + args);
        }
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (process.exitValue() != 0) {
            throw new IllegalStateException("git command failed " + args + ", output=" + output);
        }
        return output;
    }

    private static RuntimeLlmConfigUseCase buildRuntimeConfigUseCase(String provider, String apiKey) {
        return buildRuntimeConfigUseCase(provider, apiKey, "zh-CN");
    }

    private static RuntimeLlmConfigUseCase buildRuntimeConfigUseCase(
        String provider,
        String apiKey,
        String outputLanguage
    ) {
        RuntimeLlmConfigUseCase.LlmProfile profile = new RuntimeLlmConfigUseCase.LlmProfile(
            provider,
            "langchain4j",
            "https://dashscope.aliyuncs.com/compatible-mode/v1",
            "qwen3.5-plus-2026-02-15",
            apiKey,
            120000
        );
        RuntimeLlmConfigUseCase.RuntimeConfigView config = new RuntimeLlmConfigUseCase.RuntimeConfigView(
            outputLanguage,
            profile,
            profile,
            1L,
            true
        );
        return new RuntimeLlmConfigUseCase() {
            @Override
            public RuntimeConfigView getCurrentConfig() {
                return config;
            }

            @Override
            public RuntimeConfigView resolveForRequestLanguage(String requestedOutputLanguage) {
                return config;
            }

            @Override
            public RuntimeConfigView apply(RuntimeConfigPatch patch) {
                throw new UnsupportedOperationException("not needed in test");
            }

            @Override
            public ConnectivityProbeResult probe(RuntimeConfigPatch patch) {
                throw new UnsupportedOperationException("not needed in test");
            }
        };
    }
}

