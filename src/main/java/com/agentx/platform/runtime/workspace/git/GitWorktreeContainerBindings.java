package com.agentx.platform.runtime.workspace.git;

import com.agentx.platform.runtime.agentruntime.ContainerMount;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class GitWorktreeContainerBindings {

    private static final String CONTAINER_GIT_ROOT = "/agentx/repo/.git";

    private GitWorktreeContainerBindings() {
    }

    public static GitContainerBinding forWorktree(Path worktreePath, String workingDirectory, boolean worktreeReadOnly, boolean gitMetadataReadOnly) {
        Path gitDir = resolveGitDir(worktreePath);
        Path commonGitDir = gitDir.getParent().getParent();
        String adminDirName = gitDir.getFileName().toString();

        Map<String, String> environment = new LinkedHashMap<>();
        environment.put("GIT_DIR", CONTAINER_GIT_ROOT + "/worktrees/" + adminDirName);
        environment.put("GIT_WORK_TREE", workingDirectory);

        return new GitContainerBinding(
                List.of(
                        new ContainerMount(worktreePath.toAbsolutePath().normalize(), workingDirectory, worktreeReadOnly),
                        new ContainerMount(commonGitDir.toAbsolutePath().normalize(), CONTAINER_GIT_ROOT, gitMetadataReadOnly)
                ),
                environment
        );
    }

    private static Path resolveGitDir(Path worktreePath) {
        Path gitPointer = worktreePath.resolve(".git");
        try {
            String rawPointer = Files.readString(gitPointer, StandardCharsets.UTF_8).trim();
            if (!rawPointer.startsWith("gitdir:")) {
                throw new IllegalStateException("expected gitdir pointer in " + gitPointer);
            }
            String rawGitDir = rawPointer.substring("gitdir:".length()).trim();
            Path gitDir = Path.of(rawGitDir);
            if (gitDir.isAbsolute()) {
                return gitDir.normalize();
            }
            return worktreePath.resolve(rawGitDir).normalize();
        } catch (IOException exception) {
            throw new IllegalStateException("failed to resolve gitdir for worktree " + worktreePath, exception);
        }
    }

    public record GitContainerBinding(
            List<ContainerMount> mounts,
            Map<String, String> environment
    ) {
    }
}
