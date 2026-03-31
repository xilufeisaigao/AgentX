package com.agentx.platform.runtime.agentkernel.model;

import com.agentx.platform.domain.catalog.model.AgentDefinition;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.json.JsonSchema;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.service.output.JsonSchemas;
import org.springframework.stereotype.Component;

import javax.net.ssl.SSLException;
import javax.net.ssl.SSLHandshakeException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;
import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;

@Component
public class DeepSeekOpenAiCompatibleGateway implements ModelGateway {

    private final AgentModelProperties modelProperties;
    private final ObjectMapper objectMapper;

    public DeepSeekOpenAiCompatibleGateway(
            AgentModelProperties modelProperties,
            ObjectMapper objectMapper
    ) {
        this.modelProperties = modelProperties;
        this.objectMapper = objectMapper;
    }

    @Override
    public <T> StructuredModelResult<T> generateStructuredObject(
            AgentDefinition agentDefinition,
            String systemPrompt,
            String userPrompt,
            Class<T> responseType
    ) {
        String rawResponse = null;
        try {
            JsonSchema jsonSchema = JsonSchemas.jsonSchemaFrom(responseType)
                    .orElseThrow(() -> new IllegalStateException("missing json schema for response type " + responseType.getName()));
            rawResponse = executeStructuredChat(agentDefinition, systemPrompt, userPrompt, jsonSchema);
            if (rawResponse == null || rawResponse.isBlank()) {
                throw new IllegalStateException("deepseek returned an empty response");
            }
            T value = objectMapper.readValue(rawResponse, responseType);
            return new StructuredModelResult<>(value, "deepseek", agentDefinition.model(), rawResponse);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(
                    "failed to parse structured model response for "
                            + agentDefinition.agentId()
                            + ": "
                            + abbreviate(rawResponse),
                    exception
            );
        } catch (RuntimeException exception) {
            throw new IllegalStateException(
                    "deepseek model call failed for " + agentDefinition.agentId() + " using model " + agentDefinition.model(),
                    exception
            );
        }
    }

    protected String executeStructuredChat(
            AgentDefinition agentDefinition,
            String systemPrompt,
            String userPrompt,
            JsonSchema jsonSchema
    ) {
        String apiKey = requiredApiKey();
        OpenAiChatModel chatModel = buildChatModel(agentDefinition, apiKey);
        ChatRequest request = ChatRequest.builder()
                .messages(
                        SystemMessage.from(systemPrompt + "\n\n" + schemaInstruction(jsonSchema)),
                        UserMessage.from(userPrompt + "\n\n请只返回一个 JSON 对象，不要输出代码块、解释或额外文字。")
                )
                .build();
        int maxAttempts = Math.max(1, modelProperties.getMaxRetries() + 1);
        RuntimeException lastFailure = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                return requestStructuredJson(chatModel, request);
            } catch (RuntimeException exception) {
                lastFailure = exception;
                if (!isRetriableTransportFailure(exception) || attempt >= maxAttempts) {
                    throw exception;
                }
                sleepBeforeRetry(attempt, exception);
            }
        }
        throw lastFailure == null
                ? new IllegalStateException("structured model call exited retry loop without a result")
                : lastFailure;
    }

    protected OpenAiChatModel buildChatModel(AgentDefinition agentDefinition, String apiKey) {
        return OpenAiChatModel.builder()
                .baseUrl(modelProperties.getDeepseek().getBaseUrl())
                .apiKey(apiKey)
                .modelName(agentDefinition.model())
                .timeout(modelProperties.getTimeout())
                // We keep the retry budget at the gateway boundary so transport failures can be classified
                // and backed off deterministically instead of depending on provider-specific client behavior.
                .maxRetries(0)
                .strictJsonSchema(true)
                .build();
    }

    protected String requestStructuredJson(OpenAiChatModel chatModel, ChatRequest request) {
        return extractJson(chatModel.chat(request).aiMessage().text());
    }

    protected void sleepBeforeRetry(int attempt, RuntimeException exception) {
        try {
            Thread.sleep(retryDelayMillis(attempt));
        } catch (InterruptedException interruptedException) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("model retry interrupted", interruptedException);
        }
    }

    protected long retryDelayMillis(int attempt) {
        long baseDelayMillis = Math.min(4000L, 500L * attempt);
        return baseDelayMillis + ThreadLocalRandom.current().nextLong(250L);
    }

    private String requiredApiKey() {
        String apiKey = modelProperties.getDeepseek().getApiKey();
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("agentx.platform.model.deepseek.api-key must be configured");
        }
        return apiKey;
    }

    private String schemaInstruction(JsonSchema jsonSchema) {
        return """
                输出必须满足下面的 JSON schema，并且字段命名必须严格一致：
                %s
                """.formatted(jsonSchema);
    }

    private String extractJson(String rawResponse) {
        if (rawResponse == null) {
            return null;
        }
        String trimmed = rawResponse.trim();
        if (trimmed.startsWith("```")) {
            int firstLineBreak = trimmed.indexOf('\n');
            int closingFence = trimmed.lastIndexOf("```");
            if (firstLineBreak >= 0 && closingFence > firstLineBreak) {
                trimmed = trimmed.substring(firstLineBreak + 1, closingFence).trim();
            }
        }
        return trimmed;
    }

    private String abbreviate(String rawResponse) {
        if (rawResponse == null || rawResponse.isBlank()) {
            return "<empty>";
        }
        return rawResponse.length() <= 500 ? rawResponse : rawResponse.substring(0, 500) + "...";
    }

    private boolean isRetriableTransportFailure(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof SSLHandshakeException
                    || current instanceof SSLException
                    || current instanceof SocketTimeoutException
                    || current instanceof HttpTimeoutException
                    || current instanceof ConnectException) {
                return true;
            }
            String message = current.getMessage();
            if (message != null && !message.isBlank()) {
                String normalized = message.toLowerCase(Locale.ROOT);
                if (normalized.contains("remote host terminated the handshake")
                        || normalized.contains("connection reset")
                        || normalized.contains("broken pipe")
                        || normalized.contains("stream was reset")
                        || normalized.contains("timeout")
                        || normalized.contains("timed out")
                        || normalized.contains("temporarily unavailable")
                        || normalized.contains("too many requests")
                        || normalized.contains("429")
                        || normalized.contains("502")
                        || normalized.contains("503")
                        || normalized.contains("504")
                        || normalized.contains("bad gateway")
                        || normalized.contains("gateway timeout")) {
                    return true;
                }
            }
            current = current.getCause();
        }
        return false;
    }
}
