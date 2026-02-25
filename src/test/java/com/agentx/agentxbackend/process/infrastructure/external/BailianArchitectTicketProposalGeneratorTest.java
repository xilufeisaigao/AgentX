package com.agentx.agentxbackend.process.infrastructure.external;

import com.agentx.agentxbackend.process.application.port.in.RuntimeLlmConfigUseCase;
import com.agentx.agentxbackend.process.application.port.out.ArchitectTicketProposalGeneratorPort;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BailianArchitectTicketProposalGeneratorTest {

    @Test
    void generateShouldProduceDecisionForHandoffInMockMode() {
        BailianArchitectTicketProposalGenerator generator = createGenerator("mock", "");

        ArchitectTicketProposalGeneratorPort.Proposal proposal = generator.generate(
            new ArchitectTicketProposalGeneratorPort.GenerateInput(
                "TCK-1",
                "SES-1",
                "HANDOFF",
                "handoff review",
                "REQ-1",
                3,
                "{\"kind\":\"handoff_packet\"}",
                "requirement markdown",
                java.util.List.of()
            )
        );

        assertEquals("DECISION", proposal.requestKind());
        assertTrue(!proposal.options().isEmpty());
        assertEquals("mock", proposal.provider());
    }

    @Test
    void generateShouldProduceClarificationForArchReviewInMockMode() {
        BailianArchitectTicketProposalGenerator generator = createGenerator("mock", "");

        ArchitectTicketProposalGeneratorPort.Proposal proposal = generator.generate(
            new ArchitectTicketProposalGeneratorPort.GenerateInput(
                "TCK-2",
                "SES-1",
                "ARCH_REVIEW",
                "arch review",
                "REQ-2",
                4,
                "{\"kind\":\"handoff_packet\"}",
                "requirement markdown",
                java.util.List.of()
            )
        );

        assertEquals("CLARIFICATION", proposal.requestKind());
        assertTrue(proposal.options().isEmpty());
    }

    @Test
    void generateShouldFailWhenBailianApiKeyMissing() {
        BailianArchitectTicketProposalGenerator generator = createGenerator("bailian", "");

        IllegalStateException ex = assertThrows(
            IllegalStateException.class,
            () -> generator.generate(
                new ArchitectTicketProposalGeneratorPort.GenerateInput(
                    "TCK-3",
                    "SES-1",
                    "HANDOFF",
                    "handoff review",
                    "REQ-1",
                    3,
                    "{}",
                    "requirement markdown",
                    java.util.List.of()
                )
            )
        );
        assertTrue(ex.getMessage().contains("api-key"));
    }

    private static BailianArchitectTicketProposalGenerator createGenerator(String provider, String apiKey) {
        return new BailianArchitectTicketProposalGenerator(
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

