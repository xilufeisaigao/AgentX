package com.agentx.agentxbackend.process.infrastructure.external;

import com.agentx.agentxbackend.process.application.port.in.RuntimeLlmConfigUseCase;
import com.agentx.agentxbackend.process.application.port.out.ArchitectTicketProposalGeneratorPort;
import com.agentx.agentxbackend.process.application.port.out.ArchitectTicketEventContext;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.openai.OpenAiChatModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Component
public class BailianArchitectTicketProposalGenerator implements ArchitectTicketProposalGeneratorPort {

    private static final Logger log = LoggerFactory.getLogger(BailianArchitectTicketProposalGenerator.class);
    private static final String MOCK_PROVIDER = "mock";
    private static final String BAILIAN_PROVIDER = "bailian";
    private static final String LANGCHAIN4J_FRAMEWORK = "langchain4j";
    private static final String OUTPUT_LANGUAGE_HEADER = "X-AgentX-Language";

    private final ObjectMapper objectMapper;
    private final RuntimeLlmConfigUseCase runtimeLlmConfigUseCase;
    private final HttpClient httpClient;

    public BailianArchitectTicketProposalGenerator(
        RuntimeLlmConfigUseCase runtimeLlmConfigUseCase,
        ObjectMapper objectMapper,
        @Value("${agentx.requirement.llm.timeout-ms:120000}") long timeoutMs
    ) {
        this.runtimeLlmConfigUseCase = runtimeLlmConfigUseCase;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofMillis(Math.max(1000L, timeoutMs)))
            .build();
        log.info("Architect proposal generator initialized with runtime LLM config resolver.");
    }

    @Override
    public Proposal generate(GenerateInput input) {
        if (input == null) {
            throw new IllegalArgumentException("input must not be null");
        }
        ActiveLlmConfig llmConfig = resolveActiveRequirementLlm();
        String outputLanguage = llmConfig.outputLanguage();

        if (MOCK_PROVIDER.equals(llmConfig.provider())) {
            return buildMockProposal(input, MOCK_PROVIDER, outputLanguage, llmConfig.model());
        }
        if (!BAILIAN_PROVIDER.equals(llmConfig.provider())) {
            throw new IllegalArgumentException("Unsupported LLM provider: " + llmConfig.provider());
        }
        if (llmConfig.apiKey().isBlank()) {
            throw new IllegalStateException("agentx.requirement.llm.api-key is required when provider=bailian");
        }

        try {
            String content = invokeBailian(
                llmConfig,
                buildSystemPrompt(outputLanguage),
                buildUserPrompt(input, outputLanguage),
                0.2d,
                buildRequestBody(input, outputLanguage, llmConfig.model()),
                input.ticketId()
            );
            Proposal parsed = parseProposal(content, input, outputLanguage, llmConfig.model());
            return new Proposal(
                parsed.requestKind(),
                parsed.question(),
                parsed.context(),
                parsed.options(),
                parsed.recommendation(),
                parsed.analysisSummary(),
                BAILIAN_PROVIDER,
                llmConfig.model()
            );
        } catch (RuntimeException ex) {
            log.warn(
                "Architect proposal generation failed, fallback to heuristic. ticketId={}",
                input.ticketId(),
                ex
            );
            Proposal fallback = buildMockProposal(input, BAILIAN_PROVIDER, outputLanguage, llmConfig.model());
            return new Proposal(
                fallback.requestKind(),
                fallback.question(),
                fallback.context(),
                fallback.options(),
                fallback.recommendation(),
                fallback.analysisSummary(),
                BAILIAN_PROVIDER,
                llmConfig.model()
            );
        }
    }

    private String invokeBailian(
        ActiveLlmConfig llmConfig,
        String systemPrompt,
        String userPrompt,
        double temperature,
        String requestBody,
        String ticketId
    ) {
        if (useLangChain4j(llmConfig.framework())) {
            try {
                return invokeByLangChain4j(llmConfig, systemPrompt, userPrompt, temperature);
            } catch (RuntimeException ex) {
                log.warn("LangChain4j invoke failed, fallback to HTTP call. ticketId={}", ticketId, ex);
            }
        }
        return invokeByHttp(llmConfig, requestBody);
    }

    private String invokeByLangChain4j(
        ActiveLlmConfig llmConfig,
        String systemPrompt,
        String userPrompt,
        double temperature
    ) {
        OpenAiChatModel chatModel = OpenAiChatModel.builder()
            .apiKey(llmConfig.apiKey())
            .baseUrl(normalizeBaseUrl(llmConfig.baseUrl()))
            .modelName(llmConfig.model())
            .temperature(temperature)
            .timeout(llmConfig.timeout())
            .build();
        ChatRequest chatRequest = ChatRequest.builder()
            .messages(List.of(
                SystemMessage.from(systemPrompt),
                UserMessage.from(userPrompt)
            ))
            .build();
        ChatResponse response = chatModel.chat(chatRequest);
        if (response == null || response.aiMessage() == null) {
            throw new IllegalStateException("LangChain4j response missing aiMessage");
        }
        String content = response.aiMessage().text();
        if (content == null || content.isBlank()) {
            throw new IllegalStateException("LangChain4j response content is blank");
        }
        return content;
    }

    private String invokeByHttp(ActiveLlmConfig llmConfig, String requestBody) {
        String endpoint = normalizeBaseUrl(llmConfig.baseUrl()) + "/chat/completions";
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(endpoint))
            .timeout(llmConfig.timeout())
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer " + llmConfig.apiKey())
            .POST(HttpRequest.BodyPublishers.ofString(requestBody, StandardCharsets.UTF_8))
            .build();
        try {
            HttpResponse<String> response = httpClient.send(
                request,
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
            );
            if (response.statusCode() >= 400) {
                throw new IllegalStateException(
                    "Bailian API call failed, status=" + response.statusCode()
                        + ", body=" + trimForError(response.body())
                );
            }
            return extractContent(response.body());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Bailian API call interrupted", ex);
        } catch (IOException ex) {
            throw new IllegalStateException("Bailian API call failed", ex);
        }
    }

    private String buildRequestBody(GenerateInput input, String outputLanguage, String modelName) {
        try {
            ObjectNode root = objectMapper.createObjectNode();
            root.put("model", modelName);
            root.put("temperature", 0.2);
            root.put("stream", false);

            ArrayNode messages = root.putArray("messages");
            messages.addObject()
                .put("role", "system")
                .put("content", buildSystemPrompt(outputLanguage));
            messages.addObject()
                .put("role", "user")
                .put("content", buildUserPrompt(input, outputLanguage));
            return objectMapper.writeValueAsString(root);
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to serialize Bailian request", ex);
        }
    }

    private boolean useLangChain4j(String frameworkName) {
        return LANGCHAIN4J_FRAMEWORK.equals(frameworkName);
    }

    private Proposal parseProposal(String raw, GenerateInput input, String outputLanguage, String modelName) {
        JsonNode root = tryParseJson(raw);
        if (root == null) {
            return buildMockProposal(input, BAILIAN_PROVIDER, outputLanguage, modelName);
        }

        String requestKind = normalizeRequestKind(root.path("request_kind").asText(""), input.ticketType());
        String question = root.path("question").asText("").trim();
        if (question.isBlank()) {
            question = defaultQuestion(requestKind, input, outputLanguage);
        }
        String summary = root.path("analysis_summary").asText("").trim();
        if (summary.isBlank()) {
            summary = defaultSummary(requestKind, input, outputLanguage);
        }

        List<String> context = readStringList(root.path("context"));
        if (context.isEmpty()) {
            context = defaultContext(input, outputLanguage);
        }
        List<DecisionOption> options = readOptions(root.path("options"));
        Recommendation recommendation = readRecommendation(root.path("recommendation"));

        if ("DECISION".equals(requestKind) && options.isEmpty()) {
            options = defaultOptions(input, outputLanguage);
            recommendation = defaultRecommendation(options, outputLanguage);
        }
        if ("CLARIFICATION".equals(requestKind)) {
            options = List.of();
            recommendation = null;
        }

        return new Proposal(
            requestKind,
            question,
            context,
            options,
            recommendation,
            summary,
            BAILIAN_PROVIDER,
            modelName
        );
    }

    private Proposal buildMockProposal(
        GenerateInput input,
        String providerName,
        String outputLanguage,
        String modelName
    ) {
        String requestKind = defaultRequestKind(input.ticketType());
        String question = defaultQuestion(requestKind, input, outputLanguage);
        List<String> context = defaultContext(input, outputLanguage);
        List<DecisionOption> options = "DECISION".equals(requestKind) ? defaultOptions(input, outputLanguage) : List.of();
        Recommendation recommendation = "DECISION".equals(requestKind)
            ? defaultRecommendation(options, outputLanguage)
            : null;
        String summary = defaultSummary(requestKind, input, outputLanguage);
        return new Proposal(
            requestKind,
            question,
            context,
            options,
            recommendation,
            summary,
            providerName,
            modelName
        );
    }

    private static String buildSystemPrompt(String outputLanguage) {
        return """
            You are the architect agent in AgentX control-plane.
            You receive one HANDOFF or ARCH_REVIEW ticket and must produce a user-facing request.
            Output strict JSON only with keys:
            {
              "request_kind": "DECISION|CLARIFICATION",
              "question": "string",
              "context": ["string", "..."],
              "options": [
                {
                  "option_id": "OPT-A",
                  "title": "string",
                  "pros": ["string"],
                  "cons": ["string"],
                  "risks": ["string"],
                  "cost_notes": ["string"]
                }
              ],
              "recommendation": {"option_id":"OPT-A","reason":"string"} | null,
              "analysis_summary": "string"
            }
            Rules:
            - If the ticket asks for architecture tradeoff, choose request_kind=DECISION.
            - If facts are missing and no valid option can be selected, choose request_kind=CLARIFICATION.
            - Keep question concise and actionable.
            - For CLARIFICATION, options must be [] and recommendation must be null.
            - Keep JSON keys and enums exactly as defined above.
            - Use %s for all natural-language fields.
            - Do not output markdown fences.
            """.formatted(languageInstruction(outputLanguage));
    }

    private static String buildUserPrompt(GenerateInput input, String outputLanguage) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("output_language: ").append(nullSafe(outputLanguage)).append('\n');
        prompt.append("ticket_id: ").append(nullSafe(input.ticketId())).append('\n');
        prompt.append("session_id: ").append(nullSafe(input.sessionId())).append('\n');
        prompt.append("type: ").append(nullSafe(input.ticketType())).append('\n');
        prompt.append("title: ").append(nullSafe(input.title())).append('\n');
        prompt.append("requirement_doc_id: ").append(nullSafe(input.requirementDocId())).append('\n');
        prompt.append("requirement_doc_ver: ").append(input.requirementDocVer()).append('\n');
        prompt.append("payload_json:\n").append(nullSafe(input.payloadJson())).append('\n');
        if (input.requirementDocContent() != null && !input.requirementDocContent().isBlank()) {
            prompt.append("requirement_doc_content:\n")
                .append(trimForPrompt(input.requirementDocContent(), 6000))
                .append('\n');
        } else {
            prompt.append("requirement_doc_content: <not_available>\n");
        }
        prompt.append("recent_ticket_events:\n");
        if (input.recentEvents() == null || input.recentEvents().isEmpty()) {
            prompt.append("- <none>\n");
        } else {
            for (ArchitectTicketEventContext event : input.recentEvents()) {
                if (event == null) {
                    continue;
                }
                prompt.append("- [")
                    .append(nullSafe(event.createdAt()))
                    .append("] ")
                    .append(nullSafe(event.eventType()))
                    .append(" actor=")
                    .append(nullSafe(event.actorRole()))
                    .append(" body=")
                    .append(trimForPrompt(event.body(), 400))
                    .append(" data=")
                    .append(trimForPrompt(event.dataJson(), 500))
                    .append('\n');
            }
        }
        prompt.append("Please generate one next user request.");
        return prompt.toString();
    }

    private JsonNode tryParseJson(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String cleaned = stripMarkdownFence(raw.trim());
        JsonNode parsed = parseNode(cleaned);
        if (parsed != null) {
            return parsed;
        }
        int start = cleaned.indexOf('{');
        int end = cleaned.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return parseNode(cleaned.substring(start, end + 1));
        }
        return null;
    }

    private JsonNode parseNode(String raw) {
        try {
            return objectMapper.readTree(raw);
        } catch (Exception ignored) {
            return null;
        }
    }

    private List<String> readStringList(JsonNode node) {
        if (node == null || !node.isArray()) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        for (JsonNode item : node) {
            String value = item == null ? "" : item.asText("").trim();
            if (!value.isBlank()) {
                values.add(value);
            }
        }
        return values;
    }

    private List<DecisionOption> readOptions(JsonNode node) {
        if (node == null || !node.isArray()) {
            return List.of();
        }
        List<DecisionOption> options = new ArrayList<>();
        for (JsonNode item : node) {
            if (item == null || !item.isObject()) {
                continue;
            }
            String optionId = item.path("option_id").asText("").trim();
            String title = item.path("title").asText("").trim();
            if (optionId.isBlank() || title.isBlank()) {
                continue;
            }
            options.add(new DecisionOption(
                optionId,
                title,
                readStringList(item.path("pros")),
                readStringList(item.path("cons")),
                readStringList(item.path("risks")),
                readStringList(item.path("cost_notes"))
            ));
        }
        return options;
    }

    private Recommendation readRecommendation(JsonNode node) {
        if (node == null || !node.isObject()) {
            return null;
        }
        String optionId = node.path("option_id").asText("").trim();
        String reason = node.path("reason").asText("").trim();
        if (optionId.isBlank() || reason.isBlank()) {
            return null;
        }
        return new Recommendation(optionId, reason);
    }

    private static String defaultRequestKind(String ticketType) {
        if ("ARCH_REVIEW".equalsIgnoreCase(nullSafe(ticketType))) {
            return "CLARIFICATION";
        }
        return "DECISION";
    }

    private static String normalizeRequestKind(String raw, String ticketType) {
        String normalized = raw == null ? "" : raw.trim().toUpperCase(Locale.ROOT);
        if ("DECISION".equals(normalized) || "CLARIFICATION".equals(normalized)) {
            return normalized;
        }
        return defaultRequestKind(ticketType);
    }

    private static String defaultQuestion(String requestKind, GenerateInput input, String outputLanguage) {
        if (isChinese(outputLanguage)) {
            if ("CLARIFICATION".equals(requestKind)) {
                return "请补充 " + nullSafe(input.title()) + " 的架构约束和关键非功能目标。";
            }
            return "请在 " + nullSafe(input.title()) + " 的架构方向中选择首选方案。";
        }
        if ("CLARIFICATION".equals(requestKind)) {
            return "Please clarify the architecture constraints and expected non-functional targets for "
                + nullSafe(input.title()) + ".";
        }
        return "Please choose the preferred architecture direction for "
            + nullSafe(input.title()) + ".";
    }

    private static String defaultSummary(String requestKind, GenerateInput input, String outputLanguage) {
        if (isChinese(outputLanguage)) {
            if ("CLARIFICATION".equals(requestKind)) {
                return "架构评审缺少关键事实约束，暂无法选择设计方案。";
            }
            return "检测到架构取舍，需要用户决策后再继续推进。";
        }
        if ("CLARIFICATION".equals(requestKind)) {
            return "Architecture review requires missing factual constraints before selecting a design option.";
        }
        return "Architecture tradeoff detected, user decision is required to proceed.";
    }

    private static List<String> defaultContext(GenerateInput input, String outputLanguage) {
        List<String> context = new ArrayList<>();
        boolean chinese = isChinese(outputLanguage);
        if (input.requirementDocId() != null && !input.requirementDocId().isBlank()) {
            context.add((chinese ? "需求引用: " : "Requirement reference: ")
                + input.requirementDocId() + "@" + input.requirementDocVer());
        }
        if (input.ticketType() != null && !input.ticketType().isBlank()) {
            context.add((chinese ? "提请类型: " : "Ticket type: ") + input.ticketType());
        }
        if (input.payloadJson() != null && !input.payloadJson().isBlank()) {
            context.add(chinese ? "ticket payload_json 已附带交接数据。" : "Handoff payload attached in ticket payload_json.");
        }
        return context;
    }

    private static List<DecisionOption> defaultOptions(GenerateInput input, String outputLanguage) {
        if (isChinese(outputLanguage)) {
            return List.of(
                new DecisionOption(
                    "OPT-A",
                    "增量扩展",
                    List.of("短期迁移风险更低"),
                    List.of("可能保留历史复杂性"),
                    List.of("架构债持续累积"),
                    List.of("短期成本较低，长期成本中等")
                ),
                new DecisionOption(
                    "OPT-B",
                    "结构化重构",
                    List.of("长期架构边界更清晰"),
                    List.of("短期交付压力更高"),
                    List.of("过渡期可能影响当前迭代速度"),
                    List.of("短期成本更高，长期可维护性更好")
                )
            );
        }
        return List.of(
            new DecisionOption(
                "OPT-A",
                "Incremental extension",
                List.of("Lower immediate migration risk"),
                List.of("May keep legacy complexity"),
                List.of("Architecture debt accumulates"),
                List.of("Lower short-term cost, medium long-term cost")
            ),
            new DecisionOption(
                "OPT-B",
                "Structured redesign",
                List.of("Clearer long-term architecture boundary"),
                List.of("Higher short-term delivery pressure"),
                List.of("Transition period may slow current iteration"),
                List.of("Higher short-term cost, better long-term maintainability")
            )
        );
    }

    private static Recommendation defaultRecommendation(List<DecisionOption> options, String outputLanguage) {
        if (options == null || options.isEmpty()) {
            return null;
        }
        DecisionOption first = options.get(0);
        if (isChinese(outputLanguage)) {
            return new Recommendation(first.optionId(), "当前需求基线阶段，优先选择交付风险更低的方案。");
        }
        return new Recommendation(first.optionId(), "Prefer lower delivery risk for the current requirement baseline.");
    }

    private String extractContent(String responseBody) {
        try {
            JsonNode root = objectMapper.readTree(responseBody);
            JsonNode contentNode = root.path("choices").path(0).path("message").path("content");
            String content = contentNode.isMissingNode() ? "" : contentNode.asText("");
            if (content.isBlank()) {
                throw new IllegalStateException(
                    "Bailian API response missing choices[0].message.content, body="
                        + trimForError(responseBody)
                );
            }
            return content;
        } catch (Exception ex) {
            throw new IllegalStateException(
                "Failed to parse Bailian response: " + trimForError(responseBody),
                ex
            );
        }
    }

    private static String stripMarkdownFence(String raw) {
        if (!raw.startsWith("```")) {
            return raw;
        }
        int firstBreak = raw.indexOf('\n');
        String value = firstBreak > 0 ? raw.substring(firstBreak + 1) : raw;
        if (value.endsWith("```")) {
            value = value.substring(0, value.length() - 3);
        }
        return value.trim();
    }

    private static String normalizeBaseUrl(String baseUrl) {
        String trimmed = baseUrl == null ? "" : baseUrl.trim();
        if (trimmed.isBlank()) {
            throw new IllegalArgumentException("agentx.requirement.llm.base-url must not be blank");
        }
        if (trimmed.endsWith("/")) {
            return trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }

    private static String trimForError(String raw) {
        if (raw == null) {
            return "";
        }
        String compact = raw.replaceAll("\\s+", " ").trim();
        if (compact.length() <= 400) {
            return compact;
        }
        return compact.substring(0, 400) + "...";
    }

    private ActiveLlmConfig resolveActiveRequirementLlm() {
        RuntimeLlmConfigUseCase.RuntimeConfigView runtimeConfig = runtimeLlmConfigUseCase.resolveForRequestLanguage(
            resolveRequestedOutputLanguage()
        );
        RuntimeLlmConfigUseCase.LlmProfile profile = runtimeConfig.requirementLlm();
        String provider = normalizeProvider(profile.provider());
        String framework = normalizeFramework(profile.framework());
        String modelName = normalizeModel(profile.model());
        return new ActiveLlmConfig(
            runtimeConfig.outputLanguage(),
            provider,
            framework,
            resolveBaseUrl(provider, profile.baseUrl()),
            modelName,
            profile.apiKey() == null ? "" : profile.apiKey().trim(),
            Duration.ofMillis(Math.max(1000L, profile.timeoutMs()))
        );
    }

    private String resolveRequestedOutputLanguage() {
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        if (attributes instanceof ServletRequestAttributes servletAttributes) {
            return servletAttributes.getRequest().getHeader(OUTPUT_LANGUAGE_HEADER);
        }
        return null;
    }

    private static String normalizeOutputLanguage(String value) {
        if (value == null) {
            return "";
        }
        String normalized = value.trim();
        if (normalized.isBlank()) {
            return "";
        }
        String lower = normalized.toLowerCase(Locale.ROOT);
        if (lower.equals("zh") || lower.equals("zh-cn") || lower.equals("cn") || lower.equals("chinese")) {
            return "zh-CN";
        }
        if (lower.equals("en") || lower.equals("en-us") || lower.equals("english")) {
            return "en-US";
        }
        if (lower.equals("ja") || lower.equals("ja-jp") || lower.equals("japanese")) {
            return "ja-JP";
        }
        if (!normalized.matches("[A-Za-z]{2,3}([_-][A-Za-z0-9]{2,8})?")) {
            return "";
        }
        return normalized.replace('_', '-');
    }

    private static String languageInstruction(String outputLanguage) {
        if (isChinese(outputLanguage)) {
            return "Simplified Chinese (zh-CN)";
        }
        String normalized = normalizeOutputLanguage(outputLanguage);
        if (!normalized.isBlank()) {
            return normalized;
        }
        return "Simplified Chinese (zh-CN)";
    }

    private static boolean isChinese(String outputLanguage) {
        return normalizeOutputLanguage(outputLanguage).toLowerCase(Locale.ROOT).startsWith("zh");
    }

    private static String normalizeProvider(String value) {
        String normalized = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        if (normalized.isBlank()) {
            return MOCK_PROVIDER;
        }
        return normalized;
    }

    private static String normalizeFramework(String value) {
        String normalized = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        if (normalized.isBlank()) {
            return LANGCHAIN4J_FRAMEWORK;
        }
        return normalized;
    }

    private static String normalizeModel(String value) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isBlank()) {
            return "qwen3.5-plus-2026-02-15";
        }
        return normalized;
    }

    private static String resolveBaseUrl(String provider, String baseUrl) {
        String normalized = baseUrl == null ? "" : baseUrl.trim();
        if (normalized.isBlank()) {
            if (MOCK_PROVIDER.equals(provider)) {
                return "https://dashscope.aliyuncs.com/compatible-mode/v1";
            }
            throw new IllegalArgumentException("agentx.requirement.llm.base-url must not be blank");
        }
        return normalizeBaseUrl(normalized);
    }

    private static String nullSafe(String value) {
        return value == null ? "" : value;
    }

    private static String trimForPrompt(String raw, int maxLen) {
        String safe = nullSafe(raw).replaceAll("\\s+", " ").trim();
        if (safe.length() <= maxLen) {
            return safe;
        }
        return safe.substring(0, Math.max(0, maxLen - 3)) + "...";
    }

    private record ActiveLlmConfig(
        String outputLanguage,
        String provider,
        String framework,
        String baseUrl,
        String model,
        String apiKey,
        Duration timeout
    ) {
    }
}

