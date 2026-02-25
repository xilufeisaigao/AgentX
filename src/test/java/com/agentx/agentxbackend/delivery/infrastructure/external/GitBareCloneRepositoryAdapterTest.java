package com.agentx.agentxbackend.delivery.infrastructure.external;

import com.agentx.agentxbackend.delivery.domain.model.DeliveryClonePublication;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class GitBareCloneRepositoryAdapterTest {

    @TempDir
    Path tempDir;

    @Test
    void publishShouldCreateCloneableRepository() throws Exception {
        assumeTrue(canRunGit(), "git executable is required");
        Path sourceRepo = tempDir.resolve("repo");
        Files.createDirectories(sourceRepo);
        runGit(sourceRepo, List.of("init"));
        runGit(sourceRepo, List.of("config", "user.email", "agentx@test.local"));
        runGit(sourceRepo, List.of("config", "user.name", "AgentX Test"));
        Files.writeString(sourceRepo.resolve("README.md"), "# demo", StandardCharsets.UTF_8);
        runGit(sourceRepo, List.of("add", "README.md"));
        runGit(sourceRepo, List.of("commit", "-m", "init"));

        Path remotesRoot = tempDir.resolve("remotes");
        GitBareCloneRepositoryAdapter adapter = new GitBareCloneRepositoryAdapter(
            "git",
            sourceRepo.toString(),
            remotesRoot.toString(),
            "",
            "agentx-session-",
            "main",
            120000,
            72
        );

        DeliveryClonePublication publication = adapter.publish("SES-1");
        assertTrue(publication.cloneUrl().startsWith("file:"));
        assertTrue(Files.exists(remotesRoot.resolve("agentx-session-SES-1.git")));

        Optional<DeliveryClonePublication> active = adapter.findActive("SES-1");
        assertTrue(active.isPresent());
        assertEquals(publication.cloneUrl(), active.get().cloneUrl());

        Path metadataFile = remotesRoot.resolve(".metadata").resolve("SES-1.properties");
        Properties properties = new Properties();
        try (java.io.InputStream inputStream = Files.newInputStream(metadataFile)) {
            properties.load(inputStream);
        }
        properties.setProperty("expires_at_epoch_ms", String.valueOf(Instant.now().minusSeconds(3600).toEpochMilli()));
        try (java.io.OutputStream outputStream = Files.newOutputStream(metadataFile)) {
            properties.store(outputStream, "expire for cleanup");
        }

        int deleted = adapter.cleanupExpired();
        assertTrue(deleted >= 1);
        assertFalse(Files.exists(remotesRoot.resolve("agentx-session-SES-1.git")));
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

    private static String runGit(Path workingDir, List<String> args) throws Exception {
        ProcessBuilder builder = new ProcessBuilder();
        java.util.ArrayList<String> command = new java.util.ArrayList<>();
        command.add("git");
        command.addAll(args);
        builder.command(command);
        builder.directory(workingDir.toFile());
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

}
