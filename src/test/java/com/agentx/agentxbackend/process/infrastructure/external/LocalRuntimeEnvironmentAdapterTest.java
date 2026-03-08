package com.agentx.agentxbackend.process.infrastructure.external;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;

import java.lang.reflect.Method;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LocalRuntimeEnvironmentAdapterTest {

    @TempDir
    Path tempDir;

    @Test
    void resolveDockerImageForJava17ShouldReuseJava21RuntimeImage() throws Exception {
        LocalRuntimeEnvironmentAdapter adapter = new LocalRuntimeEnvironmentAdapter(
            new ObjectMapper(),
            true,
            "docker",
            tempDir.toString(),
            "python",
            1_000L,
            "docker",
            "never",
            "alpine:3.20",
            "eclipse-temurin:21-jdk",
            "maven:3.9.11-eclipse-temurin-21",
            "alpine/git:2.47.2",
            "python:3.11-slim",
            false,
            "ax",
            20,
            "mysql",
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

        Method method = LocalRuntimeEnvironmentAdapter.class.getDeclaredMethod(
            "resolveDockerImageForToolpack",
            String.class
        );
        method.setAccessible(true);

        assertEquals("eclipse-temurin:21-jdk", method.invoke(adapter, "TP-JAVA-17"));
        assertEquals("eclipse-temurin:21-jdk", method.invoke(adapter, "TP-JAVA-21"));
    }
}
