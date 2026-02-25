package com.agentx.agentxbackend.process.infrastructure.external;

import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.openai.OpenAiChatModel;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class RealLlmSmokeTest {

    @Test
    void shouldInvokeRealLlmWhenEnabled() {
        assumeTrue(
            "true".equalsIgnoreCase(System.getenv("AGENTX_REAL_LLM_TEST")),
            "Set AGENTX_REAL_LLM_TEST=true to run real LLM smoke test"
        );
        String apiKey = env("AGENTX_REQUIREMENT_LLM_API_KEY");
        String baseUrl = env("AGENTX_REQUIREMENT_LLM_BASE_URL");
        String model = System.getenv().getOrDefault("AGENTX_REQUIREMENT_LLM_MODEL", "qwen3.5-plus-2026-02-15");
        assumeTrue(apiKey != null && !apiKey.isBlank(), "AGENTX_REQUIREMENT_LLM_API_KEY is required");
        assumeTrue(baseUrl != null && !baseUrl.isBlank(), "AGENTX_REQUIREMENT_LLM_BASE_URL is required");

        OpenAiChatModel chatModel = OpenAiChatModel.builder()
            .apiKey(apiKey.trim())
            .baseUrl(trimBaseUrl(baseUrl))
            .modelName(model)
            .temperature(0.0)
            .timeout(Duration.ofSeconds(60))
            .build();
        ChatRequest request = ChatRequest.builder()
            .messages(java.util.List.of(UserMessage.from("Reply with one short line proving connectivity.")))
            .build();

        ChatResponse response = chatModel.chat(request);

        assertNotNull(response);
        assertNotNull(response.aiMessage());
        String text = response.aiMessage().text();
        assertNotNull(text);
        assertFalse(text.isBlank(), "LLM response should not be blank");
    }

    private static String env(String key) {
        return System.getenv(key);
    }

    private static String trimBaseUrl(String value) {
        String trimmed = value.trim();
        if (trimmed.endsWith("/")) {
            return trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }
}

