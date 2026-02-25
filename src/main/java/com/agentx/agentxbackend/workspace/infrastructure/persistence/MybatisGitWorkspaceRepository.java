package com.agentx.agentxbackend.workspace.infrastructure.persistence;

import com.agentx.agentxbackend.workspace.application.port.out.GitWorkspaceRepository;
import com.agentx.agentxbackend.workspace.domain.model.GitWorkspace;
import com.agentx.agentxbackend.workspace.domain.model.GitWorkspaceStatus;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;

@Repository
public class MybatisGitWorkspaceRepository implements GitWorkspaceRepository {

    private final GitWorkspaceMapper mapper;

    public MybatisGitWorkspaceRepository(GitWorkspaceMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public GitWorkspace save(GitWorkspace workspace) {
        int inserted = mapper.insert(toRow(workspace));
        if (inserted != 1) {
            throw new IllegalStateException("Failed to insert git workspace: " + workspace.runId());
        }
        return workspace;
    }

    @Override
    public Optional<GitWorkspace> findByRunId(String runId) {
        GitWorkspaceRow row = mapper.findByRunId(runId);
        return Optional.ofNullable(row).map(this::toDomain);
    }

    @Override
    public GitWorkspace update(GitWorkspace workspace) {
        int updated = mapper.updateStatus(
            workspace.runId(),
            workspace.status().name(),
            Timestamp.from(workspace.updatedAt())
        );
        if (updated != 1) {
            throw new IllegalStateException("Failed to update git workspace: " + workspace.runId());
        }
        return workspace;
    }

    private GitWorkspaceRow toRow(GitWorkspace workspace) {
        GitWorkspaceRow row = new GitWorkspaceRow();
        row.setRunId(workspace.runId());
        row.setStatus(workspace.status().name());
        row.setCreatedAt(Timestamp.from(workspace.createdAt()));
        row.setUpdatedAt(Timestamp.from(workspace.updatedAt()));
        return row;
    }

    private GitWorkspace toDomain(GitWorkspaceRow row) {
        Instant createdAt = row.getCreatedAt().toInstant();
        Instant updatedAt = row.getUpdatedAt().toInstant();
        return new GitWorkspace(
            row.getRunId(),
            GitWorkspaceStatus.valueOf(row.getStatus()),
            createdAt,
            updatedAt
        );
    }
}
