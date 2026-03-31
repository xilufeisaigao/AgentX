package com.agentx.platform;

import com.agentx.platform.domain.execution.model.AgentPoolInstance;
import com.agentx.platform.domain.execution.model.AgentPoolStatus;
import com.agentx.platform.domain.shared.model.JsonPayload;
import com.agentx.platform.runtime.agentruntime.AgentRuntimeHandle;
import com.agentx.platform.runtime.agentruntime.ContainerLaunchSpec;
import com.agentx.platform.runtime.agentruntime.ContainerMount;
import com.agentx.platform.runtime.agentruntime.ContainerObservation;
import com.agentx.platform.runtime.agentruntime.ContainerState;
import com.agentx.platform.runtime.agentruntime.EphemeralExecutionResult;
import com.agentx.platform.runtime.agentruntime.docker.DockerTaskRuntime;
import com.agentx.platform.runtime.support.CommandResult;
import com.agentx.platform.runtime.support.CommandRunner;
import com.agentx.platform.runtime.support.CommandSpec;
import com.agentx.platform.runtime.support.RuntimeInfrastructureProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;
import java.util.Queue;

import static org.assertj.core.api.Assertions.assertThat;

class DockerTaskRuntimeTests {

    @Test
    void shouldLaunchObserveAndExecuteContainersThroughCliCommands() {
        RuntimeInfrastructureProperties properties = new RuntimeInfrastructureProperties();
        properties.setRepoRoot(Path.of("."));
        RecordingCommandRunner commandRunner = new RecordingCommandRunner(
                new CommandResult(0, "", "", false, Duration.ofMillis(10)),
                new CommandResult(0, "container-created", "", false, Duration.ofMillis(10)),
                new CommandResult(0, "container-started", "", false, Duration.ofMillis(10)),
                new CommandResult(0, "{\"Running\":true,\"ExitCode\":0,\"StartedAt\":\"2026-03-28T10:00:00.000000000Z\",\"FinishedAt\":\"0001-01-01T00:00:00Z\"}", "", false, Duration.ofMillis(10)),
                new CommandResult(0, "runtime logs", "", false, Duration.ofMillis(10)),
                new CommandResult(0, "verify ok", "", false, Duration.ofMillis(10))
        );
        DockerTaskRuntime runtime = new DockerTaskRuntime(properties, commandRunner, new ObjectMapper());
        ContainerLaunchSpec launchSpec = new ContainerLaunchSpec(
                "agentx-run-01",
                "alpine/git:2.47.2",
                "/workspace",
                List.of("sh", "-lc", "echo test"),
                List.of(new ContainerMount(Path.of("C:/tmp/worktree"), "/workspace", false)),
                Map.of("TASK_ID", "task-1"),
                Duration.ofSeconds(10)
        );

        AgentRuntimeHandle handle = runtime.launch(launchSpec);
        ContainerObservation observation = runtime.observe(new AgentPoolInstance(
                "ainst-01",
                "coding-agent-java",
                "docker",
                AgentPoolStatus.READY,
                "TASK_RUN_CONTAINER",
                "workflow-1",
                LocalDateTime.now(),
                LocalDateTime.now(),
                handle.endpointRef(),
                handle.runtimeMetadataJson()
        ));
        EphemeralExecutionResult executionResult = runtime.executeOnce(launchSpec);

        assertThat(commandRunner.commands()).hasSize(6);
        assertThat(commandRunner.commands().get(0).command()).contains("rm", "-f");
        assertThat(commandRunner.commands().get(1).command()).contains("create");
        assertThat(commandRunner.commands().get(2).command()).contains("start");
        assertThat(observation.state()).isEqualTo(ContainerState.RUNNING);
        assertThat(observation.logOutput()).isEqualTo("runtime logs");
        assertThat(executionResult.succeeded()).isTrue();
    }

    private static final class RecordingCommandRunner implements CommandRunner {

        private final Queue<CommandResult> results;
        private final List<CommandSpec> commands = new java.util.ArrayList<>();

        private RecordingCommandRunner(CommandResult... results) {
            this.results = new ArrayDeque<>(List.of(results));
        }

        @Override
        public CommandResult run(CommandSpec commandSpec) {
            commands.add(commandSpec);
            return results.remove();
        }

        private List<CommandSpec> commands() {
            return commands;
        }
    }
}
