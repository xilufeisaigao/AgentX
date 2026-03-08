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
    void shouldCreateAndRemoveWorktreeInSessionRepo() throws Exception {
        assumeTrue(canRunGit(), "git executable is required");
        Path repoRoot = tempDir.resolve("repo-root");
        Files.createDirectories(repoRoot);
        runGit(repoRoot, List.of("init"));
        runGit(repoRoot, List.of("config", "user.email", "agentx@test.local"));
        runGit(repoRoot, List.of("config", "user.name", "AgentX Test"));
        runGit(repoRoot, List.of("checkout", "-B", "main"));
        Files.writeString(repoRoot.resolve("README.md"), "# template", StandardCharsets.UTF_8);
        runGit(repoRoot, List.of("add", "README.md"));
        runGit(repoRoot, List.of("commit", "-m", "init"));
        String baseCommit = runGit(repoRoot, List.of("rev-parse", "HEAD")).trim();

        JGitClientAdapter adapter = new JGitClientAdapter(
            "git",
            repoRoot.toString(),
            "sessions",
            "main",
            "task/",
            "agentx@test.local",
            "AgentX Test",
            120000
        );
        String worktreePath = "worktrees/SES-1/RUN-1";
        adapter.createRunBranchAndWorktree("RUN-1", "SES-1", baseCommit, "run/RUN-1", worktreePath);

        Path sessionRepo = repoRoot.resolve("sessions/ses-1/repo");
        assertTrue(Files.exists(sessionRepo.resolve(worktreePath)));
        adapter.updateTaskBranch("SES-1", "TASK-1", baseCommit);
        String taskBranchHead = runGit(sessionRepo, List.of("rev-parse", "task/TASK-1")).trim();
        assertTrue(taskBranchHead.equals(baseCommit));
        adapter.removeWorktree(worktreePath);
        assertFalse(Files.exists(sessionRepo.resolve(worktreePath)));
    }

    @Test
    void updateTaskBranchShouldAdvanceCheckedOutTaskBranch() throws Exception {
        assumeTrue(canRunGit(), "git executable is required");
        Path repoRoot = tempDir.resolve("repo-root-checked-out-task");
        Files.createDirectories(repoRoot);
        runGit(repoRoot, List.of("init"));
        runGit(repoRoot, List.of("config", "user.email", "agentx@test.local"));
        runGit(repoRoot, List.of("config", "user.name", "AgentX Test"));
        runGit(repoRoot, List.of("checkout", "-B", "main"));
        Files.writeString(repoRoot.resolve("README.md"), "# template", StandardCharsets.UTF_8);
        runGit(repoRoot, List.of("add", "README.md"));
        runGit(repoRoot, List.of("commit", "-m", "init"));
        String baseCommit = runGit(repoRoot, List.of("rev-parse", "HEAD")).trim();

        JGitClientAdapter adapter = new JGitClientAdapter(
            "git",
            repoRoot.toString(),
            "sessions",
            "main",
            "task/",
            "agentx@test.local",
            "AgentX Test",
            120000
        );

        String worktreePath = "worktrees/SES-2/RUN-1";
        adapter.createRunBranchAndWorktree("RUN-1", "SES-2", baseCommit, "run/RUN-1", worktreePath);
        adapter.updateTaskBranch("SES-2", "TASK-2", baseCommit);

        Path sessionRepo = repoRoot.resolve("sessions/ses-2/repo");
        runGit(sessionRepo, List.of("checkout", "main"));
        Files.writeString(sessionRepo.resolve("README.md"), "# updated\n", StandardCharsets.UTF_8);
        runGit(sessionRepo, List.of("add", "README.md"));
        runGit(sessionRepo, List.of("commit", "-m", "delivery"));
        String deliveryCommit = runGit(sessionRepo, List.of("rev-parse", "HEAD")).trim();

        runGit(sessionRepo, List.of("checkout", "task/TASK-2"));
        adapter.updateTaskBranch("SES-2", "TASK-2", deliveryCommit);

        String taskBranchHead = runGit(sessionRepo, List.of("rev-parse", "task/TASK-2")).trim();
        String checkedOutHead = runGit(sessionRepo, List.of("rev-parse", "HEAD")).trim();
        assertTrue(taskBranchHead.equals(deliveryCommit));
        assertTrue(checkedOutHead.equals(deliveryCommit));
    }

    @Test
    void createRunBranchAndWorktreeShouldFallbackToMainWhenBaseCommitUnavailable() throws Exception {
        assumeTrue(canRunGit(), "git executable is required");
        Path repoRoot = tempDir.resolve("repo-root-baseline-fallback");
        Files.createDirectories(repoRoot);
        runGit(repoRoot, List.of("init"));
        runGit(repoRoot, List.of("config", "user.email", "agentx@test.local"));
        runGit(repoRoot, List.of("config", "user.name", "AgentX Test"));
        runGit(repoRoot, List.of("checkout", "-B", "main"));
        Files.writeString(repoRoot.resolve("README.md"), "# template", StandardCharsets.UTF_8);
        runGit(repoRoot, List.of("add", "README.md"));
        runGit(repoRoot, List.of("commit", "-m", "init"));
        String mainCommit = runGit(repoRoot, List.of("rev-parse", "HEAD")).trim();

        JGitClientAdapter adapter = new JGitClientAdapter(
            "git",
            repoRoot.toString(),
            "sessions",
            "main",
            "task/",
            "agentx@test.local",
            "AgentX Test",
            120000
        );

        String worktreePath = "worktrees/SES-3/RUN-1";
        adapter.createRunBranchAndWorktree(
            "RUN-1",
            "SES-3",
            "BASELINE_UNAVAILABLE",
            "run/RUN-1",
            worktreePath
        );

        Path sessionRepo = repoRoot.resolve("sessions/ses-3/repo");
        assertTrue(Files.exists(sessionRepo.resolve(worktreePath)));
        String runBranchHead = runGit(sessionRepo, List.of("rev-parse", "run/RUN-1")).trim();
        assertTrue(runBranchHead.equals(mainCommit));
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
