package com.agentx.agentxbackend.process.infrastructure.external;

import com.agentx.agentxbackend.execution.domain.model.GitAlloc;
import com.agentx.agentxbackend.execution.domain.model.RunKind;
import com.agentx.agentxbackend.execution.domain.model.TaskContext;
import com.agentx.agentxbackend.execution.domain.model.TaskPackage;
import com.agentx.agentxbackend.process.application.port.in.RuntimeLlmConfigUseCase;
import com.agentx.agentxbackend.process.application.port.out.WorkerTaskExecutorPort;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class WorkerRuntimeJavaBackendSmokeTest {

    @Test
    void shouldGenerateCompilableStudentManagementBackendWhenRealLlmEnabled() throws Exception {
        Path tempDir = Files.createTempDirectory("agentx-runtime-smoke-");
        assumeTrue(
            "true".equalsIgnoreCase(System.getenv("AGENTX_REAL_LLM_TEST")),
            "Set AGENTX_REAL_LLM_TEST=true to run real LLM codegen smoke test"
        );
        String apiKey = env("AGENTX_WORKER_RUNTIME_LLM_API_KEY", env("AGENTX_REQUIREMENT_LLM_API_KEY", ""));
        String baseUrl = env("AGENTX_WORKER_RUNTIME_LLM_BASE_URL", env("AGENTX_REQUIREMENT_LLM_BASE_URL", ""));
        String model = env("AGENTX_WORKER_RUNTIME_LLM_MODEL", env("AGENTX_REQUIREMENT_LLM_MODEL", "qwen3.5-plus-2026-02-15"));
        String gitExecutable = resolveExecutable(tempDir, List.of("git", "git.exe"), List.of("--version"));
        String mavenExecutable = resolveExecutable(tempDir, List.of("mvn", "mvn.cmd"), List.of("-v"));
        assumeTrue(apiKey != null && !apiKey.isBlank(), "worker/runtime LLM api key is required");
        assumeTrue(baseUrl != null && !baseUrl.isBlank(), "worker/runtime LLM base url is required");
        assumeTrue(gitExecutable != null, "git executable is required");
        assumeTrue(mavenExecutable != null, "maven executable is required");

        Path repo = initSpringBootSkeleton(tempDir.resolve("student-backend"), gitExecutable);
        Path taskSkill = writeTaskSkill(repo);

        LocalWorkerTaskExecutor executor = new LocalWorkerTaskExecutor(
            buildRuntimeConfigUseCase("bailian", baseUrl, model, apiKey, 300_000),
            new ObjectMapper(),
            gitExecutable,
            repo.toString(),
            "sessions",
            300_000,
            30,
            "mvn,./mvnw,gradle,./gradlew,python,pytest,git",
            false,
            "docker",
            "maven:3.9.11-eclipse-temurin-21",
            "1g",
            "1.0",
            256
        );

        TaskPackage taskPackage = new TaskPackage(
            "RUN-REAL-JAVA-1",
            "TASK-REAL-JAVA-1",
            "Build student management backend",
            "MOD-STUDENT",
            "CTXS-REAL-JAVA-1",
            RunKind.IMPL,
            "tmpl.impl.v0",
            List.of("TP-JAVA-21", "TP-MAVEN-3", "TP-GIT-2"),
            "file:" + taskSkill.toString(),
            null,
            new TaskContext(
                "task:TASK-REAL-JAVA-1",
                List.of(),
                List.of(),
                "git:BASE"
            ),
            List.of("./"),
            List.of("src/main/java/", "src/test/java/", "src/main/resources/", "pom.xml"),
            List.of(),
            List.of("Missing facts must trigger NEED_CLARIFICATION."),
            List.of("work_report", "delivery_commit"),
            new GitAlloc("BASE", "run/RUN-REAL-JAVA-1", ".")
        );

        WorkerTaskExecutorPort.ExecutionResult result = executor.execute(taskPackage);

        assertEquals(WorkerTaskExecutorPort.ExecutionStatus.SUCCEEDED, result.status());
        assertNotNull(result.deliveryCommit());
        assertFalse(result.deliveryCommit().isBlank());
        assertTrue(
            anyJavaFileContains(repo.resolve("src/main/java"), "@RestController"),
            "Expected generated REST controller in src/main/java"
        );
        assertTrue(
            anyJavaFileContains(repo.resolve("src/test/java"), "MockMvc"),
            "Expected generated MockMvc test in src/test/java"
        );

        ProcessResult verifyBuild = runCommand(
            repo,
            List.of(mavenExecutable, "-q", "-DskipTests", "compile"),
            600_000
        );
        assertEquals(0, verifyBuild.exitCode());
    }

    private static Path initSpringBootSkeleton(Path repo, String gitExecutable) throws Exception {
        Files.createDirectories(repo);
        runCommand(repo, List.of(gitExecutable, "init"), 30_000);
        runCommand(repo, List.of(gitExecutable, "config", "user.email", "agentx@test.local"), 30_000);
        runCommand(repo, List.of(gitExecutable, "config", "user.name", "AgentX Runtime"), 30_000);

        Files.writeString(repo.resolve("pom.xml"), """
            <project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                     xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
              <modelVersion>4.0.0</modelVersion>
              <parent>
                <groupId>org.springframework.boot</groupId>
                <artifactId>spring-boot-starter-parent</artifactId>
                <version>3.4.2</version>
                <relativePath/>
              </parent>
              <groupId>com.example</groupId>
              <artifactId>student-backend</artifactId>
              <version>0.0.1-SNAPSHOT</version>
              <name>student-backend</name>
              <description>LLM generated student backend smoke test</description>
              <properties>
                <java.version>21</java.version>
              </properties>
              <dependencies>
                <dependency>
                  <groupId>org.springframework.boot</groupId>
                  <artifactId>spring-boot-starter-web</artifactId>
                </dependency>
                <dependency>
                  <groupId>org.springframework.boot</groupId>
                  <artifactId>spring-boot-starter-test</artifactId>
                  <scope>test</scope>
                </dependency>
              </dependencies>
              <build>
                <plugins>
                  <plugin>
                    <groupId>org.springframework.boot</groupId>
                    <artifactId>spring-boot-maven-plugin</artifactId>
                  </plugin>
                </plugins>
              </build>
            </project>
            """, StandardCharsets.UTF_8);
        Path appDir = repo.resolve("src/main/java/com/example/student");
        Files.createDirectories(appDir);
        Files.writeString(appDir.resolve("StudentBackendApplication.java"), """
            package com.example.student;

            import org.springframework.boot.SpringApplication;
            import org.springframework.boot.autoconfigure.SpringBootApplication;

            @SpringBootApplication
            public class StudentBackendApplication {
                public static void main(String[] args) {
                    SpringApplication.run(StudentBackendApplication.class, args);
                }
            }
            """, StandardCharsets.UTF_8);
        Path testDir = repo.resolve("src/test/java/com/example/student");
        Files.createDirectories(testDir);
        Files.writeString(testDir.resolve("StudentBackendApplicationTests.java"), """
            package com.example.student;

            import org.junit.jupiter.api.Test;
            import org.springframework.boot.test.context.SpringBootTest;

            @SpringBootTest
            class StudentBackendApplicationTests {

                @Test
                void contextLoads() {
                }
            }
            """, StandardCharsets.UTF_8);

        runCommand(repo, List.of(gitExecutable, "add", "-A"), 30_000);
        runCommand(repo, List.of(gitExecutable, "commit", "-m", "init spring boot skeleton"), 30_000);
        return repo;
    }

    private static Path writeTaskSkill(Path repo) throws Exception {
        Path taskSkill = repo.resolve(".agentx/context/task-skills/student-management.md");
        Files.createDirectories(taskSkill.getParent());
        Files.writeString(taskSkill, """
            # Task Skill

            ## Goal
            Build a minimal but production-style Spring Boot student management backend.

            ## Functional Requirements
            - Implement Student CRUD REST API under `/api/students`.
            - Fields: id (Long), name (String), age (Integer), email (String).
            - Use in-memory storage (ConcurrentHashMap) to keep the smoke test deterministic.
            - Validate required fields and return proper HTTP status codes.
            - Add unit tests for controller endpoints using MockMvc.

            ## Non-functional Requirements
            - Java 21 + Spring Boot 3 style.
            - Keep package as `com.example.student`.
            - Keep code clean and compilable with Maven.

            ## Recommended Commands
            - mvn -q -Dtest=StudentControllerTest test
            """, StandardCharsets.UTF_8);
        return taskSkill;
    }

    private static ProcessResult runCommand(Path workDir, List<String> command, int timeoutMs) throws Exception {
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.directory(workDir.toFile());
        builder.redirectErrorStream(true);
        Process process = builder.start();
        boolean finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS);
        if (!finished) {
            process.destroyForcibly();
            throw new IllegalStateException("Command timed out: " + String.join(" ", command));
        }
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int exitCode = process.exitValue();
        if (exitCode != 0) {
            throw new IllegalStateException(
                "Command failed (exit " + exitCode + "): " + String.join(" ", command) + ", output=" + output
            );
        }
        return new ProcessResult(exitCode, output);
    }

    private static boolean canRunCommand(List<String> command, Path workDir) {
        try {
            runCommand(workDir, command, 10_000);
            return true;
        } catch (Exception ex) {
            return false;
        }
    }

    private static String resolveExecutable(Path workDir, List<String> candidates, List<String> args) {
        for (String candidate : candidates) {
            if (candidate == null || candidate.isBlank()) {
                continue;
            }
            List<String> command = new java.util.ArrayList<>();
            command.add(candidate);
            command.addAll(args);
            if (canRunCommand(command, workDir)) {
                return candidate;
            }
        }
        return null;
    }

    private static String env(String key, String fallback) {
        String value = System.getenv(key);
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return value.trim();
    }

    private static boolean anyJavaFileContains(Path root, String marker) throws Exception {
        if (root == null || marker == null || marker.isBlank() || !Files.exists(root)) {
            return false;
        }
        try (Stream<Path> paths = Files.walk(root)) {
            for (Path path : paths.filter(Files::isRegularFile).toList()) {
                if (!path.toString().endsWith(".java")) {
                    continue;
                }
                String content = Files.readString(path, StandardCharsets.UTF_8);
                if (content.contains(marker)) {
                    return true;
                }
            }
            return false;
        }
    }

    private record ProcessResult(int exitCode, String output) {
    }

    private static RuntimeLlmConfigUseCase buildRuntimeConfigUseCase(
        String provider,
        String baseUrl,
        String model,
        String apiKey,
        long timeoutMs
    ) {
        RuntimeLlmConfigUseCase.LlmProfile requirement = new RuntimeLlmConfigUseCase.LlmProfile(
            provider,
            "langchain4j",
            baseUrl,
            model,
            apiKey,
            timeoutMs
        );
        RuntimeLlmConfigUseCase.LlmProfile worker = new RuntimeLlmConfigUseCase.LlmProfile(
            provider,
            "langchain4j",
            baseUrl,
            model,
            apiKey,
            timeoutMs
        );
        RuntimeLlmConfigUseCase.RuntimeConfigView config = new RuntimeLlmConfigUseCase.RuntimeConfigView(
            "zh-CN",
            requirement,
            worker,
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

