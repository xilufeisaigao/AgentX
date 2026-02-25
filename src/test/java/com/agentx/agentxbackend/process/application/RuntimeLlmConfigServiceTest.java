package com.agentx.agentxbackend.process.application;

import com.agentx.agentxbackend.process.application.port.in.RuntimeLlmConfigUseCase;
import com.agentx.agentxbackend.process.application.port.out.LlmConnectivityTesterPort;
import com.agentx.agentxbackend.process.application.port.out.RuntimeLlmConfigStorePort;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuntimeLlmConfigServiceTest {

    @Test
    void applyShouldPersistAndExposeCustomizedConfig() {
        InMemoryStore store = new InMemoryStore();
        RuntimeLlmConfigService service = createService(store);

        RuntimeLlmConfigUseCase.RuntimeConfigView applied = service.apply(
            new RuntimeLlmConfigUseCase.RuntimeConfigPatch(
                "zh-CN",
                new RuntimeLlmConfigUseCase.LlmProfilePatch(
                    "bailian",
                    "langchain4j",
                    "https://dashscope.aliyuncs.com/compatible-mode/v1",
                    "qwen3.5-plus-2026-02-15",
                    "sk-test",
                    120000L
                ),
                new RuntimeLlmConfigUseCase.LlmProfilePatch(
                    "bailian",
                    "langchain4j",
                    "https://dashscope.aliyuncs.com/compatible-mode/v1",
                    "qwen3.5-plus-2026-02-15",
                    "sk-test",
                    120000L
                )
            )
        );

        assertTrue(applied.customized());
        assertEquals("bailian", applied.requirementLlm().provider());
        assertEquals("bailian", applied.workerRuntimeLlm().provider());
        assertNotNull(store.value);
        assertEquals("bailian", store.value.requirementLlm().provider());
    }

    @Test
    void restoreShouldLoadPersistedConfig() {
        InMemoryStore store = new InMemoryStore();
        store.value = new RuntimeLlmConfigStorePort.StoredConfig(
            "en-US",
            new RuntimeLlmConfigStorePort.StoredProfile(
                "bailian",
                "langchain4j",
                "https://dashscope.aliyuncs.com/compatible-mode/v1",
                "qwen3.5-plus-2026-02-15",
                "sk-req",
                110000
            ),
            new RuntimeLlmConfigStorePort.StoredProfile(
                "mock",
                "langchain4j",
                "https://dashscope.aliyuncs.com/compatible-mode/v1",
                "qwen3.5-plus-2026-02-15",
                "",
                120000
            ),
            7L,
            true
        );
        RuntimeLlmConfigService service = createService(store);

        service.restoreFromStore();
        RuntimeLlmConfigUseCase.RuntimeConfigView current = service.getCurrentConfig();

        assertEquals("en-US", current.outputLanguage());
        assertEquals(7L, current.version());
        assertEquals("bailian", current.requirementLlm().provider());
        assertEquals("mock", current.workerRuntimeLlm().provider());
    }

    @Test
    void probeShouldUseConnectivityTester() {
        InMemoryStore store = new InMemoryStore();
        RuntimeLlmConfigService service = createService(store);

        RuntimeLlmConfigUseCase.ConnectivityProbeResult result = service.probe(
            new RuntimeLlmConfigUseCase.RuntimeConfigPatch(
                "zh-CN",
                new RuntimeLlmConfigUseCase.LlmProfilePatch("mock", "langchain4j", null, null, null, null),
                new RuntimeLlmConfigUseCase.LlmProfilePatch("mock", "langchain4j", null, null, null, null)
            )
        );

        assertTrue(result.allOk());
        assertTrue(result.requirementLlm().ok());
        assertTrue(result.workerRuntimeLlm().ok());
    }

    private static RuntimeLlmConfigService createService(InMemoryStore store) {
        return new RuntimeLlmConfigService(
            new MockConnectivityTester(),
            store,
            "zh-CN",
            "mock",
            "langchain4j",
            "https://dashscope.aliyuncs.com/compatible-mode/v1",
            "qwen3.5-plus-2026-02-15",
            "",
            120000,
            "mock",
            "langchain4j",
            "https://dashscope.aliyuncs.com/compatible-mode/v1",
            "qwen3.5-plus-2026-02-15",
            "",
            120000
        );
    }

    private static final class InMemoryStore implements RuntimeLlmConfigStorePort {
        private StoredConfig value;

        @Override
        public Optional<StoredConfig> load() {
            return Optional.ofNullable(value);
        }

        @Override
        public void save(StoredConfig config) {
            this.value = config;
        }
    }

    private static final class MockConnectivityTester implements LlmConnectivityTesterPort {
        @Override
        public TestResult test(TestCommand command) {
            return TestResult.success(
                command.provider(),
                command.framework(),
                command.baseUrl(),
                command.model(),
                1L,
                "ok"
            );
        }
    }
}
