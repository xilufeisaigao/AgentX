package com.agentx.agentxbackend.process.infrastructure.external;

import com.agentx.agentxbackend.process.application.port.in.RuntimeLlmConfigUseCase;
import com.agentx.agentxbackend.process.application.port.out.ArchitectTaskBreakdownGeneratorPort;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BailianArchitectTaskBreakdownGeneratorTest {

    @Test
    void generateShouldReturnPlanInMockMode() {
        BailianArchitectTaskBreakdownGenerator generator = createGenerator("mock", "");

        ArchitectTaskBreakdownGeneratorPort.BreakdownPlan plan = generator.generate(
            new ArchitectTaskBreakdownGeneratorPort.GenerateInput(
                "TCK-1",
                "SES-1",
                "HANDOFF",
                "order center mvp",
                "REQ-1",
                1,
                "{\"kind\":\"handoff_packet\"}",
                "requirement markdown content",
                "",
                List.of()
            )
        );

        assertFalse(plan.modules().isEmpty());
        assertFalse(plan.modules().get(0).tasks().isEmpty());
        assertTrue(plan.modules().get(0).tasks().get(0).requiredToolpackIds().contains("TP-JAVA-21"));
        assertEquals("mock", plan.provider());
    }

    @Test
    void generateShouldFailWhenBailianApiKeyMissing() {
        BailianArchitectTaskBreakdownGenerator generator = createGenerator("bailian", "");

        IllegalStateException ex = assertThrows(
            IllegalStateException.class,
            () -> generator.generate(
                new ArchitectTaskBreakdownGeneratorPort.GenerateInput(
                    "TCK-2",
                    "SES-1",
                    "ARCH_REVIEW",
                    "arch review",
                    "REQ-1",
                    1,
                    "{}",
                    "content",
                    "",
                    List.of()
                )
            )
        );
        assertTrue(ex.getMessage().contains("api-key"));
    }

    private static BailianArchitectTaskBreakdownGenerator createGenerator(String provider, String apiKey) {
        return new BailianArchitectTaskBreakdownGenerator(
            buildRuntimeConfigUseCase(provider, apiKey),
            new ObjectMapper(),
            30000
        );
    }

    private static RuntimeLlmConfigUseCase buildRuntimeConfigUseCase(String provider, String apiKey) {
        RuntimeLlmConfigUseCase.LlmProfile profile = new RuntimeLlmConfigUseCase.LlmProfile(
            provider,
            "langchain4j",
            "https://dashscope.aliyuncs.com/compatible-mode/v1",
            "qwen3.5-plus-2026-02-15",
            apiKey,
            30000
        );
        RuntimeLlmConfigUseCase.RuntimeConfigView config = new RuntimeLlmConfigUseCase.RuntimeConfigView(
            "zh-CN",
            profile,
            profile,
            1L,
            true
        );
        return new RuntimeLlmConfigUseCase() {
            @Override
            public RuntimeConfigView getCurrentConfig() {
                return config;
            }

            @Override
            public RuntimeConfigView resolveForRequestLanguage(String requestedOutputLanguage) {
                return config;
            }

            @Override
            public RuntimeConfigView apply(RuntimeConfigPatch patch) {
                throw new UnsupportedOperationException("not needed in test");
            }

            @Override
            public ConnectivityProbeResult probe(RuntimeConfigPatch patch) {
                throw new UnsupportedOperationException("not needed in test");
            }
        };
    }
}

