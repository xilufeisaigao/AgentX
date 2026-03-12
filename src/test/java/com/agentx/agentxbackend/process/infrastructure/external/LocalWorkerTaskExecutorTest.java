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
import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.util.List;
import java.util.Optional;
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
            "sessions",
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
    void executeImplShouldResolveSessionScopedWorktreePath() throws Exception {
        assumeTrue(canRunGit(), "git executable is required");
        Path repoRoot = tempDir.resolve("repo-root");
        Path worktree = repoRoot.resolve("sessions/ses-alpha/repo/worktrees/SES-ALPHA/RUN-IMPL-SESSION-1");
        initGitRepo(worktree);
        LocalWorkerTaskExecutor executor = new LocalWorkerTaskExecutor(
            buildRuntimeConfigUseCase("mock", ""),
            new ObjectMapper(),
            "git",
            repoRoot.toString(),
            "sessions",
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
            buildImplPackage("RUN-IMPL-SESSION-1", "TASK-IMPL-SESSION-1", "worktrees/SES-ALPHA/RUN-IMPL-SESSION-1")
        );

        assertEquals(WorkerTaskExecutorPort.ExecutionStatus.SUCCEEDED, result.status());
        assertNotNull(result.deliveryCommit());
        assertFalse(result.deliveryCommit().isBlank());
        assertTrue(Files.exists(worktree.resolve("AGENTX_AUTOGEN_NOTE.md")));
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
            "sessions",
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
    void executeVerifyShouldIgnoreSpringBootTargetOutputsWhenCheckingReadOnly() throws Exception {
        assumeTrue(canRunGit(), "git executable is required");
        assumeTrue(canRunMaven(), "mvn executable is required");
        Path repo = initMinimalSpringBootMavenRepo(tempDir.resolve("repo-verify-spring-boot"));
        LocalWorkerTaskExecutor executor = new LocalWorkerTaskExecutor(
            buildRuntimeConfigUseCase("mock", ""),
            new ObjectMapper(),
            "git",
            repo.toString(),
            "sessions",
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
            buildVerifyPackage(
                "RUN-VERIFY-MVN-1",
                "TASK-VERIFY-MVN-1",
                ".",
                List.of("mvn -q test", "mvn -q -Dtest=* verify")
            )
        );

        assertEquals(WorkerTaskExecutorPort.ExecutionStatus.SUCCEEDED, result.status());
        String statusOutput = runGit(repo, List.of("status", "--porcelain")).trim();
        assertTrue(statusOutput.contains("?? target/"));
    }

    @Test
    void executeVerifyShouldDrainLargeStdoutWithoutTimingOut() throws Exception {
        assumeTrue(canRunGit(), "git executable is required");
        Path repo = initGitRepo(tempDir.resolve("repo-verify-large-stdout"));
        Files.writeString(repo.resolve("BIG_OUTPUT.txt"), "x".repeat(200_000), StandardCharsets.UTF_8);
        runGit(repo, List.of("add", "BIG_OUTPUT.txt"));
        runGit(repo, List.of("commit", "-m", "add large diff payload"));
        LocalWorkerTaskExecutor executor = new LocalWorkerTaskExecutor(
            buildRuntimeConfigUseCase("mock", ""),
            new ObjectMapper(),
            "git",
            repo.toString(),
            "sessions",
            5000,
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
            buildVerifyPackage(
                "RUN-VERIFY-STDOUT-1",
                "TASK-VERIFY-STDOUT-1",
                ".",
                List.of("git show HEAD --stat --patch")
            )
        );

        assertEquals(WorkerTaskExecutorPort.ExecutionStatus.SUCCEEDED, result.status());
    }

    @Test
    void executeVerifyShouldFailWhenUnexpectedDirtyFileRemains() throws Exception {
        assumeTrue(canRunGit(), "git executable is required");
        Path repo = initGitRepo(tempDir.resolve("repo-verify-dirty"));
        Files.writeString(repo.resolve("verify-notes.txt"), "dirty", StandardCharsets.UTF_8);
        LocalWorkerTaskExecutor executor = new LocalWorkerTaskExecutor(
            buildRuntimeConfigUseCase("mock", ""),
            new ObjectMapper(),
            "git",
            repo.toString(),
            "sessions",
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
            buildVerifyPackage("RUN-VERIFY-DIRTY-1", "TASK-VERIFY-DIRTY-1", ".")
        );

        assertEquals(WorkerTaskExecutorPort.ExecutionStatus.FAILED, result.status());
        assertTrue(result.failureReason().contains("verify-notes.txt"));
    }

    @Test
    void tryCompleteAlreadySatisfiedTaskShouldReuseCurrentHeadForPassingTestTask() throws Exception {
        assumeTrue(canRunGit(), "git executable is required");
        assumeTrue(canRunMaven(), "mvn executable is required");
        Path repo = initMinimalSpringBootMavenRepo(tempDir.resolve("repo-existing-tests"));
        LocalWorkerTaskExecutor executor = new LocalWorkerTaskExecutor(
            buildRuntimeConfigUseCase("mock", ""),
            new ObjectMapper(),
            "git",
            repo.toString(),
            "sessions",
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
        Method method = LocalWorkerTaskExecutor.class.getDeclaredMethod(
            "tryCompleteAlreadySatisfiedTask",
            TaskPackage.class,
            Path.class,
            String.class
        );
        method.setAccessible(true);

        @SuppressWarnings("unchecked")
        Optional<WorkerTaskExecutorPort.ExecutionResult> result =
            (Optional<WorkerTaskExecutorPort.ExecutionResult>) method.invoke(
                executor,
                buildTestImplPackage("RUN-TEST-ALREADY-SATISFIED-1", "TASK-TEST-ALREADY-SATISFIED-1", "."),
                repo,
                "zh-CN"
            );

        assertTrue(result.isPresent());
        assertEquals(WorkerTaskExecutorPort.ExecutionStatus.SUCCEEDED, result.get().status());
        assertEquals(runGit(repo, List.of("rev-parse", "HEAD")).trim(), result.get().deliveryCommit());
        assertTrue(result.get().workReport().contains("现有测试"));
    }

    @Test
    void buildWorkspaceSnapshotShouldExposeExistingProjectStructure() throws Exception {
        Path repo = initMinimalMavenRepo(tempDir.resolve("repo-workspace-snapshot"));

        String snapshot = LocalWorkerTaskExecutor.buildWorkspaceSnapshot(
            repo,
            List.of("./"),
            List.of("src/main/java/", "pom.xml")
        );

        assertTrue(snapshot.contains("pom.xml"));
        assertTrue(snapshot.contains("src/main/java/com/example/VerifyMavenApplication.java"));
        assertTrue(snapshot.contains("<artifactId>verify-maven</artifactId>"));
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
            "sessions",
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
            "sessions",
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
            "sessions",
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

    @Test
    void buildSystemPromptShouldForceInitTasksToHonorExplicitFrameworkRequirements() throws Exception {
        Method method = LocalWorkerTaskExecutor.class.getDeclaredMethod("buildSystemPrompt", String.class, boolean.class);
        method.setAccessible(true);

        String prompt = (String) method.invoke(null, "zh-CN", false);

        assertTrue(prompt.contains("Never return SUCCEEDED with an empty edits array"));
        assertTrue(prompt.contains("bootstrap baseline MUST honor any explicit framework"));
        assertTrue(prompt.contains("do not ask permission to adopt it"));
        assertTrue(prompt.contains("MockMvc"));
        assertTrue(prompt.contains("/api/greeting"));
        assertTrue(prompt.contains("content().contentTypeCompatibleWith(MediaType.TEXT_PLAIN)"));
        assertTrue(prompt.contains("@RequestParam(\"name\")"));
        assertTrue(prompt.contains("张三"));
    }

    @Test
    void trimForReportShouldPreserveTailFailureContext() throws Exception {
        Method method = LocalWorkerTaskExecutor.class.getDeclaredMethod("trimForReport", String.class);
        method.setAccessible(true);
        String longOutput = "start-" + "a".repeat(3000) + "END-FAILURE-CONTEXT";

        String trimmed = (String) method.invoke(null, longOutput);

        assertTrue(trimmed.contains("start-"));
        assertTrue(trimmed.contains("END-FAILURE-CONTEXT"));
        assertTrue(trimmed.contains("output truncated"));
    }

    @Test
    void parsePlannerResultShouldAcceptAlternateEditFieldNames() throws Exception {
        LocalWorkerTaskExecutor executor = new LocalWorkerTaskExecutor(
            buildRuntimeConfigUseCase("mock", ""),
            new ObjectMapper(),
            "git",
            tempDir.toString(),
            "sessions",
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
        Method parseMethod = LocalWorkerTaskExecutor.class.getDeclaredMethod("parsePlannerResult", String.class);
        parseMethod.setAccessible(true);

        Object plannerResult = parseMethod.invoke(
            executor,
            """
            {
              "status": "SUCCEEDED",
              "summary": "apply scaffold correction",
              "files": [
                {
                  "file": "pom.xml",
                  "full_content": "<project/>"
                },
                {
                  "target_path": "src/main/java/com/example/helloapi/HelloApiDemoApplication.java",
                  "new_content": "class HelloApiDemoApplication {}"
                }
              ]
            }
            """
        );

        assertEquals("SUCCEEDED", invokeRecordAccessor(plannerResult, "outcome"));
        assertEquals("apply scaffold correction", invokeRecordAccessor(plannerResult, "message"));
        List<?> edits = (List<?>) invokeRecordAccessor(plannerResult, "edits");
        assertEquals(2, edits.size());
        assertEquals("pom.xml", invokeRecordAccessor(edits.get(0), "path"));
        assertEquals("<project/>", invokeRecordAccessor(edits.get(0), "content"));
        assertEquals(
            "src/main/java/com/example/helloapi/HelloApiDemoApplication.java",
            invokeRecordAccessor(edits.get(1), "path")
        );
    }

    @Test
    void parsePlannerResultShouldAcceptPathToContentMap() throws Exception {
        LocalWorkerTaskExecutor executor = new LocalWorkerTaskExecutor(
            buildRuntimeConfigUseCase("mock", ""),
            new ObjectMapper(),
            "git",
            tempDir.toString(),
            "sessions",
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
        Method parseMethod = LocalWorkerTaskExecutor.class.getDeclaredMethod("parsePlannerResult", String.class);
        parseMethod.setAccessible(true);

        Object plannerResult = parseMethod.invoke(
            executor,
            """
            {
              "result": "SUCCEEDED",
              "analysis": "rewrite scaffold",
              "changes": {
                "pom.xml": "<project/>",
                "src/main/resources/application.properties": "server.port=8080"
              }
            }
            """
        );

        List<?> edits = (List<?>) invokeRecordAccessor(plannerResult, "edits");
        assertEquals(2, edits.size());
        assertEquals("rewrite scaffold", invokeRecordAccessor(plannerResult, "message"));
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

    private static TaskPackage buildTestImplPackage(String runId, String taskId, String worktreePath) {
        return new TaskPackage(
            runId,
            taskId,
            "Implement test task for " + taskId,
            "MOD-1",
            "CTXS-1",
            RunKind.IMPL,
            "tmpl.test.v0",
            List.of("TP-JAVA-21", "TP-MAVEN-3", "TP-GIT-2"),
            null,
            null,
            new TaskContext("task:" + taskId, List.of(), List.of(), "git:BASE"),
            List.of("./"),
            List.of("src/test/java/", "src/test/resources/", "pom.xml"),
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

    private static boolean canRunMaven() {
        try {
            Process process = new ProcessBuilder("mvn", "-v").start();
            boolean finished = process.waitFor(10, TimeUnit.SECONDS);
            return finished && process.exitValue() == 0;
        } catch (Exception ex) {
            return false;
        }
    }

    private static Path initMinimalMavenRepo(Path repo) throws Exception {
        initGitRepo(repo);
        Files.writeString(
            repo.resolve("pom.xml"),
            """
                <project xmlns="http://maven.apache.org/POM/4.0.0"
                         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.example</groupId>
                    <artifactId>verify-maven</artifactId>
                    <version>1.0.0</version>
                    <properties>
                        <maven.compiler.source>17</maven.compiler.source>
                        <maven.compiler.target>17</maven.compiler.target>
                        <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
                    </properties>
                </project>
                """,
            StandardCharsets.UTF_8
        );
        Path mainClass = repo.resolve("src/main/java/com/example/VerifyMavenApplication.java");
        Files.createDirectories(mainClass.getParent());
        Files.writeString(
            mainClass,
            """
                package com.example;

                public class VerifyMavenApplication {
                    public static void main(String[] args) {
                        System.out.println("ok");
                    }
                }
                """,
            StandardCharsets.UTF_8
        );
        runGit(repo, List.of("add", "-A"));
        runGit(repo, List.of("commit", "-m", "add minimal maven project"));
        return repo;
    }

    private static Object invokeRecordAccessor(Object record, String componentName) throws Exception {
        for (RecordComponent component : record.getClass().getRecordComponents()) {
            if (component.getName().equals(componentName)) {
                Method accessor = component.getAccessor();
                accessor.setAccessible(true);
                return accessor.invoke(record);
            }
        }
        throw new IllegalArgumentException("Record component not found: " + componentName);
    }

    private static Path initMinimalSpringBootMavenRepo(Path repo) throws Exception {
        initGitRepo(repo);
        Files.writeString(
            repo.resolve("pom.xml"),
            """
                <project xmlns="http://maven.apache.org/POM/4.0.0"
                         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
                    <modelVersion>4.0.0</modelVersion>
                    <parent>
                        <groupId>org.springframework.boot</groupId>
                        <artifactId>spring-boot-starter-parent</artifactId>
                        <version>3.2.0</version>
                        <relativePath/>
                    </parent>
                    <groupId>com.example</groupId>
                    <artifactId>verify-spring-boot</artifactId>
                    <version>0.0.1-SNAPSHOT</version>
                    <properties>
                        <java.version>17</java.version>
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
                """,
            StandardCharsets.UTF_8
        );
        Path mainClass = repo.resolve("src/main/java/com/example/VerifySpringBootApplication.java");
        Files.createDirectories(mainClass.getParent());
        Files.writeString(
            mainClass,
            """
                package com.example;

                import org.springframework.boot.SpringApplication;
                import org.springframework.boot.autoconfigure.SpringBootApplication;

                @SpringBootApplication
                public class VerifySpringBootApplication {
                    public static void main(String[] args) {
                        SpringApplication.run(VerifySpringBootApplication.class, args);
                    }
                }
                """,
            StandardCharsets.UTF_8
        );
        Path testClass = repo.resolve("src/test/java/com/example/VerifySpringBootApplicationTests.java");
        Files.createDirectories(testClass.getParent());
        Files.writeString(
            testClass,
            """
                package com.example;

                import org.junit.jupiter.api.Test;
                import org.springframework.boot.test.context.SpringBootTest;

                @SpringBootTest
                class VerifySpringBootApplicationTests {

                    @Test
                    void contextLoads() {
                    }
                }
                """,
            StandardCharsets.UTF_8
        );
        runGit(repo, List.of("add", "-A"));
        runGit(repo, List.of("commit", "-m", "add minimal spring boot maven project"));
        return repo;
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

