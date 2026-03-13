package com.agentx.agentxbackend.contextpack.application.port.in;

import com.agentx.agentxbackend.contextpack.domain.model.RoleContextPack;
import com.agentx.agentxbackend.contextpack.domain.model.TaskContextPack;
import com.agentx.agentxbackend.contextpack.domain.model.TaskContextSnapshotStatusView;
import com.agentx.agentxbackend.contextpack.domain.model.TaskSkill;

import java.util.List;

public interface ContextCompileUseCase {

    RoleContextPack compileRolePack(String sessionId, String role);

    TaskContextPack compileTaskContextPack(String taskId, String runKind);

    default TaskContextPack compileTaskContextPack(String taskId, String runKind, String triggerType) {
        return compileTaskContextPack(taskId, runKind);
    }

    TaskSkill compileTaskSkill(String taskId);

    TaskContextSnapshotStatusView getTaskContextStatus(String taskId, int limit);

    int refreshTaskContextsBySession(String sessionId, String triggerType, int limit);

    int refreshTaskContextsByTicket(String ticketId, String triggerType, int limit);

    boolean refreshTaskContextByTask(String taskId, String triggerType);

    default RepoContextPrompt buildRepoContextPrompt(
        String queryText,
        List<String> includeRoots,
        int maxFiles,
        int maxExcerpts,
        int maxExcerptChars,
        int maxTotalExcerptChars
    ) {
        return new RepoContextPrompt("", "repo_context_prompt_v1", List.of("repo_context_prompt_not_supported"));
    }

    record RepoContextPrompt(
        String promptText,
        String indexKind,
        List<String> warnings
    ) {
    }
}
