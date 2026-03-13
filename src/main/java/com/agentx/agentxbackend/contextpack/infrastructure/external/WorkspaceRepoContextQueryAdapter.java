package com.agentx.agentxbackend.contextpack.infrastructure.external;

import com.agentx.agentxbackend.contextpack.application.port.out.RepoContextQueryPort;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

@Component
public class WorkspaceRepoContextQueryAdapter implements RepoContextQueryPort {

    private static final int MAX_INDEXED_FILES = 20_000;
    private static final int MAX_FILES_TO_SCORE = 8_000;
    private static final int MAX_PATH_CANDIDATES_FOR_CONTENT = 400;
    private static final int MAX_CONTENT_CHARS_FOR_SCORE = 30_000;
    private static final int MAX_CONTENT_CHARS_FOR_EXCERPT = 50_000;
    private static final Pattern QUERY_IDENTIFIER_PATTERN = Pattern.compile("[A-Za-z_][A-Za-z0-9_]{2,}");
    private static final Pattern QUERY_CHINESE_PHRASE_PATTERN = Pattern.compile("[\\p{IsHan}]{2,}");

    private final String gitExecutable;
    private final Path repoRoot;
    private final Duration indexTtl;
    private final LangChain4jSemanticRepoIndexSupport semanticSupport;

    private final Map<String, RepoIndex> cachedIndexes = new ConcurrentHashMap<>();

    public WorkspaceRepoContextQueryAdapter(
        @Value("${agentx.workspace.git.executable:git}") String gitExecutable,
        @Value("${agentx.workspace.git.repo-root:.}") String repoRoot,
        @Value("${agentx.contextpack.repo-context.index-ttl-seconds:600}") long indexTtlSeconds,
        @Value("${agentx.contextpack.repo-context.rag.enabled:true}") boolean semanticEnabled,
        @Value("${agentx.contextpack.repo-context.rag.base-url:https://api.openai.com/v1}") String semanticBaseUrl,
        @Value("${agentx.contextpack.repo-context.rag.api-key:}") String semanticApiKey,
        @Value("${agentx.contextpack.repo-context.rag.model:text-embedding-3-small}") String semanticModel,
        @Value("${agentx.contextpack.repo-context.rag.timeout-ms:60000}") long semanticTimeoutMs,
        @Value("${agentx.contextpack.repo-context.rag.max-indexed-files:2000}") int semanticMaxIndexedFiles,
        @Value("${agentx.contextpack.repo-context.rag.max-file-chars:24000}") int semanticMaxFileChars,
        @Value("${agentx.contextpack.repo-context.rag.max-total-chars:2000000}") int semanticMaxTotalChars,
        @Value("${agentx.contextpack.repo-context.rag.segment-chars:1000}") int semanticSegmentChars,
        @Value("${agentx.contextpack.repo-context.rag.segment-overlap-chars:120}") int semanticSegmentOverlapChars,
        @Value("${agentx.contextpack.repo-context.rag.max-search-results:24}") int semanticMaxSearchResults,
        @Value("${agentx.contextpack.repo-context.rag.min-score:0.55}") double semanticMinScore
    ) {
        this(
            gitExecutable,
            repoRoot,
            indexTtlSeconds,
            new LangChain4jSemanticRepoIndexSupport(
                new LangChain4jSemanticRepoIndexSupport.SemanticConfig(
                    semanticEnabled,
                    semanticBaseUrl,
                    semanticApiKey,
                    semanticModel,
                    semanticTimeoutMs,
                    indexTtlSeconds,
                    semanticMaxIndexedFiles,
                    semanticMaxFileChars,
                    semanticMaxTotalChars,
                    semanticSegmentChars,
                    semanticSegmentOverlapChars,
                    semanticMaxSearchResults,
                    semanticMinScore
                )
            )
        );
    }

