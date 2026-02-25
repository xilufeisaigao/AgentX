package com.agentx.agentxbackend.workspace.infrastructure.external;

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

class JGitClientAdapterTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldCreateAndRemoveWorktree() throws Exception {
        assumeTrue(canRunGit(), "git executable is required");
        Path repo = tempDir.resolve("repo");
        Files.createDirectories(repo);
        runGit(repo, List.of("init"));
        runGit(repo, List.of("config", "user.email", "agentx@test.local"));
        runGit(repo, List.of("config", "user.name", "AgentX Test"));
        Files.writeString(repo.resolve("README.md"), "# test", StandardCharsets.UTF_8);
        runGit(repo, List.of("add", "README.md"));
        runGit(repo, List.of("commit", "-m", "init"));
        String baseCommit = runGit(repo, List.of("rev-parse", "HEAD")).trim();

        JGitClientAdapter adapter = new JGitClientAdapter("git", repo.toString(), "task/", 120000);
        String worktreePath = "worktrees/TASK-1/RUN-1";
        adapter.createRunBranchAndWorktree("RUN-1", baseCommit, "run/RUN-1", worktreePath);

        assertTrue(Files.exists(repo.resolve(worktreePath)));
        adapter.updateTaskBranch("TASK-1", baseCommit);
        String taskBranchHead = runGit(repo, List.of("rev-parse", "task/TASK-1")).trim();
        assertTrue(taskBranchHead.equals(baseCommit));
        adapter.removeWorktree(worktreePath);
        assertFalse(Files.exists(repo.resolve(worktreePath)));
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
        ProcessBuilder builder = new ProcessBuilder();
        builder.command(buildCommand(args));
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

    private static List<String> buildCommand(List<String> args) {
        java.util.ArrayList<String> command = new java.util.ArrayList<>();
        command.add("git");
        command.addAll(args);
        return command;
    }
}
