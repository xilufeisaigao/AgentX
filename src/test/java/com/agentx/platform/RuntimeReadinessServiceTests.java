package com.agentx.platform;

import com.agentx.platform.runtime.agentkernel.model.AgentModelProperties;
import com.agentx.platform.runtime.application.workflow.profile.StackProfileRegistry;
import com.agentx.platform.runtime.support.CommandResult;
import com.agentx.platform.runtime.support.CommandRunner;
import com.agentx.platform.runtime.support.CommandSpec;
import com.agentx.platform.runtime.support.RuntimeInfrastructureProperties;
import com.agentx.platform.runtime.support.RuntimeReadinessService;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RuntimeReadinessServiceTests {

    @Test
    void shouldReportReadyWhenBaselineChecksPass() throws Exception {
        RuntimeInfrastructureProperties runtimeProperties = new RuntimeInfrastructureProperties();
        Path repoRoot = Files.createTempDirectory("runtime-readiness-repo");
        Path workspaceRoot = Files.createTempDirectory("runtime-readiness-workspaces");
        runtimeProperties.setRepoRoot(repoRoot);
        runtimeProperties.setWorkspaceRoot(workspaceRoot);

        AgentModelProperties modelProperties = new AgentModelProperties();
        modelProperties.getDeepseek().setApiKey("test-key");

        RuntimeReadinessService service = new RuntimeReadinessService(
                runtimeProperties,
                modelProperties,
                new StackProfileRegistry(),
                new SuccessfulCommandRunner()
        );

        RuntimeReadinessService.RuntimeReadinessReport report = service.inspect();

        assertThat(report.ready()).isTrue();
        assertThat(report.checks())
                .extracting(RuntimeReadinessService.ReadinessCheck::name)
                .containsExactly(
                        "docker-binary",
                        "docker-daemon",
                        "repo-root",
                        "workspace-root",
                        "stack-profile-manifests",
                        "deepseek-smoke-config"
                );
    }

    @Test
    void shouldFlagMissingDeepSeekKeyWithoutThrowing() throws Exception {
        RuntimeInfrastructureProperties runtimeProperties = new RuntimeInfrastructureProperties();
        runtimeProperties.setRepoRoot(Files.createTempDirectory("runtime-readiness-repo-missing-key"));

        RuntimeReadinessService service = new RuntimeReadinessService(
                runtimeProperties,
                new AgentModelProperties(),
                new StackProfileRegistry(),
                new SuccessfulCommandRunner()
        );

        RuntimeReadinessService.RuntimeReadinessReport report = service.inspect();

        assertThat(report.ready()).isFalse();
        assertThat(report.checks())
                .filteredOn(check -> check.name().equals("deepseek-smoke-config"))
                .singleElement()
                .satisfies(check -> assertThat(check.passed()).isFalse());
    }

    private static class SuccessfulCommandRunner implements CommandRunner {

        @Override
        public CommandResult run(CommandSpec commandSpec) {
            List<String> command = commandSpec.command();
            String stdout = command.contains("info") ? "27.0.0" : "Docker version 27.0.0";
            return new CommandResult(0, stdout, "", false, Duration.ofMillis(100));
        }
    }
}
