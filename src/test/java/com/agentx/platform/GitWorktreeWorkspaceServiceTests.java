package com.agentx.platform;

import com.agentx.platform.domain.execution.model.GitWorkspace;
import com.agentx.platform.domain.execution.model.TaskRun;
import com.agentx.platform.domain.execution.model.TaskRunStatus;
import com.agentx.platform.domain.execution.model.RunKind;
import com.agentx.platform.domain.planning.model.WorkTask;
import com.agentx.platform.domain.planning.model.WorkTaskStatus;
import com.agentx.platform.domain.shared.model.ActorRef;
import com.agentx.platform.domain.shared.model.ActorType;
import com.agentx.platform.domain.shared.model.JsonPayload;
import com.agentx.platform.domain.shared.model.WriteScope;
import com.agentx.platform.runtime.support.ProcessCommandRunner;
import com.agentx.platform.runtime.support.RuntimeInfrastructureProperties;
import com.agentx.platform.runtime.workspace.git.GitWorktreeWorkspaceService;
import com.agentx.platform.support.TestGitRepoHelper;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class GitWorktreeWorkspaceServiceTests {

    @Test
    void shouldAllocateMergeAndCleanupRealGitWorktree() throws Exception {
        Path testRoot = Files.createTempDirectory("agentx-worktree-test");
        Path repoRoot = testRoot.resolve("repo");
        Path workspaceRoot = testRoot.resolve("workspaces");
        TestGitRepoHelper.resetFixtureRepository(repoRoot);

        RuntimeInfrastructureProperties properties = new RuntimeInfrastructureProperties();
        properties.setRepoRoot(repoRoot);
        properties.setBaseBranch("main");
        properties.setWorkspaceRoot(workspaceRoot);
        GitWorktreeWorkspaceService service = new GitWorktreeWorkspaceService(properties, new ProcessCommandRunner());

        WorkTask task = new WorkTask(
                "task-healthz",
                "module-api",
                "实现 healthz",
                "生成交付候选",
                "java-backend-task",
                WorkTaskStatus.READY,
                List.of(new WriteScope("src/main/java")),
                null,
                new ActorRef(ActorType.AGENT, "architect-agent")
        );
        TaskRun run = new TaskRun(
                "task-healthz-run-001",
                task.taskId(),
                "ainst-01",
                TaskRunStatus.RUNNING,
                RunKind.IMPL,
                "snapshot-001",
                LocalDateTime.now(),
                LocalDateTime.now(),
                LocalDateTime.now(),
                null,
                JsonPayload.emptyObject()
        );

        GitWorkspace workspace = service.allocate("workflow-healthz", task, run);
        Path worktreePath = Path.of(workspace.worktreePath());
        assertThat(Files.isDirectory(worktreePath.resolve("src/main/java"))).isTrue();
        Files.writeString(worktreePath.resolve("src/main/java/.agentx-task-healthz.txt"), "hello");
        TestGitRepoHelper.run(worktreePath, List.of("git", "add", "."));
        TestGitRepoHelper.run(worktreePath, List.of("git", "commit", "-m", "task output"));

        GitWorkspace refreshed = service.refreshHeadCommit(workspace);
        GitWorkspace merged = service.mergeCandidate(refreshed);
        GitWorkspace cleaned = service.cleanup(merged);

        assertThat(refreshed.headCommit()).isNotBlank();
        assertThat(merged.mergeCommit()).isNotBlank();
        assertThat(cleaned.cleanupStatus()).isEqualTo(com.agentx.platform.domain.execution.model.CleanupStatus.DONE);
        assertThat(cleaned.status()).isEqualTo(com.agentx.platform.domain.execution.model.GitWorkspaceStatus.CLEANED);
    }
}
