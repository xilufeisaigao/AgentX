package com.agentx.agentxbackend.process.infrastructure.external;

import com.agentx.agentxbackend.process.application.port.out.LlmConnectivityTesterPort;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.openai.OpenAiChatModel;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Locale;

@Component
public class OpenAiCompatibleLlmConnectivityTester implements LlmConnectivityTesterPort {

    private static final String PROVIDER_MOCK = "mock";
    private static final String PROVIDER_BAILIAN = "bailian";
    private static final String FRAMEWORK_LANGCHAIN4J = "langchain4j";

    @Override
    public TestResult test(TestCommand command) {
        if (command == null) {
            return TestResult.failed(null, null, null, null, "llm test command must not be null");
        }
        String provider = normalizeToken(command.provider());
        String framework = normalizeToken(command.framework());
        String baseUrl = normalizeNullable(command.baseUrl());
        String model = normalizeNullable(command.model());
        String apiKey = normalizeNullable(command.apiKey());
        long timeoutMs = normalizeTimeout(command.timeoutMs());

        if (provider == null || provider.isBlank()) {
            return TestResult.failed(provider, framework, baseUrl, model, "provider is required");
        }
        if (PROVIDER_MOCK.equals(provider)) {
            return TestResult.success(
                provider,
                normalizeFramework(framework),
                baseUrl,
                model,
                0,
                "mock connectivity check passed"
            );
        }
        if (!PROVIDER_BAILIAN.equals(provider)) {
            return TestResult.failed(provider, framework, baseUrl, model, "unsupported provider: " + provider);
        }
        if (!FRAMEWORK_LANGCHAIN4J.equals(normalizeFramework(framework))) {
            return TestResult.failed(provider, framework, baseUrl, model, "unsupported framework: " + framework);
        }
        if (baseUrl == null || baseUrl.isBlank()) {
            return TestResult.failed(provider, framework, baseUrl, model, "base_url is required");
        }
        if (model == null || model.isBlank()) {
            return TestResult.failed(provider, framework, baseUrl, model, "model is required");
        }
        if (apiKey == null || apiKey.isBlank()) {
            return TestResult.failed(provider, framework, baseUrl, model, "api_key is required when provider=bailian");
        }

        String normalizedBaseUrl = trimTrailingSlash(baseUrl);
        try {
            OpenAiChatModel chatModel = OpenAiChatModel.builder()
                .apiKey(apiKey)
                .baseUrl(normalizedBaseUrl)
                .modelName(model)
                .temperature(0.0)
                .timeout(Duration.ofMillis(timeoutMs))
                .build();
            ChatRequest request = ChatRequest.builder()
                .messages(List.of(
                    SystemMessage.from(buildSystemPrompt(command.outputLanguage())),
                    UserMessage.from("Return a very short connectivity confirmation message.")
                ))
                .build();
            long startedAt = System.nanoTime();
            ChatResponse response = chatModel.chat(request);
            long latencyMs = Duration.ofNanos(System.nanoTime() - startedAt).toMillis();
            String content = response == null || response.aiMessage() == null ? "" : normalizeNullable(response.aiMessage().text());
            if (content == null || content.isBlank()) {
                return TestResult.failed(provider, framework, normalizedBaseUrl, model, "llm returned blank content");
            }
            return TestResult.success(
                provider,
                FRAMEWORK_LANGCHAIN4J,
                normalizedBaseUrl,
                model,
                latencyMs,
                trimPreview(content)
            );
        } catch (RuntimeException ex) {
            return TestResult.failed(
                provider,
                FRAMEWORK_LANGCHAIN4J,
                normalizedBaseUrl,
                model,
                sanitizeErrorMessage(ex.getMessage())
            );
        }
    }

    private static String buildSystemPrompt(String outputLanguage) {
        String language = normalizeNullable(outputLanguage);
        if (language == null || language.isBlank()) {
            language = "zh-CN";
        }
        return """
            You are an API connectivity probe.
            Reply in %s.
            Keep it short (<= 20 words).
            """.formatted(language);
    }

    private static String trimPreview(String value) {
        if (value.length() <= 240) {
            return value;
        }
        return value.substring(0, 240);
    }

    private static long normalizeTimeout(long timeoutMs) {
        return Math.max(1_000, Math.min(300_000, timeoutMs));
    }

    private static String normalizeToken(String value) {
        String normalized = normalizeNullable(value);
        return normalized == null ? null : normalized.toLowerCase(Locale.ROOT);
    }

    private static String normalizeFramework(String value) {
        String normalized = normalizeToken(value);
        return normalized == null || normalized.isBlank() ? FRAMEWORK_LANGCHAIN4J : normalized;
    }

    private static String normalizeNullable(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        return normalized.isEmpty() ? null : normalized;
    }

    private static String trimTrailingSlash(String value) {
        if (value == null || value.isBlank()) {
            return value;
        }
        if (value.endsWith("/")) {
            return value.substring(0, value.length() - 1);
        }
        return value;
    }

    private static String sanitizeErrorMessage(String message) {
        if (message == null || message.isBlank()) {
            return "llm invocation failed";
        }
        String compact = message.replace('\n', ' ').replace('\r', ' ').trim();
        if (compact.length() <= 320) {
            return compact;
        }
        return compact.substring(0, 320);
    }
}
