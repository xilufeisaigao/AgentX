package com.agentx.agentxbackend.process.infrastructure.external;

import com.agentx.agentxbackend.process.application.port.out.RuntimeLlmConfigStorePort;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocalFileRuntimeLlmConfigStoreTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldSaveAndLoadConfig() {
        Path file = tempDir.resolve("runtime").resolve("runtime-llm-config.json");
        LocalFileRuntimeLlmConfigStore store = new LocalFileRuntimeLlmConfigStore(new ObjectMapper(), file.toString());
        RuntimeLlmConfigStorePort.StoredConfig config = new RuntimeLlmConfigStorePort.StoredConfig(
            "zh-CN",
            new RuntimeLlmConfigStorePort.StoredProfile(
                "bailian",
                "langchain4j",
                "https://dashscope.aliyuncs.com/compatible-mode/v1",
                "qwen3.5-plus-2026-02-15",
                "sk-test",
                120000
            ),
            new RuntimeLlmConfigStorePort.StoredProfile(
                "mock",
                "langchain4j",
                "https://dashscope.aliyuncs.com/compatible-mode/v1",
                "qwen3.5-plus-2026-02-15",
                "",
                120000
            ),
            9L,
            true
        );

        store.save(config);
        Optional<RuntimeLlmConfigStorePort.StoredConfig> loaded = store.load();

        assertTrue(loaded.isPresent());
        assertEquals(9L, loaded.get().version());
        assertEquals("bailian", loaded.get().requirementLlm().provider());
        assertEquals("mock", loaded.get().workerRuntimeLlm().provider());
    }
}