    WorkspaceRepoContextQueryAdapter(
        String gitExecutable,
        String repoRoot,
        long indexTtlSeconds,
        LangChain4jSemanticRepoIndexSupport semanticSupport
    ) {
        this.gitExecutable = (gitExecutable == null || gitExecutable.isBlank()) ? "git" : gitExecutable.trim();
        this.repoRoot = Path.of(repoRoot == null || repoRoot.isBlank() ? "." : repoRoot.trim())
            .toAbsolutePath()
            .normalize();
        long cappedSeconds = Math.max(30L, Math.min(3600L, indexTtlSeconds));
        this.indexTtl = Duration.ofSeconds(cappedSeconds);
        this.semanticSupport = semanticSupport == null
            ? new LangChain4jSemanticRepoIndexSupport(LangChain4jSemanticRepoIndexSupport.SemanticConfig.disabled())
            : semanticSupport;
    }

    @Override
    public RepoContext query(RepoContextQuery query) {
        if (query == null) {
            throw new IllegalArgumentException("query must not be null");
        }
        String queryText = query.queryText() == null ? "" : query.queryText().trim();
        QueryTarget target = resolveQueryTarget(query.includeRoots());
        RepoIndex index = resolveIndex(target.effectiveRepoRoot());

        if (queryText.isBlank()) {
            return new RepoContext(
                "lexical_v1",
                index.repoRoot(),
                index.repoHeadRef(),
                List.of(),
                index.topLevelEntries(),
                List.of(),
                List.of(),
                List.of("empty_query_text")
            );
        }

        List<String> queryTerms = extractQueryTerms(queryText);
        List<String> includeRoots = target.localIncludeRoots();
        int maxFiles = cap(query.maxFiles(), 1, 60, 24);
        int maxExcerpts = cap(query.maxExcerpts(), 0, 20, 6);
        int maxExcerptChars = cap(query.maxExcerptChars(), 200, 2400, 900);
        int maxTotalExcerptChars = cap(query.maxTotalExcerptChars(), 500, 20_000, 6_000);
        LinkedHashSet<String> warnings = new LinkedHashSet<>(index.warnings());

        if (semanticSupport.isEnabled()) {
            LangChain4jSemanticRepoIndexSupport.SemanticQueryResult semanticResult = semanticSupport.query(
                target.effectiveRepoRoot(),
                index.repoHeadRef(),
                index.files(),
                queryText,
                includeRoots,
                maxFiles,
                maxExcerpts,
                maxExcerptChars,
                maxTotalExcerptChars
            );
            warnings.addAll(semanticResult.warnings());
            if (semanticResult.hasMatches()) {
                return new RepoContext(
                    semanticResult.indexKind(),
                    index.repoRoot(),
                    index.repoHeadRef(),
                    queryTerms,
                    index.topLevelEntries(),
                    semanticResult.relevantFiles(),
                    semanticResult.excerpts(),
                    List.copyOf(warnings)
                );
            }
            warnings.add("semantic_fallback_to_lexical");
        }

        List<ScoredPath> scored = scorePaths(index.files(), includeRoots, queryTerms);
        if (scored.isEmpty()) {
            warnings.add("no_visible_files");
            return new RepoContext(
                "lexical_v1",
                index.repoRoot(),
                index.repoHeadRef(),
                queryTerms,
                index.topLevelEntries(),
                List.of(),
                List.of(),
                List.copyOf(warnings)
            );
        }

        List<ScoredPath> topFiles = scored.subList(0, Math.min(maxFiles, scored.size()));
        List<FileExcerpt> excerpts = buildExcerpts(
            target.effectiveRepoRoot(),
            topFiles,
            queryTerms,
            maxExcerpts,
            maxExcerptChars,
            maxTotalExcerptChars
        );

        return new RepoContext(
            "lexical_v1",
            index.repoRoot(),
            index.repoHeadRef(),
            queryTerms,
            index.topLevelEntries(),
            topFiles,
            excerpts,
            List.copyOf(warnings)
        );
    }

