package com.agentx.agentxbackend.workspace.application.port.out;

import com.agentx.agentxbackend.workspace.domain.model.GitWorkspace;

import java.util.Optional;

public interface GitWorkspaceRepository {

    GitWorkspace save(GitWorkspace workspace);

    Optional<GitWorkspace> findByRunId(String runId);

    GitWorkspace update(GitWorkspace workspace);
}
