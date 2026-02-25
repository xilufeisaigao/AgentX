package com.agentx.agentxbackend.requirement.infrastructure.external;

import com.agentx.agentxbackend.process.application.port.in.RuntimeLlmConfigUseCase;
import com.agentx.agentxbackend.requirement.application.port.out.RequirementDraftGeneratorPort;
import com.agentx.agentxbackend.requirement.domain.policy.RequirementDocContentPolicy;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BailianRequirementDraftGeneratorTest {

    @Test
    void generateShouldReturnValidTemplateInMockMode() {
        BailianRequirementDraftGenerator generator = createGenerator("mock", "");

        RequirementDraftGeneratorPort.GeneratedDraft draft = generator.generate(
            new RequirementDraftGeneratorPort.GenerateDraftInput(
                "Checkout",
                "Need customer checkout and notify flow",
                null,
                List.of()
            )
        );

        assertEquals("mock", draft.provider());
        assertEquals("qwen3.5-plus-2026-02-15", draft.model());
        assertTrue(draft.content().contains("schema_version: req_doc_v1"));
        RequirementDocContentPolicy.validateOrThrow(draft.content());
    }

    @Test
    void assessConversationShouldReturnNeedMoreInfoInMockMode() {
        BailianRequirementDraftGenerator generator = createGenerator("mock", "");

        RequirementDraftGeneratorPort.ConversationAssessment assessment = generator.assessConversation(
            new RequirementDraftGeneratorPort.AssessConversationInput(
                "Checkout",
                "你好",
                List.of(),
                false
            )
        );

        assertEquals("mock", assessment.provider());
        assertEquals("qwen3.5-plus-2026-02-15", assessment.model());
        assertTrue(!assessment.readyForDraft());
        assertTrue(!assessment.missingInformation().isEmpty());
    }

    @Test
    void assessConversationShouldAllowDraftWhenInformationLooksSufficientInMockMode() {
        BailianRequirementDraftGenerator generator = createGenerator("mock", "");

        RequirementDraftGeneratorPort.ConversationAssessment assessment = generator.assessConversation(
            new RequirementDraftGeneratorPort.AssessConversationInput(
                "Checkout",
                "确认需求。目标是提升下单成功率，范围包括下单和支付，不包含营销，验收标准是成功率提升到95%。",
                List.of(),
                true
            )
        );

        assertTrue(assessment.readyForDraft());
        assertTrue(assessment.missingInformation().isEmpty());
        assertTrue(!assessment.needsHandoff());
    }

    @Test
    void assessConversationShouldCreateHandoffSignalForArchitectureRequestInMockMode() {
        BailianRequirementDraftGenerator generator = createGenerator("mock", "");

        RequirementDraftGeneratorPort.ConversationAssessment assessment = generator.assessConversation(
            new RequirementDraftGeneratorPort.AssessConversationInput(
                "Checkout",
                "请给我数据库分库分表和微服务拆分方案",
                List.of(),
                false
            )
        );

        assertTrue(assessment.needsHandoff());
        assertTrue(!assessment.readyForDraft());
    }

    @Test
    void assessConversationShouldFailWhenBailianApiKeyMissing() {
        BailianRequirementDraftGenerator generator = createGenerator("bailian", "");

        IllegalStateException ex = assertThrows(
            IllegalStateException.class,
            () -> generator.assessConversation(
                new RequirementDraftGeneratorPort.AssessConversationInput(
                    "Checkout",
                    "Need customer checkout and notify flow",
                    List.of(),
                    false
                )
            )
        );
        assertTrue(ex.getMessage().contains("api-key"));
    }

    @Test
    void generateShouldFailWhenBailianApiKeyMissing() {
        BailianRequirementDraftGenerator generator = createGenerator("bailian", "");

        IllegalStateException ex = assertThrows(
            IllegalStateException.class,
            () -> generator.generate(
                new RequirementDraftGeneratorPort.GenerateDraftInput(
                    "Checkout",
                    "Need customer checkout and notify flow",
                    null,
                    List.of()
                )
            )
        );
        assertTrue(ex.getMessage().contains("api-key"));
    }

    private static BailianRequirementDraftGenerator createGenerator(String provider, String apiKey) {
        return new BailianRequirementDraftGenerator(
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

