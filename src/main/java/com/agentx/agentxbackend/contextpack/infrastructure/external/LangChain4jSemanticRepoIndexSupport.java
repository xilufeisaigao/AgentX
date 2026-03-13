package com.agentx.agentxbackend.contextpack.infrastructure.external;

import com.agentx.agentxbackend.contextpack.application.port.out.RepoContextQueryPort;
import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.document.splitter.DocumentSplitters;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingSearchResult;
import dev.langchain4j.store.embedding.EmbeddingStoreIngestor;
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

final class LangChain4jSemanticRepoIndexSupport {

    private static final Logger log = LoggerFactory.getLogger(LangChain4jSemanticRepoIndexSupport.class);
    private static final String INDEX_KIND = "langchain4j_semantic_v1";

    private final SemanticConfig config;
    private final EmbeddingModelFactory embeddingModelFactory;
    private final ConcurrentHashMap<String, SemanticIndex> cachedIndexes = new ConcurrentHashMap<>();

    LangChain4jSemanticRepoIndexSupport(SemanticConfig config) {
        this(config, LangChain4jSemanticRepoIndexSupport::buildDefaultEmbeddingModel);
    }

    LangChain4jSemanticRepoIndexSupport(
        SemanticConfig config,
        EmbeddingModelFactory embeddingModelFactory
    ) {
        this.config = config == null ? SemanticConfig.disabled() : config;
        this.embeddingModelFactory = embeddingModelFactory == null
            ? LangChain4jSemanticRepoIndexSupport::buildDefaultEmbeddingModel
            : embeddingModelFactory;
    }

    boolean isEnabled() {
        return config.enabled();
    }

    SemanticQueryResult query(
        Path effectiveRepoRoot,
        String repoHeadRef,
        List<String> indexedFiles,
        String queryText,
        List<String> includeRoots,
        int maxFiles,
        int maxExcerpts,
        int maxExcerptChars,
        int maxTotalExcerptChars
    ) {
        if (!config.enabled()) {
            return SemanticQueryResult.disabled("semantic_disabled");
        }
        if (queryText == null || queryText.isBlank()) {
            return SemanticQueryResult.empty(List.of("semantic_empty_query"));
        }
        if (effectiveRepoRoot == null) {
            return SemanticQueryResult.empty(List.of("semantic_missing_repo_root"));
        }
        if (config.baseUrl().isBlank()) {
            return SemanticQueryResult.empty(List.of("semantic_missing_base_url"));
        }
        if (config.apiKey().isBlank()) {
            return SemanticQueryResult.empty(List.of("semantic_missing_api_key"));
        }
        if (config.model().isBlank()) {
            return SemanticQueryResult.empty(List.of("semantic_missing_model"));
        }

        try {
            SemanticIndex index = resolveIndex(effectiveRepoRoot, repoHeadRef, indexedFiles);
            if (index.store().isEmpty()) {
                return SemanticQueryResult.empty(mergeWarnings(index.warnings(), List.of("semantic_index_empty")));
            }
            Embedding queryEmbedding = index.embeddingModel().embed(queryText).content();
            int searchResultLimit = Math.max(Math.max(maxFiles * 3, maxExcerpts * 4), 8);
            searchResultLimit = Math.min(searchResultLimit, config.maxSearchResults());

            EmbeddingSearchResult<TextSegment> searchResult = index.store().search(
                EmbeddingSearchRequest.builder()
                    .query(queryText)
                    .queryEmbedding(queryEmbedding)
                    .maxResults(searchResultLimit)
                    .minScore(config.minScore())
                    .build()
            );

            if (searchResult == null || searchResult.matches() == null || searchResult.matches().isEmpty()) {
                return SemanticQueryResult.empty(index.warnings());
            }

            List<RepoContextQueryPort.FileExcerpt> excerpts = buildExcerpts(
                searchResult.matches(),
                includeRoots,
                maxExcerpts,
                maxExcerptChars,
                maxTotalExcerptChars
            );
            List<RepoContextQueryPort.ScoredPath> relevantFiles = buildRelevantFiles(
                searchResult.matches(),
                includeRoots,
                maxFiles
            );
            return new SemanticQueryResult(INDEX_KIND, relevantFiles, excerpts, index.warnings());
        } catch (RuntimeException ex) {
            log.warn(
                "Semantic repo query failed, repoRoot={}, cause={}",
                effectiveRepoRoot,
                ex.getMessage()
            );
            return SemanticQueryResult.empty(List.of("semantic_query_failed:" + safeError(ex)));
        }
    }

