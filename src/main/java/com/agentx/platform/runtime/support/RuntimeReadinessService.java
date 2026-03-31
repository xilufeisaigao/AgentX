package com.agentx.platform.runtime.support;

import com.agentx.platform.runtime.agentkernel.model.AgentModelProperties;
import com.agentx.platform.runtime.application.workflow.profile.StackProfileRegistry;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Component
public class RuntimeReadinessService {

    private final RuntimeInfrastructureProperties runtimeProperties;
    private final AgentModelProperties modelProperties;
    private final StackProfileRegistry stackProfileRegistry;
    private final CommandRunner commandRunner;

    public RuntimeReadinessService(
            RuntimeInfrastructureProperties runtimeProperties,
            AgentModelProperties modelProperties,
            StackProfileRegistry stackProfileRegistry,
            CommandRunner commandRunner
    ) {
        this.runtimeProperties = runtimeProperties;
        this.modelProperties = modelProperties;
        this.stackProfileRegistry = stackProfileRegistry;
        this.commandRunner = commandRunner;
    }

    public RuntimeReadinessReport inspect() {
        List<ReadinessCheck> checks = new ArrayList<>();
        checks.add(dockerBinaryCheck());
        checks.add(dockerDaemonCheck());
        checks.add(repoRootCheck());
        checks.add(workspaceRootCheck());
        checks.add(stackProfileManifestCheck());
        checks.add(deepSeekSmokeCheck());
        return new RuntimeReadinessReport(LocalDateTime.now(), checks);
    }

    private ReadinessCheck dockerBinaryCheck() {
        CommandResult result = runCommand(List.of(runtimeProperties.getDocker().getBinary(), "--version"));
        if (result == null) {
            return new ReadinessCheck("docker-binary", false, "docker binary invocation failed before execution");
        }
        boolean passed = result.exitCode() == 0 && !result.timedOut();
        return new ReadinessCheck("docker-binary", passed, passed ? summarize(result) : "docker binary unavailable: " + summarize(result));
    }

    private ReadinessCheck dockerDaemonCheck() {
        CommandResult result = runCommand(List.of(runtimeProperties.getDocker().getBinary(), "info", "--format", "{{.ServerVersion}}"));
        if (result == null) {
            return new ReadinessCheck("docker-daemon", false, "docker daemon check could not be executed");
        }
        boolean passed = result.exitCode() == 0 && !result.timedOut();
        return new ReadinessCheck("docker-daemon", passed, passed ? summarize(result) : "docker daemon unavailable: " + summarize(result));
    }

    private ReadinessCheck repoRootCheck() {
        Path repoRoot = runtimeProperties.getRepoRoot();
        if (repoRoot == null) {
            return new ReadinessCheck("repo-root", false, "agentx.platform.runtime.repo-root is not configured");
        }
        Path normalized = repoRoot.toAbsolutePath().normalize();
        boolean exists = Files.isDirectory(normalized);
        return new ReadinessCheck(
                "repo-root",
                exists,
                exists
                        ? "repo root is available: " + normalized
                        : "repo root does not exist: " + normalized
        );
    }

    private ReadinessCheck workspaceRootCheck() {
        Path workspaceRoot = runtimeProperties.getWorkspaceRoot().toAbsolutePath().normalize();
        Path parent = workspaceRoot.getParent();
        boolean exists = Files.isDirectory(workspaceRoot) || parent == null || Files.isDirectory(parent);
        return new ReadinessCheck(
                "workspace-root",
                exists,
                exists
                        ? "workspace root is writable candidate: " + workspaceRoot
                        : "workspace root parent does not exist: " + workspaceRoot
        );
    }

    private ReadinessCheck stackProfileManifestCheck() {
        List<String> profileIds = stackProfileRegistry.listProfileIds();
        boolean passed = !profileIds.isEmpty();
        String detail = "no stack profile manifests resolved";
        if (passed) {
            for (String profileId : profileIds) {
                try {
                    stackProfileRegistry.resolveRequired(profileId);
                } catch (RuntimeException exception) {
                    return new ReadinessCheck(
                            "stack-profile-manifests",
                            false,
                            "failed to activate profile " + profileId + ": " + exception.getMessage()
                    );
                }
            }
            detail = "resolved " + profileIds.size() + " stack profiles: " + profileIds;
        }
        return new ReadinessCheck(
                "stack-profile-manifests",
                passed,
                detail
        );
    }

    private ReadinessCheck deepSeekSmokeCheck() {
        String apiKey = modelProperties.getDeepseek().getApiKey();
        boolean passed = apiKey != null && !apiKey.isBlank();
        return new ReadinessCheck(
                "deepseek-smoke-config",
                passed,
                passed
                        ? "DeepSeek API key is configured for smoke usage"
                        : "DeepSeek API key is missing; real smoke remains disabled"
        );
    }

    private CommandResult runCommand(List<String> command) {
        try {
            return commandRunner.run(new CommandSpec(
                    command,
                    workingDirectory(),
                    Duration.ofSeconds(10),
                    Map.of()
            ));
        } catch (RuntimeException exception) {
            return new CommandResult(1, "", exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage(), false, Duration.ZERO);
        }
    }

    private Path workingDirectory() {
        Path repoRoot = runtimeProperties.getRepoRoot();
        return repoRoot == null ? Path.of(".").toAbsolutePath().normalize() : repoRoot.toAbsolutePath().normalize();
    }

    private String summarize(CommandResult result) {
        if (result.stdout() != null && !result.stdout().isBlank()) {
            return result.stdout().trim();
        }
        if (result.stderr() != null && !result.stderr().isBlank()) {
            return result.stderr().trim();
        }
        return "exitCode=" + result.exitCode();
    }

    public record RuntimeReadinessReport(
            LocalDateTime checkedAt,
            List<ReadinessCheck> checks
    ) {

        public boolean ready() {
            return checks.stream().allMatch(ReadinessCheck::passed);
        }
    }

    public record ReadinessCheck(
            String name,
            boolean passed,
            String detail
    ) {
    }
}
