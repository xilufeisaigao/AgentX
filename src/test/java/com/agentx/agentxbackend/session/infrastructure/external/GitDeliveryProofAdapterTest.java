package com.agentx.agentxbackend.session.infrastructure.external;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class GitDeliveryProofAdapterTest {

    @TempDir
    Path tempDir;

    @Test
    void hasAtLeastOneDeliveryTagOnMainShouldReturnTrueForAnnotatedDeliveryTag() throws Exception {
        assumeTrue(canRunGit(), "git executable is required");
        Path repoRoot = tempDir.resolve("repo-root");
        Path sessionRepo = repoRoot.resolve("sessions/SES-1/repo");
        Files.createDirectories(sessionRepo);

        runGit(sessionRepo, List.of("init"));
        runGit(sessionRepo, List.of("config", "user.email", "agentx@test.local"));
        runGit(sessionRepo, List.of("config", "user.name", "AgentX Test"));
        Files.writeString(sessionRepo.resolve("README.md"), "base\n", StandardCharsets.UTF_8);
        runGit(sessionRepo, List.of("add", "README.md"));
        runGit(sessionRepo, List.of("commit", "-m", "init"));
        runGit(sessionRepo, List.of("branch", "-M", "main"));
        String head = runGit(sessionRepo, List.of("rev-parse", "main")).trim();
        runGit(sessionRepo, List.of("tag", "-a", "delivery/20260225-1300", head, "-m", "delivery"));

        GitDeliveryProofAdapter adapter = new GitDeliveryProofAdapter(
            "git",
            repoRoot.toString(),
            "sessions",
            "main",
            30000
        );

        assertTrue(adapter.hasAtLeastOneDeliveryTagOnMain("SES-1"));
    }

    @Test
    void hasAtLeastOneDeliveryTagOnMainShouldReturnFalseForMissingTag() throws Exception {
        assumeTrue(canRunGit(), "git executable is required");
        Path repoRoot = tempDir.resolve("repo-root-missing");
        Path sessionRepo = repoRoot.resolve("sessions/SES-2/repo");
        Files.createDirectories(sessionRepo);

        runGit(sessionRepo, List.of("init"));
        runGit(sessionRepo, List.of("config", "user.email", "agentx@test.local"));
        runGit(sessionRepo, List.of("config", "user.name", "AgentX Test"));
        Files.writeString(sessionRepo.resolve("README.md"), "base\n", StandardCharsets.UTF_8);
        runGit(sessionRepo, List.of("add", "README.md"));
        runGit(sessionRepo, List.of("commit", "-m", "init"));
        runGit(sessionRepo, List.of("branch", "-M", "main"));

        GitDeliveryProofAdapter adapter = new GitDeliveryProofAdapter(
            "git",
            repoRoot.toString(),
            "sessions",
            "main",
            30000
        );

        assertFalse(adapter.hasAtLeastOneDeliveryTagOnMain("SES-2"));
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

    private static String runGit(Path repo, List<String> args) throws Exception {
        java.util.ArrayList<String> command = new java.util.ArrayList<>();
        command.add("git");
        command.addAll(args);
        ProcessBuilder builder = new ProcessBuilder(command);
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
}