    private RepoIndex resolveIndex(Path effectiveRepoRoot) {
        String currentHeadRef = resolveRepoHeadRef(effectiveRepoRoot).orElse("git:HEAD_UNKNOWN");
        String cacheKey = effectiveRepoRoot.toAbsolutePath().normalize().toString();
        RepoIndex current = cachedIndexes.get(cacheKey);
        if (current != null && current.isFresh(indexTtl) && current.matchesRepo(currentHeadRef)) {
            return current;
        }
        synchronized (cachedIndexes) {
            RepoIndex refreshed = cachedIndexes.get(cacheKey);
            if (refreshed != null && refreshed.isFresh(indexTtl) && refreshed.matchesRepo(currentHeadRef)) {
                return refreshed;
            }
            RepoIndex rebuilt = buildIndex(effectiveRepoRoot, currentHeadRef);
            cachedIndexes.put(cacheKey, rebuilt);
            return rebuilt;
        }
    }

    private RepoIndex buildIndex(Path effectiveRepoRoot, String repoHeadRef) {
        List<String> warnings = new ArrayList<>();
        List<String> topLevel = buildTopLevelEntries(effectiveRepoRoot);
        if (!Files.exists(effectiveRepoRoot)) {
            warnings.add("repo_root_missing:" + effectiveRepoRoot);
        }

        List<String> files = new ArrayList<>();
        try (Stream<Path> stream = Files.walk(effectiveRepoRoot)) {
            files = stream
                .filter(Files::isRegularFile)
                .map(path -> normalizePath(effectiveRepoRoot.relativize(path).toString()))
                .filter(path -> !path.isBlank())
                .filter(path -> !isIgnoredPath(path))
                .limit(MAX_INDEXED_FILES)
                .toList();
        } catch (Exception ex) {
            warnings.add("repo_walk_failed:" + safeError(ex));
        }

        if (files.size() >= MAX_INDEXED_FILES) {
            warnings.add("file_index_capped:" + MAX_INDEXED_FILES);
        }
        return new RepoIndex(
            "lexical_v1",
            effectiveRepoRoot.toString().replace('\\', '/'),
            repoHeadRef,
            Instant.now(),
            topLevel,
            files,
            List.copyOf(warnings)
        );
    }

    private Optional<String> resolveRepoHeadRef(Path effectiveRepoRoot) {
        Path gitDir = effectiveRepoRoot.resolve(".git");
        if (!Files.exists(gitDir)) {
            return Optional.empty();
        }
        try {
            ProcessBuilder pb = new ProcessBuilder(List.of(gitExecutable, "rev-parse", "HEAD"));
            pb.directory(effectiveRepoRoot.toFile());
            pb.redirectErrorStream(true);
            Process proc = pb.start();
            boolean finished = proc.waitFor(5, TimeUnit.SECONDS);
            if (!finished) {
                proc.destroyForcibly();
                return Optional.empty();
            }
            if (proc.exitValue() != 0) {
                return Optional.empty();
            }
            String head = readProcessOutput(proc, 128).trim();
            if (head.isBlank()) {
                return Optional.empty();
            }
            return Optional.of("git:" + head);
        } catch (Exception ex) {
            return Optional.empty();
        }
    }

    private QueryTarget resolveQueryTarget(List<String> includeRoots) {
        List<String> normalizedRoots = normalizeIncludeRoots(includeRoots);
        if (normalizedRoots.isEmpty() || normalizedRoots.contains("./")) {
            return new QueryTarget(repoRoot, List.of("./"));
        }
        for (String includeRoot : normalizedRoots) {
            Path candidate = repoRoot.resolve(normalizePath(includeRoot)).toAbsolutePath().normalize();
            if (!candidate.startsWith(repoRoot)) {
                continue;
            }
            Path nestedRepoRoot = findEnclosingGitRepoRoot(candidate);
            if (nestedRepoRoot != null && !nestedRepoRoot.equals(repoRoot)) {
                return new QueryTarget(
                    nestedRepoRoot,
                    relativizeIncludeRoots(normalizedRoots, nestedRepoRoot)
                );
            }
        }
        return new QueryTarget(repoRoot, normalizedRoots);
    }

