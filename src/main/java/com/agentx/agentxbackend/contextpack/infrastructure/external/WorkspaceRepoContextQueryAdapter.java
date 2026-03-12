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
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
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

    private volatile RepoIndex cachedIndex;

    public WorkspaceRepoContextQueryAdapter(
        @Value("${agentx.workspace.git.executable:git}") String gitExecutable,
        @Value("${agentx.workspace.git.repo-root:.}") String repoRoot,
        @Value("${agentx.contextpack.repo-context.index-ttl-seconds:600}") long indexTtlSeconds
    ) {
        this.gitExecutable = (gitExecutable == null || gitExecutable.isBlank()) ? "git" : gitExecutable.trim();
        this.repoRoot = Path.of(repoRoot == null || repoRoot.isBlank() ? "." : repoRoot.trim())
            .toAbsolutePath()
            .normalize();
        long cappedSeconds = Math.max(30L, Math.min(3600L, indexTtlSeconds));
        this.indexTtl = Duration.ofSeconds(cappedSeconds);
    }

    @Override
    public RepoContext query(RepoContextQuery query) {
        if (query == null) {
            throw new IllegalArgumentException("query must not be null");
        }
        String queryText = query.queryText() == null ? "" : query.queryText().trim();
        if (queryText.isBlank()) {
            return new RepoContext(
                "lexical_v1",
                repoRoot.toString().replace('\\', '/'),
                resolveRepoHeadRef().orElse("git:HEAD_UNKNOWN"),
                List.of(),
                buildTopLevelEntries(repoRoot),
                List.of(),
                List.of(),
                List.of("empty_query_text")
            );
        }

        RepoIndex index = resolveIndex();
        List<String> queryTerms = extractQueryTerms(queryText);
        List<String> includeRoots = normalizeIncludeRoots(query.includeRoots());
        int maxFiles = cap(query.maxFiles(), 1, 60, 24);
        int maxExcerpts = cap(query.maxExcerpts(), 0, 20, 6);
        int maxExcerptChars = cap(query.maxExcerptChars(), 200, 2400, 900);
        int maxTotalExcerptChars = cap(query.maxTotalExcerptChars(), 500, 20_000, 6_000);

        List<RepoContextQueryPort.ScoredPath> scored = scorePaths(index.files(), includeRoots, queryTerms);
        if (scored.isEmpty()) {
            return new RepoContext(
                index.indexKind(),
                index.repoRoot(),
                index.repoHeadRef(),
                queryTerms,
                index.topLevelEntries(),
                List.of(),
                List.of(),
                List.of("no_visible_files")
            );
        }

        List<RepoContextQueryPort.ScoredPath> topFiles = scored.subList(0, Math.min(maxFiles, scored.size()));
        List<RepoContextQueryPort.FileExcerpt> excerpts = buildExcerpts(
            topFiles,
            queryTerms,
            maxExcerpts,
            maxExcerptChars,
            maxTotalExcerptChars
        );

        return new RepoContext(
            index.indexKind(),
            index.repoRoot(),
            index.repoHeadRef(),
            queryTerms,
            index.topLevelEntries(),
            topFiles,
            excerpts,
            index.warnings()
        );
    }

    private RepoIndex resolveIndex() {
        RepoIndex current = cachedIndex;
        if (current != null && current.isFresh(indexTtl) && current.matchesRepo(resolveRepoHeadRef().orElse(null))) {
            return current;
        }
        synchronized (this) {
            RepoIndex refreshed = cachedIndex;
            if (refreshed != null && refreshed.isFresh(indexTtl) && refreshed.matchesRepo(resolveRepoHeadRef().orElse(null))) {
                return refreshed;
            }
            RepoIndex rebuilt = buildIndex();
            cachedIndex = rebuilt;
            return rebuilt;
        }
    }

    private RepoIndex buildIndex() {
        String repoHeadRef = resolveRepoHeadRef().orElse("git:HEAD_UNKNOWN");
        List<String> warnings = new ArrayList<>();
        List<String> topLevel = buildTopLevelEntries(repoRoot);
        if (!Files.exists(repoRoot)) {
            warnings.add("repo_root_missing:" + repoRoot);
        }

        List<String> files = new ArrayList<>();
        try (Stream<Path> stream = Files.walk(repoRoot)) {
            files = stream
                .filter(Files::isRegularFile)
                .map(path -> normalizePath(repoRoot.relativize(path).toString()))
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
            repoRoot.toString().replace('\\', '/'),
            repoHeadRef,
            Instant.now(),
            topLevel,
            files,
            List.copyOf(warnings)
        );
    }

    private Optional<String> resolveRepoHeadRef() {
        Path gitDir = repoRoot.resolve(".git");
        if (!Files.exists(gitDir)) {
            return Optional.empty();
        }
        try {
            ProcessBuilder pb = new ProcessBuilder(List.of(gitExecutable, "rev-parse", "HEAD"));
            pb.directory(repoRoot.toFile());
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

    private static List<String> buildTopLevelEntries(Path repoRoot) {
        if (repoRoot == null || !Files.isDirectory(repoRoot)) {
            return List.of();
        }
        List<String> entries = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(repoRoot)) {
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
            return List.of();
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
            normalized.add(value);
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

    private static List<RepoContextQueryPort.ScoredPath> scorePaths(
        List<String> files,
        List<String> includeRoots,
        List<String> queryTerms
    ) {
        if (files == null || files.isEmpty()) {
            return List.of();
        }
        List<RepoContextQueryPort.ScoredPath> scored = new ArrayList<>();
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
            scored.add(new RepoContextQueryPort.ScoredPath(path, score, String.join(", ", reasons)));
            remaining--;
            if (remaining <= 0) {
                break;
            }
        }
        scored.sort(Comparator
            .comparingInt(RepoContextQueryPort.ScoredPath::score).reversed()
            .thenComparing(RepoContextQueryPort.ScoredPath::path));
        return scored;
    }

    private List<RepoContextQueryPort.FileExcerpt> buildExcerpts(
        List<RepoContextQueryPort.ScoredPath> candidates,
        List<String> queryTerms,
        int maxExcerpts,
        int maxExcerptChars,
        int maxTotalChars
    ) {
        if (maxExcerpts <= 0 || candidates == null || candidates.isEmpty()) {
            return List.of();
        }
        List<ScoredCandidate> rescored = new ArrayList<>();
        for (RepoContextQueryPort.ScoredPath candidate : candidates) {
            if (candidate == null || candidate.path() == null) {
                continue;
            }
            String path = candidate.path();
            int score = candidate.score();
            if (isTextFileCandidate(path)) {
                ContentMatch match = scoreByContent(path, queryTerms);
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

        List<RepoContextQueryPort.FileExcerpt> excerpts = new ArrayList<>();
        int remainingChars = maxTotalChars;
        for (ScoredCandidate candidate : rescored) {
            if (excerpts.size() >= maxExcerpts || remainingChars <= 0) {
                break;
            }
            if (!isTextFileCandidate(candidate.path())) {
                continue;
            }
            String excerpt = excerptRelevantText(candidate.path(), queryTerms, Math.min(maxExcerptChars, remainingChars));
            if (excerpt.isBlank()) {
                continue;
            }
            excerpts.add(new RepoContextQueryPort.FileExcerpt(candidate.path(), candidate.score(), excerpt));
            remainingChars -= excerpt.length();
        }
        return List.copyOf(excerpts);
    }

    private ContentMatch scoreByContent(String relativePath, List<String> queryTerms) {
        if (queryTerms == null || queryTerms.isEmpty()) {
            return new ContentMatch(0);
        }
        Path file = repoRoot.resolve(relativePath);
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

    private String excerptRelevantText(String relativePath, List<String> queryTerms, int maxChars) {
        Path file = repoRoot.resolve(relativePath);
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
            for (String term : defaultList(queryTerms)) {
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

    private static List<String> defaultList(List<String> value) {
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
        return raw.replace('\\', '/').trim();
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

    private record ScoredCandidate(String path, int score) {
    }

    private record ContentMatch(int score) {
    }
}
