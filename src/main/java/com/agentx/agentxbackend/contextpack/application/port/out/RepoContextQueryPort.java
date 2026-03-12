package com.agentx.agentxbackend.contextpack.application.port.out;

import java.util.List;

public interface RepoContextQueryPort {

    RepoContext query(RepoContextQuery query);

    record RepoContextQuery(
        String queryText,
        List<String> includeRoots,
        int maxFiles,
        int maxExcerpts,
        int maxExcerptChars,
        int maxTotalExcerptChars
    ) {
    }

    record RepoContext(
        String indexKind,
        String repoRoot,
        String repoHeadRef,
        List<String> queryTerms,
        List<String> topLevelEntries,
        List<ScoredPath> relevantFiles,
        List<FileExcerpt> excerpts,
        List<String> warnings
    ) {
    }

    record ScoredPath(
        String path,
        int score,
        String reason
    ) {
    }

    record FileExcerpt(
        String path,
        int score,
        String excerpt
    ) {
    }
}
