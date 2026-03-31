package com.agentx.platform.runtime.context;

import com.agentx.platform.runtime.retrieval.FactRetriever;
import com.agentx.platform.runtime.retrieval.IndexedChunk;
import com.agentx.platform.runtime.retrieval.LexicalChunkRetriever;
import com.agentx.platform.runtime.retrieval.RepoIndexManifest;
import com.agentx.platform.runtime.retrieval.RepoIndexService;
import com.agentx.platform.runtime.retrieval.RetrievalProperties;
import com.agentx.platform.runtime.retrieval.RetrievalQuery;
import com.agentx.platform.runtime.retrieval.RetrievalQueryPlanner;
import com.agentx.platform.runtime.retrieval.WorkflowOverlayIndexService;
import com.agentx.platform.runtime.evaluation.WorkflowEvalTraceCollector;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class DefaultContextCompilationCenter implements ContextCompilationCenter {

    private final ContextCompilationProperties contextProperties;
    private final RetrievalProperties retrievalProperties;
    private final FactRetriever factRetriever;
    private final RepoIndexService repoIndexService;
    private final WorkflowOverlayIndexService workflowOverlayIndexService;
    private final RetrievalQueryPlanner retrievalQueryPlanner;
    private final LexicalChunkRetriever lexicalChunkRetriever;
    private final ObjectMapper objectMapper;
    private final WorkflowEvalTraceCollector workflowEvalTraceCollector;

    public DefaultContextCompilationCenter(
            ContextCompilationProperties contextProperties,
            RetrievalProperties retrievalProperties,
            FactRetriever factRetriever,
            RepoIndexService repoIndexService,
            WorkflowOverlayIndexService workflowOverlayIndexService,
            RetrievalQueryPlanner retrievalQueryPlanner,
            LexicalChunkRetriever lexicalChunkRetriever,
            ObjectMapper objectMapper,
            WorkflowEvalTraceCollector workflowEvalTraceCollector
    ) {
        this.contextProperties = contextProperties;
        this.retrievalProperties = retrievalProperties;
        this.factRetriever = factRetriever;
        this.repoIndexService = repoIndexService;
        this.workflowOverlayIndexService = workflowOverlayIndexService;
        this.retrievalQueryPlanner = retrievalQueryPlanner;
        this.lexicalChunkRetriever = lexicalChunkRetriever;
        this.objectMapper = objectMapper;
        this.workflowEvalTraceCollector = workflowEvalTraceCollector;
    }

    @Override
    public CompiledContextPack compile(ContextCompilationRequest request) {
        FactBundle factBundle = factRetriever.retrieve(request);
        RetrievalBundle retrievalBundle = retrievalBundle(request, factBundle);
        LocalDateTime compiledAt = LocalDateTime.now();
        String fingerprint = fingerprint(request, factBundle, retrievalBundle);
        String contentJson = serializedPayload(request, factBundle, retrievalBundle, fingerprint, compiledAt);
        String trimmedContentJson = trimIfNeeded(contentJson, retrievalBundle, request, factBundle, fingerprint, compiledAt);
        Path artifactPath = writeArtifact(request, trimmedContentJson);
        CompiledContextPack contextPack = new CompiledContextPack(
                request.packType(),
                request.scope(),
                fingerprint,
                artifactPath.toString(),
                trimmedContentJson,
                factBundle,
                retrievalBundle,
                compiledAt
        );
        workflowEvalTraceCollector.recordContextPack(contextPack);
        return contextPack;
    }

    private RetrievalBundle retrievalBundle(ContextCompilationRequest request, FactBundle factBundle) {
        if (request.packType() == ContextPackType.REQUIREMENT) {
            return new RetrievalBundle(List.of());
        }
        RetrievalQuery query = retrievalQueryPlanner.plan(request, factBundle);
        List<IndexedChunk> candidateChunks = new ArrayList<>();
        RepoIndexManifest baseManifest = repoIndexService.buildBaseIndex();
        candidateChunks.addAll(baseManifest.chunks());
        if (request.scope().overlayRoot() != null && Files.exists(request.scope().overlayRoot())) {
            candidateChunks.addAll(0, workflowOverlayIndexService.buildOverlayIndex(
                    request.scope().workflowRunId(),
                    request.scope().taskId() == null ? "workflow" : request.scope().taskId(),
                    request.scope().overlayRoot()
            ).chunks());
        }
        return new RetrievalBundle(lexicalChunkRetriever.retrieve(candidateChunks, query, retrievalProperties.getTopK()));
    }

    private String serializedPayload(
            ContextCompilationRequest request,
            FactBundle factBundle,
            RetrievalBundle retrievalBundle,
            String fingerprint,
            LocalDateTime compiledAt
    ) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("packType", request.packType().name());
        payload.put("scope", request.scope());
        payload.put("triggerType", request.triggerType());
        payload.put("sourceFingerprint", fingerprint);
        payload.put("compiledAt", compiledAt);
        payload.put("facts", factBundle.sections());
        payload.put("retrieval", retrievalBundle.snippets());
        try {
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(payload);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("failed to serialize compiled context pack", exception);
        }
    }

    private String trimIfNeeded(
            String contentJson,
            RetrievalBundle retrievalBundle,
            ContextCompilationRequest request,
            FactBundle factBundle,
            String fingerprint,
            LocalDateTime compiledAt
    ) {
        if (contentJson.length() <= contextProperties.getMaxPackSize()) {
            return contentJson;
        }
        List<RetrievalSnippet> snippets = new ArrayList<>(retrievalBundle.snippets());
        while (!snippets.isEmpty()) {
            snippets.remove(snippets.size() - 1);
            String trimmed = serializedPayload(request, factBundle, new RetrievalBundle(snippets), fingerprint, compiledAt);
            if (trimmed.length() <= contextProperties.getMaxPackSize()) {
                return trimmed;
            }
        }
        // An oversized but valid JSON pack is still more useful than a truncated string
        // that silently removes contract facts or breaks the model-visible payload.
        return contentJson;
    }

    private Path writeArtifact(ContextCompilationRequest request, String contentJson) {
        Path target = contextProperties.getArtifactRoot()
                .resolve(request.packType().name().toLowerCase())
                .resolve(request.scope().workflowRunId())
                .resolve(fileName(request))
                .toAbsolutePath()
                .normalize();
        try {
            Files.createDirectories(target.getParent());
            Files.writeString(target, contentJson, StandardCharsets.UTF_8);
            return target;
        } catch (IOException exception) {
            throw new IllegalStateException("failed to persist compiled context pack " + target, exception);
        }
    }

    private String fileName(ContextCompilationRequest request) {
        StringBuilder builder = new StringBuilder();
        builder.append(request.packType().name().toLowerCase());
        if (request.scope().taskId() != null) {
            builder.append("-").append(request.scope().taskId());
        }
        if (request.scope().runId() != null) {
            builder.append("-").append(request.scope().runId());
        }
        builder.append(".json");
        return builder.toString().replace(':', '-');
    }

    private String fingerprint(ContextCompilationRequest request, FactBundle factBundle, RetrievalBundle retrievalBundle) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(request.packType().name().getBytes(StandardCharsets.UTF_8));
            digest.update(objectMapper.writeValueAsBytes(request.scope()));
            digest.update(objectMapper.writeValueAsBytes(factBundle.sections()));
            digest.update(objectMapper.writeValueAsBytes(retrievalBundle.snippets()));
            return HexFormat.of().formatHex(digest.digest());
        } catch (JsonProcessingException | NoSuchAlgorithmException exception) {
            throw new IllegalStateException("failed to calculate context fingerprint", exception);
        }
    }
}
