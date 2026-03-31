package com.agentx.platform.runtime.retrieval;

import com.agentx.platform.runtime.context.RetrievalSnippet;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Component
public class DefaultLexicalChunkRetriever implements LexicalChunkRetriever {

    @Override
    public List<RetrievalSnippet> retrieve(List<IndexedChunk> chunks, RetrievalQuery query, int limit) {
        List<ScoredChunk> scoredChunks = new ArrayList<>();
        for (IndexedChunk chunk : chunks) {
            double score = score(chunk, query);
            if (score <= 0) {
                continue;
            }
            scoredChunks.add(new ScoredChunk(chunk, score));
        }
        scoredChunks.sort(Comparator.comparingDouble(ScoredChunk::score).reversed());
        return scoredChunks.stream()
                .limit(limit)
                .map(scored -> new RetrievalSnippet(
                        scored.chunk().chunkId(),
                        scored.chunk().sourceType(),
                        scored.chunk().sourceRef(),
                        scored.chunk().relativePath() + ":" + scored.chunk().startLine(),
                        excerpt(scored.chunk().text()),
                        scored.score(),
                        scored.chunk().symbols(),
                        metadata(scored.chunk())
                ))
                .toList();
    }

    private double score(IndexedChunk chunk, RetrievalQuery query) {
        String haystack = (chunk.relativePath() + "\n" + chunk.text() + "\n" + String.join(" ", chunk.symbols()))
                .toLowerCase(Locale.ROOT);
        double score = chunk.overlay() ? 3.0 : 0.0;
        for (String term : query.terms()) {
            if (term.isBlank()) {
                continue;
            }
            String normalized = term.toLowerCase(Locale.ROOT);
            if (haystack.contains(normalized)) {
                score += 2.0;
            }
            if (chunk.relativePath().toLowerCase(Locale.ROOT).contains(normalized)) {
                score += 3.0;
            }
            if (chunk.symbols().stream().map(symbol -> symbol.toLowerCase(Locale.ROOT)).anyMatch(symbol -> symbol.contains(normalized))) {
                score += 4.0;
            }
        }
        for (String preferredPath : query.preferredPaths()) {
            if (!preferredPath.isBlank() && chunk.relativePath().replace('\\', '/').contains(preferredPath.replace('\\', '/'))) {
                score += 4.0;
            }
        }
        return score;
    }

    private Map<String, Object> metadata(IndexedChunk chunk) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("relativePath", chunk.relativePath());
        metadata.put("startLine", chunk.startLine());
        metadata.put("endLine", chunk.endLine());
        metadata.put("overlay", chunk.overlay());
        return metadata;
    }

    private String excerpt(String text) {
        return text.length() <= 1_200 ? text : text.substring(0, 1_200);
    }

    private record ScoredChunk(IndexedChunk chunk, double score) {
    }
}
