package com.agentx.agentxbackend.workspace.application;

import com.agentx.agentxbackend.workspace.application.port.in.WorkspaceUseCase;
import com.agentx.agentxbackend.workspace.application.port.out.GitClientPort;
import com.agentx.agentxbackend.workspace.application.port.out.GitWorkspaceRepository;
import com.agentx.agentxbackend.workspace.domain.model.GitWorkspace;
import com.agentx.agentxbackend.workspace.domain.model.GitWorkspaceStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.NoSuchElementException;

@Service
public class WorkspaceService implements WorkspaceUseCase {

    private final GitWorkspaceRepository gitWorkspaceRepository;
    private final GitClientPort gitClientPort;

    public WorkspaceService(GitWorkspaceRepository gitWorkspaceRepository, GitClientPort gitClientPort) {
        this.gitWorkspaceRepository = gitWorkspaceRepository;
        this.gitClientPort = gitClientPort;
    }

    @Override
    @Transactional
    public GitWorkspace allocate(
        String runId,
        String sessionId,
        String baseCommit,
        String branchName,
        String worktreePath
    ) {
        String normalizedRunId = requireNotBlank(runId, "runId");
        String normalizedSessionId = requireNotBlank(sessionId, "sessionId");
        String normalizedBaseCommit = requireNotBlank(baseCommit, "baseCommit");
        String normalizedBranchName = requireNotBlank(branchName, "branchName");
        String normalizedWorktreePath = requireNotBlank(worktreePath, "worktreePath");

        if (gitWorkspaceRepository.findByRunId(normalizedRunId).isPresent()) {
            throw new IllegalStateException("Workspace already exists for run: " + normalizedRunId);
        }

        gitClientPort.createRunBranchAndWorktree(
            normalizedRunId,
            normalizedSessionId,
            normalizedBaseCommit,
            normalizedBranchName,
            normalizedWorktreePath
        );

        Instant now = Instant.now();
        GitWorkspace workspace = new GitWorkspace(
            normalizedRunId,
            GitWorkspaceStatus.ALLOCATED,
            now,
            now
        );
        try {
            return gitWorkspaceRepository.save(workspace);
        } catch (RuntimeException ex) {
            try {
                gitClientPort.removeWorktree(normalizedWorktreePath);
            } catch (RuntimeException ignored) {
                // Workspace record does not exist yet; cleanup is best effort.
            }
            throw ex;
        }
    }

    @Override
    @Transactional
    public GitWorkspace release(String runId, String worktreePath) {
        String normalizedRunId = requireNotBlank(runId, "runId");
        String normalizedWorktreePath = requireNotBlank(worktreePath, "worktreePath");
        GitWorkspace current = gitWorkspaceRepository.findByRunId(normalizedRunId)
            .orElseThrow(() -> new NoSuchElementException("Workspace not found: " + normalizedRunId));
        if (current.status() == GitWorkspaceStatus.RELEASED) {
            return current;
        }
        try {
            gitClientPort.removeWorktree(normalizedWorktreePath);
            GitWorkspace released = new GitWorkspace(
                current.runId(),
                GitWorkspaceStatus.RELEASED,
                current.createdAt(),
                Instant.now()
            );
            return gitWorkspaceRepository.update(released);
        } catch (RuntimeException ex) {
            GitWorkspace broken = new GitWorkspace(
                current.runId(),
                GitWorkspaceStatus.BROKEN,
                current.createdAt(),
                Instant.now()
            );
            gitWorkspaceRepository.update(broken);
            throw ex;
        }
    }

    @Override
    @Transactional
    public GitWorkspace markBroken(String runId) {
        requireNotBlank(runId, "runId");
        GitWorkspace current = gitWorkspaceRepository.findByRunId(runId)
            .orElseThrow(() -> new NoSuchElementException("Workspace not found: " + runId));
        if (current.status() == GitWorkspaceStatus.BROKEN) {
            return current;
        }
        GitWorkspace updated = new GitWorkspace(
            current.runId(),
            GitWorkspaceStatus.BROKEN,
            current.createdAt(),
            Instant.now()
        );
        return gitWorkspaceRepository.update(updated);
    }

    @Override
    @Transactional
    public void updateTaskBranch(String sessionId, String taskId, String deliveryCommit) {
        String normalizedSessionId = requireNotBlank(sessionId, "sessionId");
        String normalizedTaskId = requireNotBlank(taskId, "taskId");
        String normalizedDeliveryCommit = requireNotBlank(deliveryCommit, "deliveryCommit");
        gitClientPort.updateTaskBranch(normalizedSessionId, normalizedTaskId, normalizedDeliveryCommit);
    }

    private static String requireNotBlank(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }
}
