package com.agentx.agentxbackend.process.infrastructure.external;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocalRuntimeEnvironmentAdapterDatabaseAccountTest {

    @TempDir
    Path tempDir;

    @Test
    void ensureReadyShouldProvisionDatabaseAccountByCommandTemplate() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        LocalRuntimeEnvironmentAdapter adapter = new LocalRuntimeEnvironmentAdapter(
            objectMapper,
            true,
            "local",
            tempDir.toString(),
            "python",
            30_000L,
            "docker",
            "if-not-present",
            "alpine:3.20",
            "eclipse-temurin:21-jdk",
            "maven:3.9.11-eclipse-temurin-21",
            "alpine/git:2.47.2",
            "python:3.11-slim",
            true,
            "ax",
            16,
            "postgresql,mongodb",
            "{\"postgresql\":\"echo db-account-ready\"}",
            "127.0.0.1",
            3306,
            "agentx_backend",
            "root",
            "root",
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

        adapter.ensureReady("SES-DB-1", "WRK-DB-1", List.of("TP-POSTGRES-15"));

        Path envManifest = findManifest("environment.json");
        JsonNode root = objectMapper.readTree(Files.readString(envManifest, StandardCharsets.UTF_8));
        JsonNode accountNode = root.path("database_accounts").path("postgresql");
        assertEquals("READY", accountNode.path("status").asText());
        assertTrue(accountNode.path("username").asText().startsWith("ax_"));
    }

    @Test
    void ensureReadyShouldUseBuiltinMongoAdapterWhenTemplateMissing() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        LocalRuntimeEnvironmentAdapter adapter = new LocalRuntimeEnvironmentAdapter(
            objectMapper,
            true,
            "local",
            tempDir.toString(),
            "python",
            30_000L,
            "docker",
            "if-not-present",
            "alpine:3.20",
            "eclipse-temurin:21-jdk",
            "maven:3.9.11-eclipse-temurin-21",
            "alpine/git:2.47.2",
            "python:3.11-slim",
            true,
            "ax",
            16,
            "postgresql,mongodb",
            "{}",
            "127.0.0.1",
            3306,
            "agentx_backend",
            "root",
            "root",
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
            1,
            "agentx_backend",
            "admin",
            "root",
            "root"
        );

        adapter.ensureReady("SES-DB-2", "WRK-DB-2", List.of("TP-MONGO-7"));

        Path envManifest = findManifest("environment.json");
        JsonNode root = objectMapper.readTree(Files.readString(envManifest, StandardCharsets.UTF_8));
        JsonNode accountNode = root.path("database_accounts").path("mongodb");
        assertEquals("FAILED", accountNode.path("status").asText());
        assertEquals("builtin-mongodb", accountNode.path("provisioned_by").asText());
    }

    @Test
    void ensureReadyShouldUseBuiltinPostgresqlAdapterWhenTemplateMissing() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        LocalRuntimeEnvironmentAdapter adapter = new LocalRuntimeEnvironmentAdapter(
            objectMapper,
            true,
            "local",
            tempDir.toString(),
            "python",
            30_000L,
            "docker",
            "if-not-present",
            "alpine:3.20",
            "eclipse-temurin:21-jdk",
            "maven:3.9.11-eclipse-temurin-21",
            "alpine/git:2.47.2",
            "python:3.11-slim",
            true,
            "ax",
            16,
            "postgresql,mongodb,redis",
            "{}",
            "127.0.0.1",
            3306,
            "agentx_backend",
            "root",
            "root",
            "127.0.0.1",
            1,
            "agentx_backend",
            "postgres",
            "postgres",
            "postgres",
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
            "root"
        );

        adapter.ensureReady("SES-DB-3", "WRK-DB-3", List.of("TP-POSTGRES-15"));

        Path envManifest = findManifest("environment.json");
        JsonNode root = objectMapper.readTree(Files.readString(envManifest, StandardCharsets.UTF_8));
        JsonNode accountNode = root.path("database_accounts").path("postgresql");
        assertEquals("FAILED", accountNode.path("status").asText());
        assertEquals("builtin-postgresql", accountNode.path("provisioned_by").asText());
    }

    @Test
    void ensureReadyShouldUseBuiltinRedisAdapterWhenTemplateMissing() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        LocalRuntimeEnvironmentAdapter adapter = new LocalRuntimeEnvironmentAdapter(
            objectMapper,
            true,
            "local",
            tempDir.toString(),
            "python",
            30_000L,
            "docker",
            "if-not-present",
            "alpine:3.20",
            "eclipse-temurin:21-jdk",
            "maven:3.9.11-eclipse-temurin-21",
            "alpine/git:2.47.2",
            "python:3.11-slim",
            true,
            "ax",
            16,
            "postgresql,mongodb,redis",
            "{}",
            "127.0.0.1",
            3306,
            "agentx_backend",
            "root",
            "root",
            "127.0.0.1",
            5432,
            "agentx_backend",
            "postgres",
            "postgres",
            "",
            "127.0.0.1",
            1,
            0,
            "",
            "redis-pass",
            "127.0.0.1",
            27017,
            "agentx_backend",
            "admin",
            "root",
            "root"
        );

        adapter.ensureReady("SES-DB-4", "WRK-DB-4", List.of("TP-REDIS-7"));

        Path envManifest = findManifest("environment.json");
        JsonNode root = objectMapper.readTree(Files.readString(envManifest, StandardCharsets.UTF_8));
        JsonNode accountNode = root.path("database_accounts").path("redis");
        assertEquals("FAILED", accountNode.path("status").asText());
        assertEquals("builtin-redis", accountNode.path("provisioned_by").asText());
    }

    private Path findManifest(String fileName) throws Exception {
        try (var paths = Files.walk(tempDir)) {
            return paths
                .filter(path -> path.getFileName().toString().equals(fileName))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(fileName + " not found"));
        }
    }
}
