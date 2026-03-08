package com.agentx.agentxbackend.requirement.infrastructure.external;

import com.agentx.agentxbackend.process.application.port.in.RuntimeLlmConfigUseCase;
import com.agentx.agentxbackend.requirement.application.port.out.RequirementDraftGeneratorPort;
import com.agentx.agentxbackend.requirement.domain.policy.RequirementDocContentPolicy;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.lang.reflect.Method;

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
    void assessConversationShouldTreatStructuredBootstrapPromptAsReadyInMockMode() {
        BailianRequirementDraftGenerator generator = createGenerator("mock", "");

        RequirementDraftGeneratorPort.ConversationAssessment assessment = generator.assessConversation(
            new RequirementDraftGeneratorPort.AssessConversationInput(
                "Hello API Demo",
                """
                    请创建一个极简 Java 17 + Spring Boot 3.x + Maven 项目，生成一个可直接克隆运行的后端仓库。

                    硬性要求：
                    1. 技术栈固定为 Java 17、Spring Boot 3.x、Maven。
                    2. Maven 坐标固定：groupId=com.example.helloapi，artifactId=hello-api-demo。
                    3. 提供 GET /api/health 和 GET /api/greeting。
                    4. 不要接入数据库、缓存、鉴权、Swagger、CI、Dockerfile。
                    5. 产物必须能通过 mvn test，并在 README 写明启动与测试方式。
                    """,
                List.of(),
                false
            )
        );

        assertTrue(assessment.readyForDraft());
        assertTrue(assessment.missingInformation().isEmpty());
        assertTrue(!assessment.needsHandoff());
    }

    @Test
    void normalizeAssessmentShouldSuppressSpuriousHandoffForStructuredDeliverySpec() {
        BailianRequirementDraftGenerator generator = createGenerator("mock", "");

        RequirementDraftGeneratorPort.AssessConversationInput input =
            new RequirementDraftGeneratorPort.AssessConversationInput(
                "Hello API Demo",
                """
                    请创建一个极简 Java 17 + Spring Boot 3.x + Maven 项目，生成一个可直接克隆运行的后端仓库。

                    硬性要求：
                    1. 技术栈固定为 Java 17、Spring Boot 3.x、Maven。
                    2. Maven 坐标固定：groupId=com.example.helloapi，artifactId=hello-api-demo。
                    3. 提供 GET /api/health 和 GET /api/greeting。
                    4. 不要接入数据库、缓存、鉴权、Swagger、CI、Dockerfile。
                    5. 产物必须能通过 mvn test，并在 README 写明启动与测试方式。
                    """,
                List.of(),
                false
            );

        RequirementDraftGeneratorPort.ConversationAssessment normalized = generator.normalizeAssessment(
            new RequirementDraftGeneratorPort.ConversationAssessment(
                "该请求属于架构层内容，已转交架构师。",
                false,
                List.of("业务目标"),
                true,
                "Architecture-level request",
                "bailian",
                "qwen3.5-plus-2026-02-15"
            ),
            input,
            "zh-CN",
            "bailian",
            "qwen3.5-plus-2026-02-15"
        );

        assertTrue(normalized.readyForDraft());
        assertTrue(normalized.missingInformation().isEmpty());
        assertTrue(!normalized.needsHandoff());
    }

    @Test
    void normalizeAssessmentShouldUseHistoryToAllowSecondRoundConfirmation() {
        BailianRequirementDraftGenerator generator = createGenerator("mock", "");

        RequirementDraftGeneratorPort.AssessConversationInput input =
            new RequirementDraftGeneratorPort.AssessConversationInput(
                "Hello API Demo",
                "确认需求",
                List.of(new RequirementDraftGeneratorPort.ConversationTurn(
                    "user",
                    """
                        请创建一个极简 Java 17 + Spring Boot 3.x + Maven 项目，生成一个可直接克隆运行的后端仓库。
                        需要 GET /api/health 和 GET /api/greeting，不要数据库，并且 README 写明 mvn test 和启动方式。
                        """
                )),
                true
            );

        RequirementDraftGeneratorPort.ConversationAssessment normalized = generator.normalizeAssessment(
            new RequirementDraftGeneratorPort.ConversationAssessment(
                "信息还不够，请继续补充。",
                false,
                List.of("验收标准"),
                false,
                null,
                "bailian",
                "qwen3.5-plus-2026-02-15"
            ),
            input,
            "zh-CN",
            "bailian",
            "qwen3.5-plus-2026-02-15"
        );

        assertTrue(normalized.readyForDraft());
        assertTrue(normalized.missingInformation().isEmpty());
        assertTrue(!normalized.needsHandoff());
    }

    @Test
    void buildSystemPromptShouldRequireVerbatimStructuredSpecPreservation() throws Exception {
        String prompt = invokeStaticStringMethod("buildSystemPrompt", String.class, "zh-CN");

        assertTrue(prompt.contains("Preserve user-provided fixed literals verbatim"));
        assertTrue(prompt.contains("Do not rename or generalize fixed identifiers."));
        assertTrue(prompt.contains("Treat user-provided sample inputs and outputs as fixed literals too."));
        assertTrue(prompt.contains("Reflect those fixed literals in Scope, Acceptance Criteria, and Value Constraints"));
        assertTrue(prompt.contains("not invent a stricter raw Content-Type header requirement"));
    }

    @Test
    void buildUserPromptShouldRepeatStructuredDeliveryClausesVerbatim() throws Exception {
        RequirementDraftGeneratorPort.GenerateDraftInput input =
            new RequirementDraftGeneratorPort.GenerateDraftInput(
                "Hello API Demo",
                """
                    请创建一个极简 Java 17 + Spring Boot 3.x + Maven 项目，生成一个可直接克隆运行的后端仓库。

                    硬性要求：
                    1. 技术栈固定为 Java 17、Spring Boot 3.x、Maven。
                    2. Maven 坐标固定：groupId=com.example.helloapi，artifactId=hello-api-demo，version=0.0.1-SNAPSHOT。
                    3. 包名固定：com.example.helloapi。
                    4. 主启动类固定：HelloApiDemoApplication。
                    5. 提供 GET /api/health 和 GET /api/greeting。
                    """,
                null,
                List.of()
            );

        String prompt = invokeStaticStringMethod(
            "buildUserPrompt",
            new Class<?>[] { RequirementDraftGeneratorPort.GenerateDraftInput.class, String.class },
            input,
            "zh-CN"
        );

        assertTrue(prompt.contains("Structured delivery spec detected: true"));
        assertTrue(prompt.contains("Preserve every explicit fixed literal verbatim."));
        assertTrue(prompt.contains("1. 技术栈固定为 Java 17、Spring Boot 3.x、Maven。"));
        assertTrue(prompt.contains("2. Maven 坐标固定：groupId=com.example.helloapi，artifactId=hello-api-demo，version=0.0.1-SNAPSHOT。"));
        assertTrue(prompt.contains("4. 主启动类固定：HelloApiDemoApplication。"));
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

    private static String invokeStaticStringMethod(String methodName, Class<?> parameterType, Object argument) throws Exception {
        return invokeStaticStringMethod(methodName, new Class<?>[] { parameterType }, argument);
    }

    private static String invokeStaticStringMethod(String methodName, Class<?>[] parameterTypes, Object... arguments) throws Exception {
        Method method = BailianRequirementDraftGenerator.class.getDeclaredMethod(methodName, parameterTypes);
        method.setAccessible(true);
        return (String) method.invoke(null, arguments);
    }
}

