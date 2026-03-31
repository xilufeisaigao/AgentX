package com.agentx.platform;

import com.agentx.platform.domain.catalog.port.CatalogStore;
import com.agentx.platform.domain.execution.port.ExecutionStore;
import com.agentx.platform.runtime.agentruntime.AgentRuntime;
import com.agentx.platform.runtime.application.workflow.FixedCodingWorkflowUseCase;
import com.agentx.platform.runtime.application.workflow.RuntimeSupervisorSweep;
import com.agentx.platform.runtime.evaluation.WorkflowEvalCenter;
import com.agentx.platform.runtime.evaluation.WorkflowEvalTraceCollector;
import com.agentx.platform.support.TestGitRepoHelper;
import com.agentx.platform.support.eval.RealWorkflowEvalFixtures;
import com.agentx.platform.support.eval.RealWorkflowEvalProfileSeeds;
import com.agentx.platform.support.eval.RealWorkflowEvalRunner;
import com.agentx.platform.support.eval.RealWorkflowEvalScenarioPackLoader;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
class DeepSeekStrictWorkflowEvalIT {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("agentx_platform");

    private static final Path RUN_ROOT = Path.of(
                    "artifacts",
                    "evaluation-runs",
                    "strict-" + System.currentTimeMillis()
            )
            .toAbsolutePath()
            .normalize();
    private static final Path REPO_ROOT = RUN_ROOT.resolve("repo");
    private static final Path WORKSPACE_ROOT = RUN_ROOT.resolve("workspaces");
    private static final Path ARTIFACT_ROOT = RUN_ROOT.resolve("artifacts");
    private static final Path EXPORT_ROOT = RUN_ROOT.resolve("exported-commits");
    private static final Path REVIEW_BUNDLE_ROOT = RUN_ROOT.resolve("review-bundle");
    private static final Path EVAL_ARTIFACT_ROOT = RUN_ROOT.resolve("reports");

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("agentx.platform.runtime.repo-root", REPO_ROOT::toString);
        registry.add("agentx.platform.runtime.base-branch", () -> "main");
        registry.add("agentx.platform.runtime.workspace-root", WORKSPACE_ROOT::toString);
        registry.add("agentx.platform.runtime.driver-enabled", () -> false);
        registry.add("agentx.platform.runtime.supervisor-enabled", () -> false);
        registry.add("agentx.platform.runtime.blocking-timeout", () -> "PT480S");
        registry.add("agentx.platform.model.timeout", () -> "PT60S");
        registry.add("agentx.platform.model.max-retries", () -> 2);
        registry.add("agentx.platform.evaluation.artifact-root", EVAL_ARTIFACT_ROOT::toString);
    }

    @Autowired
    private FixedCodingWorkflowUseCase workflowUseCase;

    @Autowired
    private CatalogStore catalogStore;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private ExecutionStore executionStore;

    @Autowired
    private AgentRuntime agentRuntime;

    @Autowired
    private RuntimeSupervisorSweep runtimeSupervisorSweep;

    @Autowired
    private WorkflowEvalCenter workflowEvalCenter;

    @Autowired
    private WorkflowEvalTraceCollector workflowEvalTraceCollector;

    @BeforeEach
    void resetEnvironment() throws Exception {
        TestGitRepoHelper.deleteRecursively(RUN_ROOT);
        Files.createDirectories(RUN_ROOT);
        TestGitRepoHelper.resetFixtureRepository(REPO_ROOT);
        TestGitRepoHelper.cleanDirectory(WORKSPACE_ROOT);
        Files.createDirectories(ARTIFACT_ROOT);
        Files.createDirectories(EXPORT_ROOT);
        Files.createDirectories(REVIEW_BUNDLE_ROOT);
        Files.createDirectories(EVAL_ARTIFACT_ROOT);
        try (Connection connection = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(connection, sanitizedSchemaScript());
        }
    }

    @Test
    void shouldRunStrictRealWorkflowEvalAndAlwaysEmitReport() throws Exception {
        boolean smokeEnabled = Boolean.parseBoolean(System.getProperty("agentx.llm.smoke", "false"))
                || Boolean.parseBoolean(System.getenv("AGENTX_LLM_SMOKE"));
        String apiKey = System.getenv("AGENTX_DEEPSEEK_API_KEY");
        Assumptions.assumeTrue(smokeEnabled);
        Assumptions.assumeTrue(apiKey != null && !apiKey.isBlank());

        String scenarioReference = System.getProperty("agentx.eval.scenario", "student-management-real-strict");
        RealWorkflowEvalScenarioPackLoader.LoadedScenarioPack loadedScenario =
                RealWorkflowEvalScenarioPackLoader.load(objectMapper, scenarioReference);
        RealWorkflowEvalProfileSeeds.apply(dataSource, loadedScenario.pack().profileId());
        RealWorkflowEvalFixtures.seedFixture(loadedScenario.pack().repoFixtureId(), REPO_ROOT);

        RealWorkflowEvalRunner runner = new RealWorkflowEvalRunner(
                workflowUseCase,
                catalogStore,
                executionStore,
                agentRuntime,
                runtimeSupervisorSweep,
                workflowEvalCenter,
                workflowEvalTraceCollector,
                objectMapper,
                REPO_ROOT,
                ARTIFACT_ROOT,
                EXPORT_ROOT,
                REVIEW_BUNDLE_ROOT
        );
        RealWorkflowEvalRunner.RealWorkflowEvalRunResult result = runner.runStrict(loadedScenario);

        System.out.println("Strict real workflow result: " + result.workflowResultPath());
        System.out.println("Strict real workflow report: " + result.reportArtifacts().markdownReportPath());

        assertThat(result.workflowResultPath()).exists();
        assertThat(result.reportArtifacts().rawEvidencePath()).exists();
        assertThat(result.reportArtifacts().scorecardPath()).exists();
        assertThat(result.reportArtifacts().markdownReportPath()).exists();
        assertThat(result.reportArtifacts().profileSnapshotPath()).exists();
    }

    private static ByteArrayResource sanitizedSchemaScript() {
        try {
            String schema = Files.readString(Path.of("db/schema/agentx_platform_v1.sql"), StandardCharsets.UTF_8);
            String sanitized = schema
                    .replaceFirst("(?is)create database if not exists agentx_platform\\s+character set utf8mb4\\s+collate utf8mb4_0900_ai_ci;\\s*", "")
                    .replaceFirst("(?im)^use agentx_platform;\\s*", "");
            return new ByteArrayResource(sanitized.getBytes(StandardCharsets.UTF_8));
        } catch (IOException exception) {
            throw new IllegalStateException("failed to read schema script", exception);
        }
    }
}