    private Path findEnclosingGitRepoRoot(Path candidate) {
        Path current = candidate;
        if (!Files.exists(current)) {
            current = current.getParent();
        }
        while (current != null && current.startsWith(repoRoot)) {
            if (Files.exists(current.resolve(".git"))) {
                return current;
            }
            current = current.getParent();
        }
        return null;
    }

    private List<String> relativizeIncludeRoots(List<String> includeRoots, Path nestedRepoRoot) {
        LinkedHashSet<String> relativized = new LinkedHashSet<>();
        for (String includeRoot : includeRoots) {
            if (includeRoot == null || includeRoot.isBlank() || "./".equals(includeRoot)) {
                return List.of("./");
            }
            Path absolute = repoRoot.resolve(normalizePath(includeRoot)).toAbsolutePath().normalize();
            if (!absolute.startsWith(nestedRepoRoot)) {
                continue;
            }
            Path relative = nestedRepoRoot.relativize(absolute);
            String normalizedRelative = normalizePath(relative.toString());
            if (normalizedRelative.isBlank()) {
                return List.of("./");
            }
            if (!normalizedRelative.endsWith("/")) {
                normalizedRelative = normalizedRelative + "/";
            }
            relativized.add(normalizedRelative);
        }
        if (relativized.isEmpty()) {
            return List.of("./");
        }
        return List.copyOf(relativized);
    }