    private SemanticIndex resolveIndex(
        Path effectiveRepoRoot,
        String repoHeadRef,
        List<String> indexedFiles
    ) {
        String cacheKey = effectiveRepoRoot.toAbsolutePath().normalize().toString();
        SemanticIndex current = cachedIndexes.get(cacheKey);
        if (current != null && current.isFresh(config.indexTtl()) && current.matchesRepoHead(repoHeadRef)) {
            return current;
        }
        synchronized (cachedIndexes) {
            SemanticIndex refreshed = cachedIndexes.get(cacheKey);
            if (refreshed != null && refreshed.isFresh(config.indexTtl()) && refreshed.matchesRepoHead(repoHeadRef)) {
                return refreshed;
            }
            SemanticIndex rebuilt = buildIndex(effectiveRepoRoot, repoHeadRef, indexedFiles);
            cachedIndexes.put(cacheKey, rebuilt);
            return rebuilt;
        }
    }

    private SemanticIndex buildIndex(
        Path effectiveRepoRoot,
        String repoHeadRef,
        List<String> indexedFiles
    ) {
        List<Document> documents = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        int indexedFileCount = 0;
        int truncatedFileCount = 0;
        int totalChars = 0;

        for (String rawPath : defaultList(indexedFiles)) {
            if (rawPath == null || rawPath.isBlank() || !isTextFileCandidate(rawPath)) {
                continue;
            }
            if (indexedFileCount >= config.maxIndexedFiles()) {
                warnings.add("semantic_index_file_cap:" + config.maxIndexedFiles());
                break;
            }
            Path file = effectiveRepoRoot.resolve(rawPath).normalize();
            if (!file.startsWith(effectiveRepoRoot) || !Files.exists(file) || !Files.isRegularFile(file)) {
                continue;
            }
            String content;
            try {
                content = Files.readString(file, StandardCharsets.UTF_8);
            } catch (Exception ex) {
                continue;
            }
            content = content == null ? "" : content.trim();
            if (content.isBlank()) {
                continue;
            }
            if (content.length() > config.maxFileChars()) {
                content = content.substring(0, config.maxFileChars());
                truncatedFileCount++;
            }
            if (totalChars >= config.maxTotalChars()) {
                warnings.add("semantic_index_char_cap:" + config.maxTotalChars());
                break;
            }
            int remainingChars = config.maxTotalChars() - totalChars;
            if (content.length() > remainingChars) {
                content = content.substring(0, remainingChars);
                truncatedFileCount++;
            }
            Metadata metadata = new Metadata().put("path", normalizePath(rawPath));
            documents.add(Document.from(content, metadata));
            totalChars += content.length();
            indexedFileCount++;
        }

        if (documents.isEmpty()) {
            warnings.add("semantic_index_no_documents");
            return new SemanticIndex(
                effectiveRepoRoot.toAbsolutePath().normalize(),
                normalizeRepoHeadRef(repoHeadRef),
                Instant.now(),
                new InMemoryEmbeddingStore<>(),
                embeddingModelFactory.create(config),
                List.copyOf(new LinkedHashSet<>(warnings))
            );
        }

        EmbeddingModel embeddingModel = embeddingModelFactory.create(config);
        InMemoryEmbeddingStore<TextSegment> store = new InMemoryEmbeddingStore<>();
        EmbeddingStoreIngestor.builder()
            .documentSplitter(DocumentSplitters.recursive(config.segmentChars(), config.segmentOverlapChars()))
            .embeddingModel(embeddingModel)
            .embeddingStore(store)
            .build()
            .ingest(documents);

        warnings.add("semantic_indexed_files:" + indexedFileCount);
        warnings.add("semantic_index_total_chars:" + totalChars);
        if (truncatedFileCount > 0) {
            warnings.add("semantic_index_truncated_files:" + truncatedFileCount);
        }
        return new SemanticIndex(
            effectiveRepoRoot.toAbsolutePath().normalize(),
            normalizeRepoHeadRef(repoHeadRef),
            Instant.now(),
            store,
            embeddingModel,
            List.copyOf(new LinkedHashSet<>(warnings))
        );
    }

