package com.agentx.agentxbackend.workspace.application;

import com.agentx.agentxbackend.workspace.application.port.out.GitClientPort;
import com.agentx.agentxbackend.workspace.application.port.out.GitWorkspaceRepository;
import com.agentx.agentxbackend.workspace.domain.model.GitWorkspace;
import com.agentx.agentxbackend.workspace.domain.model.GitWorkspaceStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WorkspaceServiceTest {

    @Mock
    private GitWorkspaceRepository gitWorkspaceRepository;
    @Mock
    private GitClientPort gitClientPort;

    private WorkspaceService workspaceService;

    @BeforeEach
    void setUp() {
        workspaceService = new WorkspaceService(gitWorkspaceRepository, gitClientPort);
    }

    @Test
    void allocateShouldCreateGitWorkspaceAfterGitAllocation() {
        when(gitWorkspaceRepository.findByRunId("RUN-1")).thenReturn(Optional.empty());
        when(gitWorkspaceRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        GitWorkspace workspace = workspaceService.allocate("RUN-1", "abc123", "run/RUN-1", "worktrees/TASK-1/RUN-1");

        assertEquals("RUN-1", workspace.runId());
        assertEquals(GitWorkspaceStatus.ALLOCATED, workspace.status());
        InOrder inOrder = inOrder(gitClientPort, gitWorkspaceRepository);
        inOrder.verify(gitClientPort).createRunBranchAndWorktree(
            "RUN-1",
            "abc123",
            "run/RUN-1",
            "worktrees/TASK-1/RUN-1"
        );
        inOrder.verify(gitWorkspaceRepository).save(any());
    }

    @Test
    void allocateShouldCleanupWorktreeWhenSaveFails() {
        when(gitWorkspaceRepository.findByRunId("RUN-2")).thenReturn(Optional.empty());
        when(gitWorkspaceRepository.save(any())).thenThrow(new IllegalStateException("db error"));

        assertThrows(
            IllegalStateException.class,
            () -> workspaceService.allocate("RUN-2", "abc123", "run/RUN-2", "worktrees/TASK-2/RUN-2")
        );
        verify(gitClientPort).removeWorktree("worktrees/TASK-2/RUN-2");
    }

    @Test
    void releaseShouldMarkBrokenWhenCleanupFails() {
        Instant createdAt = Instant.parse("2026-02-23T00:00:00Z");
        GitWorkspace allocated = new GitWorkspace("RUN-3", GitWorkspaceStatus.ALLOCATED, createdAt, createdAt);
        when(gitWorkspaceRepository.findByRunId("RUN-3")).thenReturn(Optional.of(allocated));
        when(gitWorkspaceRepository.update(any())).thenAnswer(invocation -> invocation.getArgument(0));
        org.mockito.Mockito.doThrow(new IllegalStateException("remove failed"))
            .when(gitClientPort).removeWorktree("worktrees/TASK-3/RUN-3");

        assertThrows(
            IllegalStateException.class,
            () -> workspaceService.release("RUN-3", "worktrees/TASK-3/RUN-3")
        );

        verify(gitWorkspaceRepository).update(any(GitWorkspace.class));
    }

    @Test
    void releaseShouldBeIdempotentWhenAlreadyReleased() {
        Instant createdAt = Instant.parse("2026-02-23T00:00:00Z");
        GitWorkspace released = new GitWorkspace("RUN-4", GitWorkspaceStatus.RELEASED, createdAt, createdAt);
        when(gitWorkspaceRepository.findByRunId("RUN-4")).thenReturn(Optional.of(released));

        GitWorkspace result = workspaceService.release("RUN-4", "worktrees/TASK-4/RUN-4");

        assertEquals(GitWorkspaceStatus.RELEASED, result.status());
        verify(gitClientPort, never()).removeWorktree("worktrees/TASK-4/RUN-4");
    }

    @Test
    void updateTaskBranchShouldDelegateToGitClient() {
        workspaceService.updateTaskBranch("TASK-5", "abc123");

        verify(gitClientPort).updateTaskBranch("TASK-5", "abc123");
        verify(gitWorkspaceRepository, never()).findByRunId(any());
    }
}
