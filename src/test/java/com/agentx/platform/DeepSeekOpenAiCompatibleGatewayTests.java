package com.agentx.platform;

import com.agentx.platform.domain.catalog.model.AgentDefinition;
import com.agentx.platform.runtime.agentkernel.model.AgentModelProperties;
import com.agentx.platform.runtime.agentkernel.model.DeepSeekOpenAiCompatibleGateway;
import com.agentx.platform.runtime.agentkernel.requirement.RequirementAgentDecision;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.model.chat.request.json.JsonSchema;
import org.junit.jupiter.api.Test;

import javax.net.ssl.SSLHandshakeException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DeepSeekOpenAiCompatibleGatewayTests {

    @Test
    void shouldFailFastWhenApiKeyIsMissing() {
        AgentModelProperties properties = new AgentModelProperties();
        DeepSeekOpenAiCompatibleGateway gateway = new DeepSeekOpenAiCompatibleGateway(properties, new ObjectMapper());

        assertThatThrownBy(() -> gateway.generateStructuredObject(
                requirementAgent(),
                "system",
                "user",
                RequirementAgentDecision.class
        )).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("deepseek model call failed")
                .rootCause()
                .hasMessageContaining("api-key");
    }

    @Test
    void shouldFailWhenStructuredResponseCannotBeParsed() {
        AgentModelProperties properties = new AgentModelProperties();
        properties.getDeepseek().setApiKey("test-key");
        DeepSeekOpenAiCompatibleGateway gateway = new DeepSeekOpenAiCompatibleGateway(properties, new ObjectMapper()) {
            @Override
            protected String executeStructuredChat(
                    AgentDefinition agentDefinition,
                    String systemPrompt,
                    String userPrompt,
                    JsonSchema jsonSchema
            ) {
                return "not-json";
            }
        };

        assertThatThrownBy(() -> gateway.generateStructuredObject(
                requirementAgent(),
                "system",
                "user",
                RequirementAgentDecision.class
        )).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("failed to parse structured model response");
    }

    @Test
    void shouldRetryRetriableTransportFailureAndEventuallySucceed() {
        AgentModelProperties properties = new AgentModelProperties();
        properties.getDeepseek().setApiKey("test-key");
        properties.setMaxRetries(2);
        AtomicInteger attempts = new AtomicInteger();
        DeepSeekOpenAiCompatibleGateway gateway = new DeepSeekOpenAiCompatibleGateway(properties, new ObjectMapper()) {
            @Override
            protected String requestStructuredJson(
                    dev.langchain4j.model.openai.OpenAiChatModel chatModel,
                    dev.langchain4j.model.chat.request.ChatRequest request
            ) {
                if (attempts.incrementAndGet() < 3) {
                    throw new IllegalStateException("transient gateway failure", new SSLHandshakeException("Remote host terminated the handshake"));
                }
                return """
                        {
                          "decision": "DRAFT_READY",
                          "gaps": [],
                          "questions": [],
                          "draftTitle": "Student API Requirement",
                          "draftContent": "Build a student management API.",
                          "summary": "ready"
                        }
                        """;
            }

            @Override
            protected void sleepBeforeRetry(int attempt, RuntimeException exception) {
                // No-op in tests.
            }
        };

        RequirementAgentDecision decision = gateway.generateStructuredObject(
                requirementAgent(),
                "system",
                "user",
                RequirementAgentDecision.class
        ).value();

        assertThat(attempts.get()).isEqualTo(3);
        assertThat(decision.decision().name()).isEqualTo("DRAFT_READY");
        assertThat(decision.draftTitle()).isEqualTo("Student API Requirement");
    }

    private AgentDefinition requirementAgent() {
        return new AgentDefinition(
                "requirement-agent",
                "Requirement Agent",
                "draft requirements",
                "SYSTEM",
                "in-process",
                "deepseek-chat",
                4,
                false,
                false,
                true,
                true
        );
    }
}
