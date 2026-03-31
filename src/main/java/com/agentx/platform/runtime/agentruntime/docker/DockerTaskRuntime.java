package com.agentx.platform.runtime.agentruntime.docker;

import com.agentx.platform.domain.execution.model.AgentPoolInstance;
import com.agentx.platform.domain.shared.model.JsonPayload;
import com.agentx.platform.runtime.agentruntime.AgentRuntime;
import com.agentx.platform.runtime.agentruntime.AgentRuntimeHandle;
import com.agentx.platform.runtime.agentruntime.ContainerLaunchSpec;
import com.agentx.platform.runtime.agentruntime.ContainerMount;
import com.agentx.platform.runtime.agentruntime.ContainerObservation;
import com.agentx.platform.runtime.agentruntime.ContainerState;
import com.agentx.platform.runtime.agentruntime.EphemeralExecutionResult;
import com.agentx.platform.runtime.agentruntime.ToolExecutionSpec;
import com.agentx.platform.runtime.support.CommandResult;
import com.agentx.platform.runtime.support.CommandRunner;
import com.agentx.platform.runtime.support.CommandSpec;
import com.agentx.platform.runtime.support.RuntimeInfrastructureProperties;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class DockerTaskRuntime implements AgentRuntime {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final RuntimeInfrastructureProperties runtimeProperties;
    private final CommandRunner commandRunner;
    private final ObjectMapper objectMapper;

    public DockerTaskRuntime(
            RuntimeInfrastructureProperties runtimeProperties,
            CommandRunner commandRunner,
            ObjectMapper objectMapper
    ) {
        this.runtimeProperties = runtimeProperties;
        this.commandRunner = commandRunner;
        this.objectMapper = objectMapper;
    }

    @Override
    public AgentRuntimeHandle launch(ContainerLaunchSpec launchSpec) {
        removeContainerIfPresent(launchSpec.containerName());
        CommandResult createResult = commandRunner.run(new CommandSpec(
                createCommand(launchSpec, false),
                configuredRepoRoot(),
                launchSpec.timeout(),
                Map.of()
        ));
        if (createResult.timedOut() || createResult.exitCode() != 0) {
            throw new IllegalStateException("docker create failed for " + launchSpec.containerName() + ": " + summarize(createResult));
        }

        CommandResult startResult = commandRunner.run(new CommandSpec(
                List.of(runtimeProperties.getDocker().getBinary(), "start", launchSpec.containerName()),
                configuredRepoRoot(),
                Duration.ofSeconds(15),
                Map.of()
        ));
        if (startResult.timedOut() || startResult.exitCode() != 0) {
            removeContainerIfPresent(launchSpec.containerName());
            throw new IllegalStateException("docker start failed for " + launchSpec.containerName() + ": " + summarize(startResult));
        }

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("containerId", firstNonBlank(startResult.stdout(), createResult.stdout(), launchSpec.containerName()));
        metadata.put("containerName", launchSpec.containerName());
        metadata.put("image", launchSpec.image());
        metadata.put("workingDirectory", launchSpec.workingDirectory());
        metadata.put("command", launchSpec.command());
        return new AgentRuntimeHandle(
                "docker://" + launchSpec.containerName(),
                jsonPayload(metadata)
        );
    }

    @Override
    public ContainerObservation observe(AgentPoolInstance agentInstance) {
        DockerMetadata metadata = metadata(agentInstance);
        CommandResult inspectResult = commandRunner.run(new CommandSpec(
                List.of(runtimeProperties.getDocker().getBinary(), "inspect", "--format", "{{json .State}}", metadata.containerName()),
                configuredRepoRoot(),
                Duration.ofSeconds(10),
                Map.of()
        ));
        if (inspectResult.timedOut()) {
            return new ContainerObservation(ContainerState.MISSING, null, inspectResult.stderr(), true, null, null);
        }
        if (inspectResult.exitCode() != 0) {
            return new ContainerObservation(ContainerState.MISSING, null, inspectResult.stderr(), false, null, null);
        }
        try {
            Map<String, Object> state = objectMapper.readValue(inspectResult.stdout(), MAP_TYPE);
            boolean running = booleanValue(state.get("Running"));
            Integer exitCode = state.get("ExitCode") == null ? null : Integer.parseInt(String.valueOf(state.get("ExitCode")));
            LocalDateTime startedAt = parseTimestamp(state.get("StartedAt"));
            LocalDateTime finishedAt = parseTimestamp(state.get("FinishedAt"));
            String logs = readLogs(metadata.containerName());
            return new ContainerObservation(
                    running ? ContainerState.RUNNING : ContainerState.EXITED,
                    exitCode,
                    logs,
                    false,
                    startedAt,
                    finishedAt
            );
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("failed to parse docker inspect state for " + metadata.containerName(), exception);
        }
    }

    @Override
    public void terminate(AgentPoolInstance agentInstance) {
        DockerMetadata metadata = metadata(agentInstance);
        commandRunner.run(new CommandSpec(
                List.of(runtimeProperties.getDocker().getBinary(), "rm", "-f", metadata.containerName()),
                configuredRepoRoot(),
                Duration.ofSeconds(10),
                Map.of()
        ));
    }

    @Override
    public EphemeralExecutionResult executeOnce(ContainerLaunchSpec launchSpec) {
        CommandResult result = commandRunner.run(new CommandSpec(
                createCommand(launchSpec, true),
                configuredRepoRoot(),
                launchSpec.timeout(),
                Map.of()
        ));
        return new EphemeralExecutionResult(
                result.exitCode(),
                result.stdout(),
                result.stderr(),
                result.timedOut(),
                result.elapsed()
        );
    }

    @Override
    public EphemeralExecutionResult executeInRunningContainer(AgentPoolInstance agentInstance, ToolExecutionSpec executionSpec) {
        DockerMetadata metadata = metadata(agentInstance);
        CommandResult result = commandRunner.run(new CommandSpec(
                execCommand(metadata.containerName(), executionSpec),
                configuredRepoRoot(),
                executionSpec.timeout(),
                Map.of()
        ));
        return new EphemeralExecutionResult(
                result.exitCode(),
                result.stdout(),
                result.stderr(),
                result.timedOut(),
                result.elapsed()
        );
    }

    private List<String> createCommand(ContainerLaunchSpec launchSpec, boolean runOnce) {
        List<String> command = new ArrayList<>();
        List<String> executable = launchSpec.command();
        command.add(runtimeProperties.getDocker().getBinary());
        command.add(runOnce ? "run" : "create");
        if (runOnce) {
            command.add("--rm");
        }
        command.add("--name");
        command.add(launchSpec.containerName());
        command.add("--workdir");
        command.add(launchSpec.workingDirectory());
        command.add("--network");
        command.add(runtimeProperties.getDocker().getNetworkMode());
        for (ContainerMount mount : launchSpec.mounts()) {
            command.add("--mount");
            command.add(mountExpression(mount));
        }
        for (Map.Entry<String, String> entry : launchSpec.environment().entrySet()) {
            command.add("--env");
            command.add(entry.getKey() + "=" + entry.getValue());
        }
        // Deterministic contracts carry a full argv, not just args for an image-defined entrypoint.
        // Overriding the entrypoint keeps images such as alpine/git from interpreting "sh" as a git subcommand.
        if (!executable.isEmpty()) {
            command.add("--entrypoint");
            command.add(executable.get(0));
        }
        command.add(launchSpec.image());
        if (executable.size() > 1) {
            command.addAll(executable.subList(1, executable.size()));
        }
        return command;
    }

    private List<String> execCommand(String containerName, ToolExecutionSpec executionSpec) {
        List<String> command = new ArrayList<>();
        List<String> executable = executionSpec.command();
        command.add(runtimeProperties.getDocker().getBinary());
        command.add("exec");
        command.add("--workdir");
        command.add(executionSpec.workingDirectory());
        for (Map.Entry<String, String> entry : executionSpec.environment().entrySet()) {
            command.add("--env");
            command.add(entry.getKey() + "=" + entry.getValue());
        }
        command.add(containerName);
        command.addAll(executable);
        return command;
    }

    private String mountExpression(ContainerMount mount) {
        StringBuilder builder = new StringBuilder();
        builder.append("type=bind,source=").append(mount.sourcePath().toAbsolutePath());
        builder.append(",target=").append(mount.targetPath());
        if (mount.readOnly()) {
            builder.append(",readonly");
        }
        return builder.toString();
    }

    private String readLogs(String containerName) {
        CommandResult result = commandRunner.run(new CommandSpec(
                List.of(runtimeProperties.getDocker().getBinary(), "logs", "--tail", "200", containerName),
                configuredRepoRoot(),
                Duration.ofSeconds(10),
                Map.of()
        ));
        return firstNonBlank(result.stdout(), result.stderr(), "");
    }

    private void removeContainerIfPresent(String containerName) {
        commandRunner.run(new CommandSpec(
                List.of(runtimeProperties.getDocker().getBinary(), "rm", "-f", containerName),
                configuredRepoRoot(),
                Duration.ofSeconds(10),
                Map.of()
        ));
    }

    private DockerMetadata metadata(AgentPoolInstance agentInstance) {
        JsonPayload payload = agentInstance.runtimeMetadataJson();
        if (payload == null) {
            throw new IllegalStateException("missing runtime metadata for agent instance " + agentInstance.agentInstanceId());
        }
        try {
            Map<String, Object> data = objectMapper.readValue(payload.json(), MAP_TYPE);
            return new DockerMetadata(
                    String.valueOf(data.get("containerName")),
                    String.valueOf(data.get("containerId"))
            );
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("failed to parse runtime metadata for agent instance " + agentInstance.agentInstanceId(), exception);
        }
    }

    private JsonPayload jsonPayload(Map<String, Object> data) {
        try {
            return new JsonPayload(objectMapper.writeValueAsString(data));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("failed to write runtime metadata", exception);
        }
    }

    private boolean booleanValue(Object rawValue) {
        if (rawValue instanceof Boolean boolValue) {
            return boolValue;
        }
        return rawValue != null && Boolean.parseBoolean(String.valueOf(rawValue));
    }

    private LocalDateTime parseTimestamp(Object rawValue) {
        if (rawValue == null) {
            return null;
        }
        String stringValue = String.valueOf(rawValue);
        if (stringValue.isBlank() || stringValue.startsWith("0001-01-01")) {
            return null;
        }
        return LocalDateTime.parse(stringValue.substring(0, 19));
    }

    private String summarize(CommandResult result) {
        return firstNonBlank(result.stderr(), result.stdout(), "docker command failed");
    }

    private String firstNonBlank(String first, String second, String fallback) {
        if (first != null && !first.isBlank()) {
            return first.trim();
        }
        if (second != null && !second.isBlank()) {
            return second.trim();
        }
        return fallback;
    }

    private Path configuredRepoRoot() {
        return runtimeProperties.requiredRepoRoot();
    }

    private record DockerMetadata(
            String containerName,
            String containerId
    ) {
    }
}
