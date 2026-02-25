package com.agentx.agentxbackend.process.infrastructure.external;

import com.agentx.agentxbackend.process.application.port.out.RuntimeLlmConfigStorePort;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Optional;

@Component
public class LocalFileRuntimeLlmConfigStore implements RuntimeLlmConfigStorePort {

    private final ObjectMapper objectMapper;
    private final Path filePath;

    public LocalFileRuntimeLlmConfigStore(
        ObjectMapper objectMapper,
        @Value("${agentx.llm.runtime-config.file-path:.agentx/runtime/runtime-llm-config.json}") String filePath
    ) {
        this.objectMapper = objectMapper;
        this.filePath = Path.of(filePath == null || filePath.isBlank()
                ? ".agentx/runtime/runtime-llm-config.json"
                : filePath.trim())
            .toAbsolutePath()
            .normalize();
    }

    @Override
    public Optional<StoredConfig> load() {
        if (!Files.exists(filePath)) {
            return Optional.empty();
        }
        try {
            String content = Files.readString(filePath, StandardCharsets.UTF_8);
            if (content.isBlank()) {
                return Optional.empty();
            }
            StoredConfig config = objectMapper.readValue(content, StoredConfig.class);
            return Optional.ofNullable(config);
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to load runtime llm config: " + filePath, ex);
        }
    }

    @Override
    public void save(StoredConfig config) {
        if (config == null) {
            throw new IllegalArgumentException("config must not be null");
        }
        try {
            Path parent = filePath.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            String json = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(config);
            Path tempFile = filePath.resolveSibling(filePath.getFileName() + ".tmp");
            Files.writeString(tempFile, json, StandardCharsets.UTF_8);
            try {
                Files.move(
                    tempFile,
                    filePath,
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE
                );
            } catch (AtomicMoveNotSupportedException ex) {
                Files.move(tempFile, filePath, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to save runtime llm config: " + filePath, ex);
        }
    }
}