    private static List<RepoContextQueryPort.ScoredPath> buildRelevantFiles(
        List<EmbeddingMatch<TextSegment>> matches,
        List<String> includeRoots,
        int maxFiles
    ) {
        Map<String, FileAggregate> aggregates = new LinkedHashMap<>();
        for (EmbeddingMatch<TextSegment> match : defaultMatches(matches)) {
            if (match == null || match.embedded() == null) {
                continue;
            }
            String path = readSegmentPath(match.embedded());
            if (path == null || !isIncluded(path, includeRoots)) {
                continue;
            }
            FileAggregate aggregate = aggregates.computeIfAbsent(path, ignored -> new FileAggregate(path));
            aggregate.record(match.score());
        }
        List<FileAggregate> ordered = new ArrayList<>(aggregates.values());
        ordered.sort(FileAggregate::compareForResult);

        List<RepoContextQueryPort.ScoredPath> relevantFiles = new ArrayList<>();
        for (FileAggregate aggregate : ordered) {
            if (relevantFiles.size() >= maxFiles) {
                break;
            }
            relevantFiles.add(
                new RepoContextQueryPort.ScoredPath(
                    aggregate.path(),
                    aggregate.scoreInt(),
                    aggregate.reason()
                )
            );
        }
        return List.copyOf(relevantFiles);
    }

    private static List<RepoContextQueryPort.FileExcerpt> buildExcerpts(
        List<EmbeddingMatch<TextSegment>> matches,
        List<String> includeRoots,
        int maxExcerpts,
        int maxExcerptChars,
        int maxTotalExcerptChars
    ) {
        List<RepoContextQueryPort.FileExcerpt> excerpts = new ArrayList<>();
        LinkedHashSet<String> dedupKeys = new LinkedHashSet<>();
        int remainingChars = maxTotalExcerptChars;
        for (EmbeddingMatch<TextSegment> match : defaultMatches(matches)) {
            if (excerpts.size() >= maxExcerpts || remainingChars <= 0) {
                break;
            }
            if (match == null || match.embedded() == null) {
                continue;
            }
            TextSegment segment = match.embedded();
            String path = readSegmentPath(segment);
            if (path == null || !isIncluded(path, includeRoots)) {
                continue;
            }
            String excerpt = normalizeExcerpt(segment.text(), Math.min(maxExcerptChars, remainingChars));
            if (excerpt.isBlank()) {
                continue;
            }
            String dedupKey = path + "|" + excerpt.hashCode();
            if (!dedupKeys.add(dedupKey)) {
                continue;
            }
            excerpts.add(
                new RepoContextQueryPort.FileExcerpt(
                    path,
                    toScoreInt(match.score()),
                    excerpt
                )
            );
            remainingChars -= excerpt.length();
        }
        return List.copyOf(excerpts);
    }

    private static String readSegmentPath(TextSegment segment) {
        if (segment == null || segment.metadata() == null) {
            return null;
        }
        String path = segment.metadata().getString("path");
        if (path == null || path.isBlank()) {
            return null;
        }
        return normalizePath(path);
    }

    private static String normalizeExcerpt(String raw, int maxChars) {
        if (raw == null || raw.isBlank() || maxChars <= 0) {
            return "";
        }
        String normalized = raw.replaceAll("\\s+", " ").trim();
        if (normalized.length() <= maxChars) {
            return normalized;
        }
        return normalized.substring(0, maxChars);
    }

    private static EmbeddingModel buildDefaultEmbeddingModel(SemanticConfig config) {
        return OpenAiEmbeddingModel.builder()
            .baseUrl(config.baseUrl())
            .apiKey(config.apiKey())
            .modelName(config.model())
            .timeout(Duration.ofMillis(config.timeoutMs()))
            .build();
    }

    private static List<String> mergeWarnings(List<String> baseWarnings, List<String> extraWarnings) {
        LinkedHashSet<String> merged = new LinkedHashSet<>(defaultList(baseWarnings));
        merged.addAll(defaultList(extraWarnings));
        return List.copyOf(merged);
    }

    private static List<String> defaultList(List<String> values) {
        return values == null ? List.of() : values;
    }

