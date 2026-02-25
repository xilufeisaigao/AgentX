package com.agentx.agentxbackend.process.infrastructure.external;

import com.agentx.agentxbackend.process.application.port.out.RuntimeEnvironmentMaintenancePort;
import com.agentx.agentxbackend.process.application.port.out.RuntimeEnvironmentPort;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class LocalRuntimeEnvironmentAdapterTest {

    @TempDir
    Path tempDir;

    @Test
    void ensureReadyShouldCreatePythonVenvWhenPythonToolpackPresent() {
        assumeTrue(isCommandAvailable("python"), "python is required for this runtime environment test");

        LocalRuntimeEnvironmentAdapter adapter = new LocalRuntimeEnvironmentAdapter(
            new ObjectMapper(),
            true,
            "local",
            tempDir.resolve("runtime-env").toString(),
            "python",
            120000,
            "docker",
            "if-not-present",
            "alpine:3.20",
            "eclipse-temurin:21-jdk",
            "maven:3.9.11-eclipse-temurin-21",
            "alpine/git:2.47.2",
            "python:3.11-slim",
            false,
            "ax",
            20,
            "mysql,postgresql,redis,mongodb,sqlserver,oracle",
            "{}",
            "127.0.0.1",
            3306,
            "agentx_backend",
            "root",
            "",
            "127.0.0.1",
            5432,
            "agentx_backend",
            "postgres",
            "postgres",
            "",
            "127.0.0.1",
            6379,
            0,
            "",
            "",
            "127.0.0.1",
            27017,
            "agentx_backend",
            "admin",
            "root",
            ""
        );

        RuntimeEnvironmentPort.PreparedEnvironment prepared = adapter.ensureReady(
            "SES-1",
            "WRK-1",
            List.of("TP-PYTHON-3_11")
        );

        assertNotNull(prepared.projectEnvironmentPath());
        assertNotNull(prepared.pythonVenvPath());
        assertTrue(Files.exists(Path.of(prepared.projectEnvironmentPath()).resolve("environment.json")));
        assertTrue(Files.exists(resolvePythonBinary(Path.of(prepared.pythonVenvPath()))));
    }

    @Test
    void cleanupExpiredProjectEnvironmentsShouldDeleteOnlyExpiredOnes() throws Exception {
        LocalRuntimeEnvironmentAdapter adapter = new LocalRuntimeEnvironmentAdapter(
            new ObjectMapper(),
            true,
            "local",
            tempDir.resolve("runtime-env").toString(),
            "python",
            120000,
            "docker",
            "if-not-present",
            "alpine:3.20",
            "eclipse-temurin:21-jdk",
            "maven:3.9.11-eclipse-temurin-21",
            "alpine/git:2.47.2",
            "python:3.11-slim",
            false,
            "ax",
            20,
            "mysql,postgresql,redis,mongodb,sqlserver,oracle",
            "{}",
            "127.0.0.1",
            3306,
            "agentx_backend",
            "root",
            "",
            "127.0.0.1",
            5432,
            "agentx_backend",
            "postgres",
            "postgres",
            "",
            "127.0.0.1",
            6379,
            0,
            "",
            "",
            "127.0.0.1",
            27017,
            "agentx_backend",
            "admin",
            "root",
            ""
        );
        Path projectsRoot = tempDir.resolve("runtime-env").resolve("projects");
        Path expiredDir = projectsRoot.resolve("SES-1").resolve("old-env");
        Path activeDir = projectsRoot.resolve("SES-1").resolve("new-env");
        Files.createDirectories(expiredDir);
        Files.createDirectories(activeDir);
        Files.writeString(
            expiredDir.resolve("environment.json"),
            """
                {"prepared_at":"2025-01-01T00:00:00Z"}
                """.trim(),
            StandardCharsets.UTF_8
        );
        Files.writeString(
            activeDir.resolve("environment.json"),
            """
                {"prepared_at":"2099-01-01T00:00:00Z"}
                """.trim(),
            StandardCharsets.UTF_8
        );

        RuntimeEnvironmentMaintenancePort.CleanupResult result = adapter.cleanupExpiredProjectEnvironments(
            Duration.ofHours(24),
            10
        );

        assertEquals(2, result.scannedEnvironments());
        assertEquals(1, result.deletedEnvironments());
        assertEquals(0, result.failedEnvironments());
        assertFalse(Files.exists(expiredDir));
        assertTrue(Files.exists(activeDir));
    }

    @Test
    void ensureReadyShouldUseDockerModeWithoutCreatingHostPythonVenv() throws Exception {
        assumeTrue(isCommandAvailable("docker"), "docker is required for docker runtime environment test");
        String existingImage = firstLocalDockerImage();
        assumeTrue(existingImage != null && !existingImage.isBlank(), "no local docker image available for test");

        LocalRuntimeEnvironmentAdapter adapter = new LocalRuntimeEnvironmentAdapter(
            new ObjectMapper(),
            true,
            "docker",
            tempDir.resolve("runtime-env").toString(),
            "python",
            120000,
            "docker",
            "never",
            existingImage,
            existingImage,
            existingImage,
            existingImage,
            existingImage,
            false,
            "ax",
            20,
            "mysql,postgresql,redis,mongodb,sqlserver,oracle",
            "{}",
            "127.0.0.1",
            3306,
            "agentx_backend",
            "root",
            "",
            "127.0.0.1",
            5432,
            "agentx_backend",
            "postgres",
            "postgres",
            "",
            "127.0.0.1",
            6379,
            0,
            "",
            "",
            "127.0.0.1",
            27017,
            "agentx_backend",
            "admin",
            "root",
            ""
        );

        RuntimeEnvironmentPort.PreparedEnvironment prepared = adapter.ensureReady(
            "SES-DOCKER",
            "WRK-DOCKER",
            List.of("TP-JAVA-21", "TP-PYTHON-3_11")
        );

        assertNotNull(prepared.projectEnvironmentPath());
        assertNull(prepared.pythonVenvPath());
        Path manifest = Path.of(prepared.projectEnvironmentPath()).resolve("environment.json");
        assertTrue(Files.exists(manifest));

        JsonNode root = new ObjectMapper().readTree(Files.readString(manifest, StandardCharsets.UTF_8));
        assertEquals("docker", root.path("runtime_mode").asText(""));
        assertEquals(existingImage, root.path("docker_images").path("TP-JAVA-21").asText(""));
        assertEquals(existingImage, root.path("docker_images").path("TP-PYTHON-3_11").asText(""));
    }

    private static boolean isCommandAvailable(String command) {
        try {
            Process process = new ProcessBuilder(command, "--version")
                .redirectErrorStream(true)
                .start();
            int exitCode = process.waitFor();
            return exitCode == 0;
        } catch (Exception ex) {
            return false;
        }
    }

    private static String firstLocalDockerImage() {
        try {
            Process process = new ProcessBuilder(
                "docker",
                "images",
                "--format",
                "{{.Repository}}:{{.Tag}}"
            ).redirectErrorStream(true).start();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            int exitCode = process.waitFor();
            if (exitCode != 0 || output.isBlank()) {
                return null;
            }
            String first = output.lines()
                .map(String::trim)
                .filter(line -> !line.isBlank() && !line.endsWith(":<none>"))
                .findFirst()
                .orElse(null);
            if (first == null || first.isBlank()) {
                return null;
            }
            return first;
        } catch (Exception ex) {
            return null;
        }
    }

    private static Path resolvePythonBinary(Path venvPath) {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("win")) {
            return venvPath.resolve("Scripts").resolve("python.exe");
        }
        return venvPath.resolve("bin").resolve("python");
    }
}
