package com.agentx.platform.runtime.retrieval;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

@Component
public class JsonChunkStore implements ChunkStore {

    private final RetrievalProperties retrievalProperties;
    private final ObjectMapper objectMapper;

    public JsonChunkStore(
            RetrievalProperties retrievalProperties,
            ObjectMapper objectMapper
    ) {
        this.retrievalProperties = retrievalProperties;
        this.objectMapper = objectMapper;
    }

    @Override
    public RepoIndexManifest write(RepoIndexManifest manifest) {
        Path target = retrievalProperties.getIndexRoot()
                .resolve(manifest.indexId() + ".json")
                .toAbsolutePath()
                .normalize();
        try {
            Files.createDirectories(target.getParent());
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(target.toFile(), manifest);
            return manifest;
        } catch (IOException exception) {
            throw new IllegalStateException("failed to persist retrieval manifest " + target, exception);
        }
    }
}