    private static String readProcessOutput(Process proc, int maxChars) {
        if (proc == null) {
            return "";
        }
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            proc.getInputStream().transferTo(out);
            String text = out.toString(StandardCharsets.UTF_8);
            if (text.length() > maxChars) {
                return text.substring(0, maxChars);
            }
            return text;
        } catch (Exception ex) {
            return "";
        }
    }

    private static List<String> buildTopLevelEntries(Path effectiveRepoRoot) {
        if (effectiveRepoRoot == null || !Files.isDirectory(effectiveRepoRoot)) {
            return List.of();
        }
        List<String> entries = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(effectiveRepoRoot)) {
            for (Path child : stream) {
                if (child == null) {
                    continue;
                }
                String name = child.getFileName() == null ? "" : child.getFileName().toString();
                if (name.isBlank()) {
                    continue;
                }
                String normalized = normalizePath(name);
                if (isIgnoredTopLevel(normalized)) {
                    continue;
                }
                if (Files.isDirectory(child)) {
                    entries.add(normalized + "/");
                } else {
                    entries.add(normalized);
                }
                if (entries.size() >= 24) {
                    break;
                }
            }
        } catch (Exception ignored) {
            return List.of();
        }
        entries.sort(String::compareTo);
        return List.copyOf(entries);
    }

    private static boolean isIgnoredTopLevel(String name) {
        String normalized = normalizePath(name);
        return normalized.equals(".git")
            || normalized.equals(".idea")
            || normalized.equals(".vscode")
            || normalized.equals("target")
            || normalized.equals("node_modules")
            || normalized.equals("dist")
            || normalized.equals("build")
            || normalized.equals(".agentx")
            || normalized.equals("runtime-data")
            || normalized.equals("runtime-projects")
            || normalized.startsWith(".env");
    }

    private static List<String> normalizeIncludeRoots(List<String> roots) {
        if (roots == null || roots.isEmpty()) {
            return List.of("./");
        }
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String root : roots) {
            if (root == null || root.isBlank()) {
                continue;
            }
            String value = root.trim().replace('\\', '/');
            if (".".equals(value) || "./".equals(value) || value.isBlank()) {
                return List.of("./");
            }
            if (!value.endsWith("/")) {
                value = value + "/";
            }
            normalized.add(normalizePath(value));
        }
        if (normalized.isEmpty()) {
            return List.of("./");
        }
        return List.copyOf(normalized);
    }

    private static List<String> extractQueryTerms(String queryText) {
        if (queryText == null || queryText.isBlank()) {
            return List.of();
        }
        String normalized = queryText.toLowerCase(Locale.ROOT);
        LinkedHashSet<String> tokens = new LinkedHashSet<>();
        Matcher identifierMatcher = QUERY_IDENTIFIER_PATTERN.matcher(normalized);
        while (identifierMatcher.find()) {
            tokens.add(identifierMatcher.group());
            if (tokens.size() >= 24) {
                break;
            }
        }
        if (tokens.size() < 24) {
            Matcher chineseMatcher = QUERY_CHINESE_PHRASE_PATTERN.matcher(normalized);
            while (chineseMatcher.find()) {
                String token = chineseMatcher.group();
                if (token.length() > 20) {
                    token = token.substring(0, 20);
                }
                tokens.add(token);
                if (tokens.size() >= 24) {
                    break;
                }
            }
        }
        return List.copyOf(tokens);
    }

    private static List<ScoredPath> scorePaths(
        List<String> files,
        List<String> includeRoots,
        List<String> queryTerms
    ) {
        if (files == null || files.isEmpty()) {
            return List.of();
        }
        List<ScoredPath> scored = new ArrayList<>();
        int remaining = MAX_FILES_TO_SCORE;
        for (String rawPath : files) {
            if (rawPath == null || rawPath.isBlank()) {
                continue;
            }
            String path = normalizePath(rawPath);
            if (!isIncluded(path, includeRoots)) {
                continue;
            }
            int score = 0;
            List<String> reasons = new ArrayList<>();
            if (isImportantWorkspaceFile(path)) {
                score += 1000;
                reasons.add("important");
            }
            if (queryTerms != null) {
                String lowerPath = path.toLowerCase(Locale.ROOT);
                for (String term : queryTerms) {
                    if (term == null || term.isBlank() || term.length() < 3) {
                        continue;
                    }
                    if (lowerPath.contains(term)) {
                        score += 40;
                        if (reasons.size() < 3) {
                            reasons.add("path:" + term);
                        }
                    }
                }
            }
            scored.add(new ScoredPath(path, score, String.join(", ", reasons)));
            remaining--;
            if (remaining <= 0) {
                break;
            }
        }
        scored.sort(Comparator
            .comparingInt(ScoredPath::score).reversed()
            .thenComparing(ScoredPath::path));
        return scored;
    }

    private List<FileExcerpt> buildExcerpts(
        Path effectiveRepoRoot,
        List<ScoredPath> candidates,
        List<String> queryTerms,
        int maxExcerpts,
        int maxExcerptChars,
        int maxTotalChars
    ) {
        if (maxExcerpts <= 0 || candidates == null || candidates.isEmpty()) {
            return List.of();
        }
        List<ScoredCandidate> rescored = new ArrayList<>();
        for (ScoredPath candidate : candidates) {
            if (candidate == null || candidate.path() == null) {
                continue;
            }
            String path = candidate.path();
            int score = candidate.score();
            if (isTextFileCandidate(path)) {
                ContentMatch match = scoreByContent(effectiveRepoRoot, path, queryTerms);
                if (match != null) {
                    score += match.score();
                }
            }
            rescored.add(new ScoredCandidate(path, score));
            if (rescored.size() >= MAX_PATH_CANDIDATES_FOR_CONTENT) {
                break;
            }
        }
        rescored.sort(Comparator
            .comparingInt(ScoredCandidate::score).reversed()
            .thenComparing(ScoredCandidate::path));

        List<FileExcerpt> excerpts = new ArrayList<>();
        int remainingChars = maxTotalChars;
        for (ScoredCandidate candidate : rescored) {
            if (excerpts.size() >= maxExcerpts || remainingChars <= 0) {
                break;
            }
            if (!isTextFileCandidate(candidate.path())) {
                continue;
            }
            String excerpt = excerptRelevantText(
                effectiveRepoRoot,
                candidate.path(),
                queryTerms,
                Math.min(maxExcerptChars, remainingChars)
            );
            if (excerpt.isBlank()) {
                continue;
            }
            excerpts.add(new FileExcerpt(candidate.path(), candidate.score(), excerpt));
            remainingChars -= excerpt.length();
        }
        return List.copyOf(excerpts);
    }

    private ContentMatch scoreByContent(Path effectiveRepoRoot, String relativePath, List<String> queryTerms) {
        if (queryTerms == null || queryTerms.isEmpty()) {
            return new ContentMatch(0);
        }
        Path file = effectiveRepoRoot.resolve(relativePath);
        try {
            if (!Files.exists(file) || !Files.isRegularFile(file)) {
                return new ContentMatch(0);
            }
            String raw = Files.readString(file, StandardCharsets.UTF_8);
            if (raw.isBlank()) {
                return new ContentMatch(0);
            }
            String content = raw.length() > MAX_CONTENT_CHARS_FOR_SCORE ? raw.substring(0, MAX_CONTENT_CHARS_FOR_SCORE) : raw;
            String lower = content.toLowerCase(Locale.ROOT);
            int score = 0;
            for (String term : queryTerms) {
                if (term == null || term.isBlank() || term.length() < 3) {
                    continue;
                }
                int found = 0;
                int from = 0;
                while (true) {
                    int idx = lower.indexOf(term, from);
                    if (idx < 0) {
                        break;
                    }
                    found++;
                    from = idx + term.length();
                    if (found >= 6) {
                        break;
                    }
                }
                if (found > 0) {
                    score += Math.min(40, found * 8);
                }
            }
            return new ContentMatch(score);
        } catch (Exception ex) {
            return new ContentMatch(0);
        }
    }

    private String excerptRelevantText(Path effectiveRepoRoot, String relativePath, List<String> queryTerms, int maxChars) {
        Path file = effectiveRepoRoot.resolve(relativePath);
        try {
            if (!Files.exists(file) || !Files.isRegularFile(file)) {
                return "";
            }
            String raw = Files.readString(file, StandardCharsets.UTF_8).trim();
            if (raw.isBlank()) {
                return "";
            }
            String content = raw.length() > MAX_CONTENT_CHARS_FOR_EXCERPT ? raw.substring(0, MAX_CONTENT_CHARS_FOR_EXCERPT) : raw;
            String lower = content.toLowerCase(Locale.ROOT);
            int bestIndex = -1;
            for (String term : defaultStrings(queryTerms)) {
                if (term == null || term.isBlank() || term.length() < 3) {
                    continue;
                }
                int idx = lower.indexOf(term);
                if (idx < 0) {
                    continue;
                }
                if (bestIndex < 0 || idx < bestIndex) {
                    bestIndex = idx;
                }
            }
            String excerpt;
            if (bestIndex < 0) {
                excerpt = content;
            } else {
                int start = Math.max(0, bestIndex - 220);
                int end = Math.min(content.length(), start + Math.max(400, maxChars));
                excerpt = content.substring(start, end);
                if (start > 0) {
                    excerpt = "... " + excerpt;
                }
                if (end < content.length()) {
                    excerpt = excerpt + " ...";
                }
            }
            if (excerpt.length() > maxChars) {
                return excerpt.substring(0, maxChars);
            }
            return excerpt;
        } catch (Exception ex) {
            return "";
        }
    }

    private static boolean isIncluded(String relativePath, List<String> includeRoots) {
        if (includeRoots == null || includeRoots.isEmpty()) {
            return true;
        }
        for (String root : includeRoots) {
            if (root == null || root.isBlank() || "./".equals(root)) {
                return true;
            }
            String normalizedRoot = normalizePath(root);
            if (normalizedRoot.isBlank() || "./".equals(normalizedRoot)) {
                return true;
            }
            if (relativePath.startsWith(normalizedRoot)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isIgnoredPath(String relativePath) {
        String normalizedPath = normalizePath(relativePath).toLowerCase(Locale.ROOT);
        return normalizedPath.startsWith(".git/")
            || normalizedPath.startsWith(".agentx/")
            || normalizedPath.startsWith("target/")
            || normalizedPath.startsWith("node_modules/")
            || normalizedPath.startsWith("dist/")
            || normalizedPath.startsWith("build/")
            || normalizedPath.startsWith("worktrees/")
            || normalizedPath.startsWith("runtime-data/")
            || normalizedPath.startsWith("runtime-projects/")
            || normalizedPath.startsWith(".idea/")
            || normalizedPath.startsWith(".vscode/")
            || normalizedPath.equals(".env")
            || normalizedPath.startsWith(".env.")
            || normalizedPath.endsWith(".pem")
            || normalizedPath.endsWith(".p12")
            || normalizedPath.endsWith(".pfx")
            || normalizedPath.endsWith(".key");
    }

    private static boolean isImportantWorkspaceFile(String relativePath) {
        String normalized = normalizePath(relativePath);
        return "pom.xml".equals(normalized)
            || "build.gradle".equals(normalized)
            || "settings.gradle".equals(normalized)
            || "package.json".equals(normalized)
            || "README.md".equalsIgnoreCase(normalized)
            || normalized.startsWith("docs/")
            || normalized.startsWith("src/main/")
            || normalized.startsWith("src/test/");
    }

    private static boolean isTextFileCandidate(String relativePath) {
        String normalized = normalizePath(relativePath).toLowerCase(Locale.ROOT);
        return normalized.endsWith(".java")
            || normalized.endsWith(".kt")
            || normalized.endsWith(".xml")
            || normalized.endsWith(".yml")
            || normalized.endsWith(".yaml")
            || normalized.endsWith(".properties")
            || normalized.endsWith(".md")
            || normalized.endsWith(".txt")
            || normalized.endsWith(".json")
            || normalized.endsWith(".gradle")
            || normalized.endsWith(".kts")
            || "pom.xml".equals(normalized)
            || "docker-compose.yml".equals(normalized);
    }

    private static List<String> defaultStrings(List<String> value) {
        return value == null ? List.of() : value;
    }

    private static int cap(int value, int min, int max, int fallback) {
        if (value <= 0) {
            return fallback;
        }
        return Math.max(min, Math.min(max, value));
    }

    private static String safeError(Exception ex) {
        if (ex == null) {
            return "unknown";
        }
        String message = ex.getMessage();
        if (message == null || message.isBlank()) {
            return ex.getClass().getSimpleName();
        }
        String trimmed = message.trim();
        return trimmed.length() > 120 ? trimmed.substring(0, 120) : trimmed;
    }

    private static String normalizePath(String raw) {
        if (raw == null) {
            return "";
        }
        String normalized = raw.replace('\\', '/').trim();
        while (normalized.startsWith("./")) {
            normalized = normalized.substring(2);
        }
        while (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        return normalized;
    }

    private record RepoIndex(
        String indexKind,
        String repoRoot,
        String repoHeadRef,
        Instant builtAt,
        List<String> topLevelEntries,
        List<String> files,
        List<String> warnings
    ) {
        boolean isFresh(Duration ttl) {
            if (builtAt == null || ttl == null) {
                return false;
            }
            return builtAt.plus(ttl).isAfter(Instant.now());
        }

        boolean matchesRepo(String currentHeadRef) {
            if (currentHeadRef == null || currentHeadRef.isBlank()) {
                return false;
            }
            return Objects.equals(repoHeadRef, currentHeadRef);
        }
    }

    private record QueryTarget(Path effectiveRepoRoot, List<String> localIncludeRoots) {
    }

    private record ScoredCandidate(String path, int score) {
    }

    private record ContentMatch(int score) {
    }
}
