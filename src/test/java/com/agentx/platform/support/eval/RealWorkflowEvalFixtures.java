package com.agentx.platform.support.eval;

import com.agentx.platform.support.TestGitRepoHelper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.List;

public final class RealWorkflowEvalFixtures {

    private static final Path FIXTURE_ROOT = Path.of("src", "test", "resources", "repo-fixtures");

    private RealWorkflowEvalFixtures() {
    }

    public static void seedFixture(String fixtureId, Path repoRoot) {
        Path fixtureDirectory = FIXTURE_ROOT.resolve(fixtureId).toAbsolutePath().normalize();
        if (Files.notExists(fixtureDirectory) || !Files.isDirectory(fixtureDirectory)) {
            throw new IllegalArgumentException("unsupported repo fixture: " + fixtureId);
        }
        try {
            clearWorkingTree(repoRoot);
            copyTree(fixtureDirectory, repoRoot);
            TestGitRepoHelper.run(repoRoot, List.of("git", "add", "-A"));
            TestGitRepoHelper.run(repoRoot, List.of("git", "commit", "--allow-empty", "-m", "seed fixture " + fixtureId));
        } catch (IOException exception) {
            throw new IllegalStateException("failed to seed fixture repository " + fixtureId, exception);
        }
    }

    private static void clearWorkingTree(Path repoRoot) throws IOException {
        try (var stream = Files.walk(repoRoot)) {
            for (Path candidate : stream.sorted(Comparator.reverseOrder()).toList()) {
                if (candidate.equals(repoRoot) || candidate.startsWith(repoRoot.resolve(".git"))) {
                    continue;
                }
                Files.deleteIfExists(candidate);
            }
        }
    }

    private static void copyTree(Path sourceRoot, Path targetRoot) throws IOException {
        try (var stream = Files.walk(sourceRoot)) {
            for (Path candidate : stream.sorted(Comparator.naturalOrder()).toList()) {
                Path relative = sourceRoot.relativize(candidate);
                Path target = targetRoot.resolve(relative.toString());
                if (Files.isDirectory(candidate)) {
                    Files.createDirectories(target);
                    continue;
                }
                Files.createDirectories(target.getParent());
                Files.copy(candidate, target, StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }
}