    private static List<EmbeddingMatch<TextSegment>> defaultMatches(List<EmbeddingMatch<TextSegment>> values) {
        return values == null ? List.of() : values;
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

    private static int toScoreInt(Double score) {
        if (score == null) {
            return 0;
        }
        return (int) Math.max(0, Math.min(1000, Math.round(score * 1000)));
    }

    private static String normalizeRepoHeadRef(String repoHeadRef) {
        if (repoHeadRef == null || repoHeadRef.isBlank()) {
            return "git:HEAD_UNKNOWN";
        }
        return repoHeadRef.trim();
    }

    private static String safeError(RuntimeException ex) {
        if (ex == null || ex.getMessage() == null || ex.getMessage().isBlank()) {
            return ex == null ? "unknown" : ex.getClass().getSimpleName();
        }
        String trimmed = ex.getMessage().trim();
        return trimmed.length() <= 120 ? trimmed : trimmed.substring(0, 120);
    }

    private static String normalizePath(String raw) {
        if (raw == null) {
            return "";
        }
        String normalized = raw.replace('\\', '/').trim();
        while (normalized.startsWith("./")) {
            normalized = normalized.substring(2);
        }
        return normalized;
    }

    record SemanticConfig(
        boolean enabled,
        String baseUrl,
        String apiKey,
        String model,
        long timeoutMs,
        long indexTtlSeconds,
        int maxIndexedFiles,
        int maxFileChars,
        int maxTotalChars,
        int segmentChars,
        int segmentOverlapChars,
        int maxSearchResults,
        double minScore
    ) {
        SemanticConfig {
            baseUrl = baseUrl == null ? "" : baseUrl.trim();
            apiKey = apiKey == null ? "" : apiKey.trim();
            model = model == null ? "" : model.trim();
            timeoutMs = Math.max(1_000L, timeoutMs);
            indexTtlSeconds = Math.max(30L, Math.min(3_600L, indexTtlSeconds));
            maxIndexedFiles = Math.max(1, maxIndexedFiles);
            maxFileChars = Math.max(500, maxFileChars);
            maxTotalChars = Math.max(maxFileChars, maxTotalChars);
            segmentChars = Math.max(200, segmentChars);
            segmentOverlapChars = Math.max(0, Math.min(segmentChars / 2, segmentOverlapChars));
            maxSearchResults = Math.max(4, maxSearchResults);
            minScore = Math.max(0.0d, Math.min(1.0d, minScore));
        }

        static SemanticConfig disabled() {
            return new SemanticConfig(false, "", "", "", 60_000L, 600L, 1, 1_000, 1_000, 500, 50, 8, 0.0d);
        }

        Duration indexTtl() {
            return Duration.ofSeconds(indexTtlSeconds);
        }
    }

    record SemanticQueryResult(
        String indexKind,
        List<RepoContextQueryPort.ScoredPath> relevantFiles,
        List<RepoContextQueryPort.FileExcerpt> excerpts,
        List<String> warnings
    ) {

        static SemanticQueryResult empty(List<String> warnings) {
            return new SemanticQueryResult(
                INDEX_KIND,
                List.of(),
                List.of(),
                warnings == null ? List.of() : List.copyOf(new LinkedHashSet<>(warnings))
            );
        }

        static SemanticQueryResult disabled(String warning) {
            return empty(List.of(warning));
        }

        boolean hasMatches() {
            return (relevantFiles != null && !relevantFiles.isEmpty())
                || (excerpts != null && !excerpts.isEmpty());
        }
    }

    interface EmbeddingModelFactory {

        EmbeddingModel create(SemanticConfig config);
    }

    private record SemanticIndex(
        Path repoRoot,
        String repoHeadRef,
        Instant builtAt,
        InMemoryEmbeddingStore<TextSegment> store,
        EmbeddingModel embeddingModel,
        List<String> warnings
    ) {

        boolean isFresh(Duration ttl) {
            return builtAt != null && ttl != null && builtAt.plus(ttl).isAfter(Instant.now());
        }

        boolean matchesRepoHead(String currentRepoHeadRef) {
            return normalizeRepoHeadRef(repoHeadRef).equals(normalizeRepoHeadRef(currentRepoHeadRef));
        }
    }

    private static final class FileAggregate {

        private final String path;
        private double maxScore;
        private double totalScore;
        private int chunkCount;

        private FileAggregate(String path) {
            this.path = path;
        }

        private void record(Double score) {
            double normalized = score == null ? 0.0d : score;
            maxScore = Math.max(maxScore, normalized);
            totalScore += normalized;
            chunkCount++;
        }

        private String path() {
            return path;
        }

        private int scoreInt() {
            return toScoreInt(maxScore);
        }

        private String reason() {
            return "semantic:max=" + String.format(Locale.ROOT, "%.3f", maxScore)
                + ",chunks=" + chunkCount;
        }

        private static int compareForResult(FileAggregate left, FileAggregate right) {
            int maxScoreCompare = Double.compare(right.maxScore, left.maxScore);
            if (maxScoreCompare != 0) {
                return maxScoreCompare;
            }
            int totalScoreCompare = Double.compare(right.totalScore, left.totalScore);
            if (totalScoreCompare != 0) {
                return totalScoreCompare;
            }
            int chunkCountCompare = Integer.compare(right.chunkCount, left.chunkCount);
            if (chunkCountCompare != 0) {
                return chunkCountCompare;
            }
            return left.path.compareTo(right.path);
        }
    }
}
