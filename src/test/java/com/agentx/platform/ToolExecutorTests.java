package com.agentx.platform;

import com.agentx.platform.domain.execution.model.AgentPoolInstance;
import com.agentx.platform.domain.execution.model.AgentPoolStatus;
import com.agentx.platform.domain.execution.model.CleanupStatus;
import com.agentx.platform.domain.execution.model.GitWorkspace;
import com.agentx.platform.domain.execution.model.GitWorkspaceStatus;
import com.agentx.platform.domain.execution.model.RunKind;
import com.agentx.platform.domain.execution.model.TaskRun;
import com.agentx.platform.domain.execution.model.TaskRunStatus;
import com.agentx.platform.domain.planning.model.WorkTask;
import com.agentx.platform.domain.planning.model.WorkTaskStatus;
import com.agentx.platform.domain.shared.model.ActorRef;
import com.agentx.platform.domain.shared.model.ActorType;
import com.agentx.platform.domain.shared.model.JsonPayload;
import com.agentx.platform.domain.shared.model.WriteScope;
import com.agentx.platform.runtime.agentruntime.AgentRuntime;
import com.agentx.platform.runtime.agentruntime.EphemeralExecutionResult;
import com.agentx.platform.runtime.tooling.ExplorationCommandSpec;
import com.agentx.platform.runtime.application.workflow.TaskExecutionContract;
import com.agentx.platform.runtime.support.ProcessCommandRunner;
import com.agentx.platform.runtime.tooling.CompiledToolCatalog;
import com.agentx.platform.runtime.tooling.HttpEndpointSpec;
import com.agentx.platform.runtime.tooling.ToolCall;
import com.agentx.platform.runtime.tooling.ToolCatalogEntry;
import com.agentx.platform.runtime.tooling.ToolExecutor;
import com.agentx.platform.runtime.tooling.ToolCallNormalizer;
import com.agentx.platform.runtime.tooling.ToolRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ToolExecutorTests {

    @Test
    void shouldWriteFileWithinWriteScope() throws IOException {
        AgentRuntime agentRuntime = mock(AgentRuntime.class);
        ObjectMapper objectMapper = new ObjectMapper();
        ToolExecutor toolExecutor = new ToolExecutor(
                agentRuntime,
                new ProcessCommandRunner(),
                new ToolRegistry(),
                new ToolCallNormalizer(objectMapper),
                objectMapper
        );
        Path workspaceRoot = Files.createTempDirectory("tool-executor-filesystem");

        ToolExecutor.ToolExecutionOutcome outcome = toolExecutor.executeForRun(
                task(),
                run(),
                agentInstance(),
                workspace(workspaceRoot),
                contract(Map.of(), Map.of()),
                new ToolCall(
                        "tool-filesystem",
                        "write_file",
                        Map.of("path", "src/main/java/App.java", "content", "class App {}"),
                        "write application file"
                )
        );

        assertThat(outcome.succeeded()).isTrue();
        assertThat(Files.readString(workspaceRoot.resolve("src/main/java/App.java"))).isEqualTo("class App {}");
        assertThat(outcome.payload().json()).contains("\"callId\"");
        assertThat(outcome.payload().json()).contains("\"argumentsSummary\"");
    }

    @Test
    void shouldRejectShellCommandOutsideAllowlist() throws IOException {
        AgentRuntime agentRuntime = mock(AgentRuntime.class);
        ObjectMapper objectMapper = new ObjectMapper();
        ToolExecutor toolExecutor = new ToolExecutor(
                agentRuntime,
                new ProcessCommandRunner(),
                new ToolRegistry(),
                new ToolCallNormalizer(objectMapper),
                objectMapper
        );
        Path workspaceRoot = Files.createTempDirectory("tool-executor-shell");

        assertThatThrownBy(() -> toolExecutor.executeForRun(
                task(),
                run(),
                agentInstance(),
                workspace(workspaceRoot),
                contract(Map.of(), Map.of()),
                new ToolCall(
                        "tool-shell",
                        "run_command",
                        Map.of("commandId", "not-allowed"),
                        "run something forbidden"
                )
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unsupported commandId");
        verifyNoInteractions(agentRuntime);
    }

    @Test
    void shouldExecuteGitHeadAgainstWorkspace() throws IOException {
        AgentRuntime agentRuntime = mock(AgentRuntime.class);
        ObjectMapper objectMapper = new ObjectMapper();
        ToolExecutor toolExecutor = new ToolExecutor(
                agentRuntime,
                new ProcessCommandRunner(),
                new ToolRegistry(),
                new ToolCallNormalizer(objectMapper),
                objectMapper
        );
        Path repoRoot = Files.createTempDirectory("tool-executor-git");
        ProcessCommandRunner commandRunner = new ProcessCommandRunner();
        commandRunner.run(new com.agentx.platform.runtime.support.CommandSpec(List.of("git", "init", "-b", "main"), repoRoot, Duration.ofSeconds(20), Map.of()));
        commandRunner.run(new com.agentx.platform.runtime.support.CommandSpec(List.of("git", "config", "user.email", "tool@test.local"), repoRoot, Duration.ofSeconds(20), Map.of()));
        commandRunner.run(new com.agentx.platform.runtime.support.CommandSpec(List.of("git", "config", "user.name", "tool-test"), repoRoot, Duration.ofSeconds(20), Map.of()));
        Files.writeString(repoRoot.resolve("README.md"), "hello");
        commandRunner.run(new com.agentx.platform.runtime.support.CommandSpec(List.of("git", "add", "README.md"), repoRoot, Duration.ofSeconds(20), Map.of()));
        commandRunner.run(new com.agentx.platform.runtime.support.CommandSpec(List.of("git", "commit", "-m", "init"), repoRoot, Duration.ofSeconds(20), Map.of()));

        ToolExecutor.ToolExecutionOutcome outcome = toolExecutor.executeForRun(
                task(),
                run(),
                agentInstance(),
                workspace(repoRoot),
                contract(Map.of(), Map.of()),
                new ToolCall("tool-git", "git_head", Map.of(), "read HEAD")
        );

        assertThat(outcome.succeeded()).isTrue();
        assertThat(outcome.payload().json()).contains("stdout");
    }

    @Test
    void shouldCallAllowlistedHttpEndpoint() throws IOException {
        AgentRuntime agentRuntime = mock(AgentRuntime.class);
        ObjectMapper objectMapper = new ObjectMapper();
        ToolExecutor toolExecutor = new ToolExecutor(
                agentRuntime,
                new ProcessCommandRunner(),
                new ToolRegistry(),
                new ToolCallNormalizer(objectMapper),
                objectMapper
        );
        Path workspaceRoot = Files.createTempDirectory("tool-executor-http");
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/health", exchange -> {
            byte[] body = "ok".getBytes();
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            ToolExecutor.ToolExecutionOutcome outcome = toolExecutor.executeForRun(
                    task(),
                    run(),
                    agentInstance(),
                    workspace(workspaceRoot),
                    contract(
                            Map.of(),
                            Map.of("local-http", new HttpEndpointSpec(
                                    "local-http",
                                    "http://127.0.0.1:" + server.getAddress().getPort(),
                                    List.of("GET"),
                                    "local server"
                            ))
                    ),
                    new ToolCall(
                            "tool-http-client",
                            "http_request",
                            Map.of("endpointId", "local-http", "method", "GET", "path", "/health"),
                            "probe local health endpoint"
                    )
            );

            assertThat(outcome.succeeded()).isTrue();
            assertThat(outcome.payload().json()).contains("ok");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void shouldExecuteAllowlistedShellCommandThroughRuntime() throws IOException {
        AgentRuntime agentRuntime = mock(AgentRuntime.class);
        when(agentRuntime.executeInRunningContainer(any(), any())).thenReturn(new EphemeralExecutionResult(
                0,
                "Python 3.11.0",
                "",
                false,
                Duration.ofSeconds(1)
        ));
        ObjectMapper objectMapper = new ObjectMapper();
        ToolExecutor toolExecutor = new ToolExecutor(
                agentRuntime,
                new ProcessCommandRunner(),
                new ToolRegistry(),
                new ToolCallNormalizer(objectMapper),
                objectMapper
        );
        Path workspaceRoot = Files.createTempDirectory("tool-executor-shell-ok");

        ToolExecutor.ToolExecutionOutcome outcome = toolExecutor.executeForRun(
                task(),
                run(),
                agentInstance(),
                workspace(workspaceRoot),
                contract(Map.of("python-version", List.of("sh", "-lc", "python --version")), Map.of()),
                new ToolCall(
                        "tool-shell",
                        "run_command",
                        Map.of("commandId", "python-version"),
                        "check python runtime"
                )
        );

        assertThat(outcome.succeeded()).isTrue();
    }

    @Test
    void shouldMaterializeMarkerFileBeforeDeliveryCommands() throws IOException {
        AgentRuntime agentRuntime = mock(AgentRuntime.class);
        when(agentRuntime.executeInRunningContainer(any(), any())).thenReturn(new EphemeralExecutionResult(
                0,
                "committed",
                "",
                false,
                Duration.ofSeconds(1)
        ));
        ObjectMapper objectMapper = new ObjectMapper();
        ToolExecutor toolExecutor = new ToolExecutor(
                agentRuntime,
                new ProcessCommandRunner(),
                new ToolRegistry(),
                new ToolCallNormalizer(objectMapper),
                objectMapper
        );
        Path workspaceRoot = Files.createTempDirectory("tool-executor-delivery-marker");
        WorkTask testTask = new WorkTask(
                "task-test",
                "module-test",
                "Add tests",
                "Write regression tests",
                "java-backend-test",
                WorkTaskStatus.IN_PROGRESS,
                List.of(new WriteScope("src/test/java")),
                null,
                new ActorRef(ActorType.AGENT, "architect-agent")
        );
        TaskExecutionContract contract = new TaskExecutionContract(
                "maven:3.9.11-eclipse-temurin-21",
                "/workspace",
                List.of("sh", "-lc", "sleep 1"),
                Map.of("TASK_ID", "task-test"),
                20,
                "LINUX_CONTAINER",
                "POSIX_SH",
                "/workspace",
                "/workspace",
                List.of(".", "src/test/java"),
                "BROAD_WORKSPACE",
                new CompiledToolCatalog(List.of(
                        new ToolCatalogEntry("tool-filesystem", "Filesystem", "DIRECT", filesystemOperations(), "schema://tool-filesystem", ""),
                        new ToolCatalogEntry("tool-shell", "Shell", "DIRECT", List.of("run_command", "run_exploration_command"), "schema://tool-shell", "")
                )),
                List.of("rt-java-21", "rt-git"),
                Map.of("MARKER_FILE", "src/test/java/.agentx-task-test.txt", "ATTEMPT_NUMBER", "1"),
                Map.of("git-commit-delivery", List.of("sh", "-lc", "git add -A && git commit -m test")),
                explorationCommands(),
                Map.of(),
                List.of(new ToolCall("tool-shell", "run_command", Map.of("commandId", "git-commit-delivery"), "commit task changes")),
                List.of(),
                List.of("src/test/java"),
                "src/test/java/.agentx-task-test.txt"
        );

        ToolExecutor.ToolExecutionOutcome outcome = toolExecutor.executeDeliveryPlan(
                testTask,
                run(),
                agentInstance(),
                workspace(workspaceRoot),
                contract
        );

        Path markerPath = workspaceRoot.resolve("src/test/java/.agentx-task-test.txt");
        assertThat(outcome.succeeded()).isTrue();
        assertThat(Files.exists(markerPath)).isTrue();
        assertThat(Files.readString(markerPath)).contains("taskId=task-test").contains("runId=task-1-run-001");
    }

    @Test
    void shouldListDirectoryAsFirstClassFilesystemOperation() throws IOException {
        AgentRuntime agentRuntime = mock(AgentRuntime.class);
        ObjectMapper objectMapper = new ObjectMapper();
        ToolExecutor toolExecutor = new ToolExecutor(
                agentRuntime,
                new ProcessCommandRunner(),
                new ToolRegistry(),
                new ToolCallNormalizer(objectMapper),
                objectMapper
        );
        Path workspaceRoot = Files.createTempDirectory("tool-executor-list-dir");
        Files.createDirectories(workspaceRoot.resolve("src/main/java"));
        Files.writeString(workspaceRoot.resolve("src/main/java/App.java"), "class App {}");

        ToolExecutor.ToolExecutionOutcome outcome = toolExecutor.executeForRun(
                task(),
                run(),
                agentInstance(),
                workspace(workspaceRoot),
                contract(Map.of(), Map.of()),
                new ToolCall(
                        "tool-filesystem",
                        "list_directory",
                        Map.of("path", "src/main"),
                        "list source tree"
                )
        );

        assertThat(outcome.succeeded()).isTrue();
        assertThat(outcome.payload().json()).contains("\"directory\":true");
        assertThat(outcome.payload().json()).contains("src/main/java");
    }

    @Test
    void shouldReadStructuredLineRange() throws IOException {
        AgentRuntime agentRuntime = mock(AgentRuntime.class);
        ObjectMapper objectMapper = new ObjectMapper();
        ToolExecutor toolExecutor = new ToolExecutor(
                agentRuntime,
                new ProcessCommandRunner(),
                new ToolRegistry(),
                new ToolCallNormalizer(objectMapper),
                objectMapper
        );
        Path workspaceRoot = Files.createTempDirectory("tool-executor-read-range");
        Files.createDirectories(workspaceRoot.resolve("src/main/java"));
        Files.writeString(
                workspaceRoot.resolve("src/main/java/App.java"),
                "line1%nline2%nline3%nline4".formatted()
        );

        ToolExecutor.ToolExecutionOutcome outcome = toolExecutor.executeForRun(
                task(),
                run(),
                agentInstance(),
                workspace(workspaceRoot),
                contract(Map.of(), Map.of()),
                new ToolCall(
                        "tool-filesystem",
                        "read_range",
                        Map.of("path", "src/main/java/App.java", "startLine", 2, "endLine", 3),
                        "read range"
                )
        );

        assertThat(outcome.succeeded()).isTrue();
        assertThat(outcome.payload().json()).contains("2: line2");
        assertThat(outcome.payload().json()).contains("3: line3");
    }

    @Test
    void shouldRunExplorationCommandUsingStructuredArguments() throws IOException {
        AgentRuntime agentRuntime = mock(AgentRuntime.class);
        when(agentRuntime.executeInRunningContainer(any(), any())).thenReturn(new EphemeralExecutionResult(
                0,
                "src/main/java/App.java:1 class App {}",
                "",
                false,
                Duration.ofSeconds(1)
        ));
        ObjectMapper objectMapper = new ObjectMapper();
        ToolExecutor toolExecutor = new ToolExecutor(
                agentRuntime,
                new ProcessCommandRunner(),
                new ToolRegistry(),
                new ToolCallNormalizer(objectMapper),
                objectMapper
        );
        Path workspaceRoot = Files.createTempDirectory("tool-executor-explore-ok");

        ToolExecutor.ToolExecutionOutcome outcome = toolExecutor.executeForRun(
                task(),
                run(),
                agentInstance(),
                workspace(workspaceRoot),
                contract(Map.of("python-version", List.of("sh", "-lc", "python --version")), Map.of()),
                new ToolCall(
                        "tool-shell",
                        "run_exploration_command",
                        Map.of("commandId", "grep-text", "query", "App", "path", "src/main/java"),
                        "grep app"
                )
        );

        assertThat(outcome.succeeded()).isTrue();
        assertThat(outcome.payload().json()).contains("\"commandId\":\"grep-text\"");
        assertThat(outcome.payload().json()).contains("\"argv\"");
    }

    @Test
    void shouldRejectExplorationCommandWithUnknownArgument() throws IOException {
        AgentRuntime agentRuntime = mock(AgentRuntime.class);
        ObjectMapper objectMapper = new ObjectMapper();
        ToolExecutor toolExecutor = new ToolExecutor(
                agentRuntime,
                new ProcessCommandRunner(),
                new ToolRegistry(),
                new ToolCallNormalizer(objectMapper),
                objectMapper
        );
        Path workspaceRoot = Files.createTempDirectory("tool-executor-explore-invalid");

        assertThatThrownBy(() -> toolExecutor.executeForRun(
                task(),
                run(),
                agentInstance(),
                workspace(workspaceRoot),
                contract(Map.of(), Map.of()),
                new ToolCall(
                        "tool-shell",
                        "run_exploration_command",
                        Map.of("commandId", "grep-text", "query", "App", "path", "src/main/java", "shell", "rm -rf /"),
                        "invalid grep app"
                )
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not allowed for exploration command");
        verifyNoInteractions(agentRuntime);
    }

    private TaskExecutionContract contract(
            Map<String, List<String>> allowedCommands,
            Map<String, HttpEndpointSpec> endpoints
    ) {
        return new TaskExecutionContract(
                "maven:3.9.11-eclipse-temurin-21",
                "/workspace",
                List.of("sh", "-lc", "sleep 1"),
                Map.of("TASK_ID", "task-1"),
                20,
                "LINUX_CONTAINER",
                "POSIX_SH",
                "/workspace",
                "/workspace",
                List.of(".", "src/main/java"),
                "BROAD_WORKSPACE",
                new CompiledToolCatalog(List.of(
                        new ToolCatalogEntry("tool-filesystem", "Filesystem", "DIRECT", filesystemOperations(), "schema://tool-filesystem", ""),
                        new ToolCatalogEntry("tool-shell", "Shell", "DIRECT", List.of("run_command", "run_exploration_command"), "schema://tool-shell", ""),
                        new ToolCatalogEntry("tool-git", "Git", "DIRECT", List.of("git_status", "git_diff_stat", "git_head"), "schema://tool-git", ""),
                        new ToolCatalogEntry("tool-http-client", "HTTP Client", "DIRECT", List.of("http_request"), "schema://tool-http-client", "")
                )),
                List.of("rt-java-21", "rt-git"),
                Map.of("MARKER_FILE", "src/main/java/.agentx-task-1.txt", "ATTEMPT_NUMBER", "1"),
                allowedCommands,
                explorationCommands(),
                endpoints,
                List.of(),
                List.of(),
                List.of("src/main/java"),
                "src/main/java/.agentx-task-1.txt"
        );
    }

    private List<String> filesystemOperations() {
        return List.of(
                "read_file",
                "read_range",
                "head_file",
                "tail_file",
                "list_directory",
                "glob_files",
                "grep_text",
                "write_file",
                "delete_file"
        );
    }

    private Map<String, ExplorationCommandSpec> explorationCommands() {
        Map<String, ExplorationCommandSpec> commands = new LinkedHashMap<>();
        commands.put("grep-text", new ExplorationCommandSpec(
                "grep-text",
                List.of("grep", "-R", "-n", "--binary-files=without-match", "--color=never", "${query}", "${path}"),
                List.of("query", "path"),
                "Readonly recursive grep inside the workspace."
        ));
        commands.put("read-range", new ExplorationCommandSpec(
                "read-range",
                List.of("sed", "-n", "${startLine},${endLine}p", "${path}"),
                List.of("startLine", "endLine", "path"),
                "Readonly line-range read for a file."
        ));
        commands.put("head-file", new ExplorationCommandSpec(
                "head-file",
                List.of("head", "-n", "${lineCount}", "${path}"),
                List.of("lineCount", "path"),
                "Readonly head read for a file."
        ));
        commands.put("tail-file", new ExplorationCommandSpec(
                "tail-file",
                List.of("tail", "-n", "${lineCount}", "${path}"),
                List.of("lineCount", "path"),
                "Readonly tail read for a file."
        ));
        return Map.copyOf(commands);
    }

    private WorkTask task() {
        return new WorkTask(
                "task-1",
                "module-1",
                "Implement feature",
                "Write feature output",
                "java-backend-code",
                WorkTaskStatus.IN_PROGRESS,
                List.of(new WriteScope("src/main/java")),
                null,
                new ActorRef(ActorType.AGENT, "architect-agent")
        );
    }

    private TaskRun run() {
        return new TaskRun(
                "task-1-run-001",
                "task-1",
                "agent-instance-1",
                TaskRunStatus.RUNNING,
                RunKind.IMPL,
                "snapshot-1",
                LocalDateTime.now().plusSeconds(30),
                LocalDateTime.now(),
                LocalDateTime.now(),
                null,
                JsonPayload.emptyObject()
        );
    }

    private AgentPoolInstance agentInstance() {
        return new AgentPoolInstance(
                "agent-instance-1",
                "coding-agent-java",
                "docker",
                AgentPoolStatus.READY,
                "TASK_RUN_CONTAINER",
                "workflow-1",
                LocalDateTime.now().plusSeconds(30),
                LocalDateTime.now(),
                "docker://agent-instance-1",
                JsonPayload.emptyObject()
        );
    }

    private GitWorkspace workspace(Path workspaceRoot) {
        return new GitWorkspace(
                "workspace-1",
                "task-1-run-001",
                "task-1",
                GitWorkspaceStatus.READY,
                workspaceRoot.toString(),
                workspaceRoot.toString(),
                "task/task-1/001",
                "base-commit",
                null,
                null,
                CleanupStatus.PENDING
        );
    }
}
