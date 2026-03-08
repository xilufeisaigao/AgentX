package com.agentx.agentxbackend.requirement.infrastructure.external;

import com.agentx.agentxbackend.process.application.port.in.RuntimeLlmConfigUseCase;
import com.agentx.agentxbackend.requirement.application.RequirementAgentUpstreamException;
import com.agentx.agentxbackend.requirement.application.port.out.RequirementDraftGeneratorPort;
import com.agentx.agentxbackend.requirement.domain.policy.RequirementDocContentPolicy;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.openai.OpenAiChatModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class BailianRequirementDraftGenerator implements RequirementDraftGeneratorPort {

    private static final Logger log = LoggerFactory.getLogger(BailianRequirementDraftGenerator.class);
    private static final String MOCK_PROVIDER = "mock";
    private static final String BAILIAN_PROVIDER = "bailian";
    private static final String LANGCHAIN4J_FRAMEWORK = "langchain4j";
    private static final String DEFAULT_HANDOFF_REASON = "Detected architecture-layer change request";
    private static final String OUTPUT_LANGUAGE_HEADER = "X-AgentX-Language";
    private static final Pattern NUMBERED_REQUIREMENT_PATTERN = Pattern.compile("(?m)^\\s*\\d+[.)、：:]");

    private final ObjectMapper objectMapper;
    private final RuntimeLlmConfigUseCase runtimeLlmConfigUseCase;
    private final HttpClient httpClient;

    public BailianRequirementDraftGenerator(
        RuntimeLlmConfigUseCase runtimeLlmConfigUseCase,
        ObjectMapper objectMapper,
        @Value("${agentx.requirement.llm.timeout-ms:120000}") long timeoutMs
    ) {
        this.runtimeLlmConfigUseCase = runtimeLlmConfigUseCase;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofMillis(Math.max(1000L, timeoutMs)))
            .build();
        log.info("Requirement draft generator initialized with runtime LLM config resolver.");
    }

    @Override
    public ConversationAssessment assessConversation(AssessConversationInput input) {
        if (input == null) {
            throw new IllegalArgumentException("input must not be null");
        }
        ActiveLlmConfig llmConfig = resolveActiveRequirementLlm();
        String outputLanguage = llmConfig.outputLanguage();

        if (MOCK_PROVIDER.equals(llmConfig.provider())) {
            return buildMockConversationAssessment(input, llmConfig.model());
        }

        if (!BAILIAN_PROVIDER.equals(llmConfig.provider())) {
            throw new IllegalArgumentException("Unsupported LLM provider: " + llmConfig.provider());
        }
        if (llmConfig.apiKey().isBlank()) {
            throw new IllegalStateException(
                "agentx.requirement.llm.api-key is required when provider=bailian"
            );
        }

        try {
            String content = invokeBailian(
                llmConfig,
                buildAssessmentSystemPrompt(outputLanguage),
                buildAssessmentUserPrompt(input, outputLanguage),
                0.1d,
                buildAssessmentRequestBody(input, outputLanguage, llmConfig.model()),
                "assessConversation"
            );
            return parseConversationAssessment(content, input, outputLanguage, llmConfig.model());
        } catch (RuntimeException e) {
            if (e instanceof RequirementAgentUpstreamException upstreamException) {
                throw upstreamException;
            }
            throw new RequirementAgentUpstreamException("Bailian response parsing failed", e);
        }
    }

    @Override
    public GeneratedDraft generate(GenerateDraftInput input) {
        if (input == null) {
            throw new IllegalArgumentException("input must not be null");
        }
        ActiveLlmConfig llmConfig = resolveActiveRequirementLlm();
        String outputLanguage = llmConfig.outputLanguage();

        if (MOCK_PROVIDER.equals(llmConfig.provider())) {
            String summary = "Draft generated from user input: " + summarize(buildDraftContext(input));
            String changeLog = input.existingContent() == null
                ? "Initial draft generated in mock mode."
                : "Revision generated in mock mode from latest confirmed content.";
            return new GeneratedDraft(
                RequirementDocContentPolicy.buildTemplate(input.title(), summary, buildDraftContext(input), changeLog),
                MOCK_PROVIDER,
                llmConfig.model()
            );
        }

        if (!BAILIAN_PROVIDER.equals(llmConfig.provider())) {
            throw new IllegalArgumentException("Unsupported LLM provider: " + llmConfig.provider());
        }
        if (llmConfig.apiKey().isBlank()) {
            throw new IllegalStateException(
                "agentx.requirement.llm.api-key is required when provider=bailian"
            );
        }

        try {
            String content = invokeBailian(
                llmConfig,
                buildSystemPrompt(outputLanguage),
                buildUserPrompt(input, outputLanguage),
                0.2d,
                buildRequestBody(input, outputLanguage, llmConfig.model()),
                "generateDraft"
            );
            return new GeneratedDraft(content, BAILIAN_PROVIDER, llmConfig.model());
        } catch (RuntimeException e) {
            if (e instanceof RequirementAgentUpstreamException upstreamException) {
                throw upstreamException;
            }
            throw new RequirementAgentUpstreamException("Bailian response parsing failed", e);
        }
    }

    private String invokeBailian(
        ActiveLlmConfig llmConfig,
        String systemPrompt,
        String userPrompt,
        double temperature,
        String requestBody,
        String operation
    ) {
        if (useLangChain4j(llmConfig.framework())) {
            try {
                return invokeByLangChain4j(llmConfig, systemPrompt, userPrompt, temperature);
            } catch (RuntimeException ex) {
                log.warn("LangChain4j invoke failed, fallback to HTTP. operation={}", operation, ex);
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
            throw new RequirementAgentUpstreamException("LangChain4j response missing aiMessage");
        }
        String content = response.aiMessage().text();
        if (content == null || content.isBlank()) {
            throw new RequirementAgentUpstreamException("LangChain4j response content is blank");
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
                throw new RequirementAgentUpstreamException(
                    "Bailian API call failed, status=" + response.statusCode()
                        + ", body=" + trimForError(response.body())
                );
            }
            return extractContent(response.body());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RequirementAgentUpstreamException("Bailian API call interrupted", e);
        } catch (IOException e) {
            throw new RequirementAgentUpstreamException(buildIOExceptionMessage(e, llmConfig.timeout()), e);
        }
    }

    private ConversationAssessment buildMockConversationAssessment(AssessConversationInput input, String modelName) {
        String merged = buildMergedConversationText(input);
        boolean needsHandoff = detectArchitectureNeed(merged);
        String handoffReason = needsHandoff ? "Detected architecture-layer request in user input." : null;
        List<String> missing = evaluateMissingInformation(merged);
        boolean ready = missing.isEmpty();

        String assistantMessage;
        if (needsHandoff) {
            assistantMessage = "检测到这是架构层问题，已转交架构师处理。";
            ready = false;
        } else if (ready && input.userWantsDraft()) {
            assistantMessage = "信息已经足够，开始生成需求文档草稿。";
        } else if (ready) {
            assistantMessage = "信息已经比较完整。你可以继续补充，或直接发送“确认需求”来生成需求文档。";
        } else if (input.userWantsDraft()) {
            assistantMessage = "目前信息还不足以生成高质量需求文档，请先补充："
                + String.join("；", missing) + "。";
        } else {
            assistantMessage = "为继续完善需求，请先补充：" + String.join("；", missing) + "。";
        }

        return new ConversationAssessment(
            assistantMessage,
            ready,
            missing,
            needsHandoff,
            handoffReason,
            MOCK_PROVIDER,
            modelName
        );
    }

    private String buildRequestBody(GenerateDraftInput input, String outputLanguage, String modelName) {
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
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize Bailian request", e);
        }
    }

    private String buildAssessmentRequestBody(AssessConversationInput input, String outputLanguage, String modelName) {
        try {
            ObjectNode root = objectMapper.createObjectNode();
            root.put("model", modelName);
            root.put("temperature", 0.1);
            root.put("stream", false);

            ArrayNode messages = root.putArray("messages");
            messages.addObject()
                .put("role", "system")
                .put("content", buildAssessmentSystemPrompt(outputLanguage));
            messages.addObject()
                .put("role", "user")
                .put("content", buildAssessmentUserPrompt(input, outputLanguage));
            return objectMapper.writeValueAsString(root);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize Bailian assessment request", e);
        }
    }

    private boolean useLangChain4j(String frameworkName) {
        return LANGCHAIN4J_FRAMEWORK.equals(frameworkName);
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
        } catch (Exception e) {
            throw new IllegalStateException(
                "Failed to parse Bailian response: " + trimForError(responseBody),
                e
            );
        }
    }

    private static String buildSystemPrompt(String outputLanguage) {
        String schemaVersion = requiredSchemaVersion(outputLanguage);
        String headingGuide = requiredHeadingGuide(outputLanguage);
        return """
            You are a requirement engineering assistant.
            Output only markdown requirement document content and do not wrap it in code fences.
            The markdown must strictly follow this structure:
            1) YAML front matter with schema_version: %s
            2) # Title
            %s
            Open Questions section rules:
            - The section must always exist, but it does not need unresolved questions.
            - If no blocking requirement facts remain, use only CLOSED/已关闭 items in section 8.
            - Do not invent optional platform defaults as open questions. Prefer sensible defaults for protocol, port,
              JSON field naming, dependency version management, and other routine conventions unless the user explicitly
              asks to choose between alternatives.
            - If a routine default is chosen, capture it under References/Decisions and keep the corresponding question CLOSED/已关闭.
            Structured delivery spec rules:
            - Preserve user-provided fixed literals verbatim when they define versions, groupId/artifactId, package names,
              class names, endpoint paths, filenames, commands, or explicit exclusions.
            - Do not rename or generalize fixed identifiers.
            - Treat user-provided sample inputs and outputs as fixed literals too. Do not replace example values such as
              query parameter names, sample user names, or response bodies with different examples unless the user
              explicitly broadens them.
            - Reflect those fixed literals in Scope, Acceptance Criteria, and Value Constraints whenever they are part of
              the user-stated non-negotiable requirements.
            - When the user asks for a plain-text HTTP response, describe the response as plain-text body semantics and do
              not invent a stricter raw Content-Type header requirement unless the user explicitly requires exact header
              bytes or charset behavior.
            - For Spring Boot + MockMvc acceptance wording, allow compatible text/plain content types because framework
              defaults may append charset automatically.
            Use %s for all natural-language prose content in paragraphs and list items.
            Never output implementation architecture details.
            """.formatted(schemaVersion, headingGuide, languageInstruction(outputLanguage));
    }

    private static String buildAssessmentSystemPrompt(String outputLanguage) {
        return """
            You are a requirement intake assistant in discovery stage.
            Do NOT write requirement markdown.
            Evaluate whether user information is enough to start drafting a requirement document,
            and whether the latest request should be handed off to architect agent.
            Output strict JSON only with keys:
            {
              "assistant_message": "string",
              "ready_for_draft": true|false,
              "missing_information": ["string", "..."],
              "needs_handoff": true|false,
              "handoff_reason": "string|null"
            }
            Rules:
            - Focus on value-layer requirement facts only.
            - If user asks architecture / implementation / module split / database design details,
              set needs_handoff=true.
            - If user asks to confirm/start drafting but facts are insufficient, ready_for_draft must be false.
            - If facts are enough and user has not asked to draft yet, guide user to send "确认需求" to trigger drafting.
            - Keep assistant_message concise and actionable.
            - Use %s for assistant_message/missing_information/handoff_reason.
            """.formatted(languageInstruction(outputLanguage));
    }

    private static String buildUserPrompt(GenerateDraftInput input, String outputLanguage) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("Output language: ").append(nullSafe(outputLanguage)).append('\n');
        prompt.append("Title: ").append(input.title()).append('\n');
        prompt.append("Conversation history (oldest first):\n");
        List<ConversationTurn> history = input.history() == null ? List.of() : input.history();
        for (ConversationTurn turn : history) {
            if (turn == null) {
                continue;
            }
            String role = nullSafe(turn.role());
            String content = nullSafe(turn.content());
            if (content.isBlank()) {
                continue;
            }
            prompt.append(role).append(": ").append(content).append('\n');
        }
        prompt.append("User input:\n").append(input.userInput()).append('\n');
        String structuredSpecSource = buildDraftContext(input);
        if (looksLikeStructuredDeliverySpec(structuredSpecSource)) {
            prompt.append("Structured delivery spec detected: true\n");
            prompt.append("Preserve every explicit fixed literal verbatim. Do not rename coordinates, package names, class names, endpoints, files, commands, versions, or exclusions.\n");
            List<String> numberedRequirements = extractNumberedRequirementLines(structuredSpecSource);
            if (!numberedRequirements.isEmpty()) {
                prompt.append("Non-negotiable user clauses (copy their literal values into the requirement doc):\n");
                for (String clause : numberedRequirements) {
                    prompt.append("- ").append(clause).append('\n');
                }
            }
        }
        if (input.existingContent() != null && !input.existingContent().isBlank()) {
            prompt.append("Current requirement markdown (latest version):\n")
                .append(input.existingContent())
                .append('\n')
                .append("Please revise it according to the new user input while preserving traceability.");
        } else {
            prompt.append("Create the first complete requirement draft based on user input.");
        }
        return prompt.toString();
    }

    private static String buildAssessmentUserPrompt(AssessConversationInput input, String outputLanguage) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("Output language: ").append(nullSafe(outputLanguage)).append('\n');
        prompt.append("Title: ").append(nullSafe(input.title())).append('\n');
        prompt.append("User wants to start drafting now: ").append(input.userWantsDraft()).append('\n');
        prompt.append("Conversation history:\n");
        List<ConversationTurn> history = input.history() == null ? Collections.emptyList() : input.history();
        for (ConversationTurn turn : history) {
            if (turn == null) {
                continue;
            }
            String role = nullSafe(turn.role());
            String content = nullSafe(turn.content());
            if (content.isBlank()) {
                continue;
            }
            prompt.append(role).append(": ").append(content).append('\n');
        }
        prompt.append("Latest user input:\n").append(nullSafe(input.userInput())).append('\n');
        return prompt.toString();
    }

    private ConversationAssessment parseConversationAssessment(
        String raw,
        AssessConversationInput input,
        String outputLanguage,
        String modelName
    ) {
        String compact = raw == null ? "" : raw.trim();
        JsonNode root = tryParseJsonNode(compact);
        if (root == null) {
            int start = compact.indexOf('{');
            int end = compact.lastIndexOf('}');
            if (start >= 0 && end > start) {
                root = tryParseJsonNode(compact.substring(start, end + 1));
            }
        }

        if (root == null) {
            log.warn("Bailian assessment returned non-json output, fallback to heuristic: {}", trimForError(raw));
            return buildFallbackAssessment(input, outputLanguage, modelName);
        }

        String assistantMessage = root.path("assistant_message").asText("").trim();
        boolean readyForDraft = root.path("ready_for_draft").asBoolean(false);
        List<String> missingInformation = readMissingInformation(root.path("missing_information"));
        boolean needsHandoff = root.path("needs_handoff").asBoolean(false);
        String handoffReason = root.path("handoff_reason").asText("").trim();

        if (assistantMessage.isBlank()) {
            assistantMessage = buildFallbackAssistantMessage(
                input.userWantsDraft(),
                readyForDraft,
                needsHandoff,
                missingInformation,
                outputLanguage
            );
        }
        if (needsHandoff && handoffReason.isBlank()) {
            handoffReason = DEFAULT_HANDOFF_REASON;
        }
        if (readyForDraft && !missingInformation.isEmpty()) {
            missingInformation = List.of();
        }
        if (needsHandoff) {
            readyForDraft = false;
        }
        return normalizeAssessment(
            new ConversationAssessment(
                assistantMessage,
                readyForDraft,
                missingInformation,
                needsHandoff,
                handoffReason.isBlank() ? null : handoffReason,
                BAILIAN_PROVIDER,
                modelName
            ),
            input,
            outputLanguage,
            BAILIAN_PROVIDER,
            modelName
        );
    }

    private ConversationAssessment buildFallbackAssessment(
        AssessConversationInput input,
        String outputLanguage,
        String modelName
    ) {
        String merged = buildMergedConversationText(input);
        boolean needsHandoff = detectArchitectureNeed(merged);
        List<String> missing = evaluateMissingInformation(merged);
        boolean ready = missing.isEmpty() && !needsHandoff;
        String message = buildFallbackAssistantMessage(input.userWantsDraft(), ready, needsHandoff, missing, outputLanguage);
        return normalizeAssessment(
            new ConversationAssessment(
                message,
                ready,
                missing,
                needsHandoff,
                needsHandoff ? DEFAULT_HANDOFF_REASON : null,
                BAILIAN_PROVIDER,
                modelName
            ),
            input,
            outputLanguage,
            BAILIAN_PROVIDER,
            modelName
        );
    }

    ConversationAssessment normalizeAssessment(
        ConversationAssessment rawAssessment,
        AssessConversationInput input,
        String outputLanguage,
        String provider,
        String modelName
    ) {
        String merged = buildMergedConversationText(input);
        boolean structuredDeliverySpec = looksLikeStructuredDeliverySpec(merged);
        boolean architectureDecisionRequest = detectArchitectureNeed(merged);

        boolean needsHandoff = rawAssessment != null && rawAssessment.needsHandoff();
        if (needsHandoff && structuredDeliverySpec && !architectureDecisionRequest) {
            needsHandoff = false;
        }

        List<String> missingInformation = copyMissingInformation(rawAssessment);
        List<String> heuristicMissing = evaluateMissingInformation(merged);
        boolean readyForDraft = rawAssessment != null && rawAssessment.readyForDraft();
        if (!needsHandoff && (readyForDraft || structuredDeliverySpec || heuristicMissing.isEmpty())) {
            readyForDraft = true;
            missingInformation = List.of();
        } else if (!readyForDraft && missingInformation.isEmpty()) {
            missingInformation = heuristicMissing;
        }

        if (needsHandoff) {
            readyForDraft = false;
            missingInformation = List.of();
        }

        String handoffReason = normalizeNullable(rawAssessment == null ? null : rawAssessment.handoffReason());
        if (needsHandoff && handoffReason == null) {
            handoffReason = DEFAULT_HANDOFF_REASON;
        }
        if (!needsHandoff) {
            handoffReason = null;
        }

        boolean statusChanged = rawAssessment == null
            || rawAssessment.readyForDraft() != readyForDraft
            || rawAssessment.needsHandoff() != needsHandoff;
        String assistantMessage = normalizeNullable(rawAssessment == null ? null : rawAssessment.assistantMessage());
        if (assistantMessage == null || statusChanged) {
            assistantMessage = buildFallbackAssistantMessage(
                input.userWantsDraft(),
                readyForDraft,
                needsHandoff,
                missingInformation,
                outputLanguage
            );
        }

        return new ConversationAssessment(
            assistantMessage,
            readyForDraft,
            missingInformation,
            needsHandoff,
            handoffReason,
            provider,
            modelName
        );
    }

    private static String buildFallbackAssistantMessage(
        boolean userWantsDraft,
        boolean readyForDraft,
        boolean needsHandoff,
        List<String> missing,
        String outputLanguage
    ) {
        if (!isChinese(outputLanguage)) {
            if (needsHandoff) {
                return "This request requires architecture-level implementation details and has been handed off to the architect.";
            }
            if (readyForDraft && userWantsDraft) {
                return "Information is sufficient. Requirement drafting can start now.";
            }
            if (readyForDraft) {
                return "Information looks sufficient. You can keep refining, or send \"confirm requirement\" to start drafting.";
            }
            if (userWantsDraft) {
                return "Information is still insufficient for a high-quality draft. Please add: " + String.join("; ", missing) + ".";
            }
            return "To proceed, please provide: " + String.join("; ", missing) + ".";
        }
        if (needsHandoff) {
            return "该请求涉及架构层实现细节，已转交架构师评估。";
        }
        if (readyForDraft && userWantsDraft) {
            return "信息已经足够，可以开始生成需求文档。";
        }
        if (readyForDraft) {
            return "信息已经比较完整。你可以继续补充，或发送“确认需求”开始生成需求文档。";
        }
        if (userWantsDraft) {
            return "目前信息还不足以生成高质量需求文档，请补充：" + String.join("；", missing) + "。";
        }
        return "为继续推进，请补充：" + String.join("；", missing) + "。";
    }

    private JsonNode tryParseJsonNode(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String cleaned = stripMarkdownCodeFence(raw);
        try {
            return objectMapper.readTree(cleaned);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String stripMarkdownCodeFence(String raw) {
        String trimmed = raw.trim();
        if (!trimmed.startsWith("```")) {
            return trimmed;
        }
        int firstLineBreak = trimmed.indexOf('\n');
        if (firstLineBreak > 0) {
            trimmed = trimmed.substring(firstLineBreak + 1);
        }
        if (trimmed.endsWith("```")) {
            trimmed = trimmed.substring(0, trimmed.length() - 3);
        }
        return trimmed.trim();
    }

    private static List<String> readMissingInformation(JsonNode node) {
        if (node == null || !node.isArray()) {
            return List.of();
        }
        List<String> missing = new ArrayList<>();
        for (JsonNode item : node) {
            String value = item == null ? "" : item.asText("").trim();
            if (!value.isBlank()) {
                missing.add(value);
            }
        }
        return missing;
    }

    private static String buildMergedConversationText(AssessConversationInput input) {
        StringBuilder merged = new StringBuilder();
        if (input.history() != null) {
            for (ConversationTurn turn : input.history()) {
                if (turn == null || turn.content() == null || turn.content().isBlank()) {
                    continue;
                }
                String role = turn.role() == null ? "" : turn.role().trim().toLowerCase(Locale.ROOT);
                if (!"user".equals(role)) {
                    continue;
                }
                merged.append(role).append(":").append(turn.content()).append('\n');
            }
        }
        if (input.userInput() != null) {
            merged.append("user:").append(input.userInput());
        }
        return merged.toString().toLowerCase(Locale.ROOT);
    }

    private static List<String> evaluateMissingInformation(String mergedText) {
        if (looksLikeStructuredDeliverySpec(mergedText)) {
            return List.of();
        }
        List<String> missing = new ArrayList<>();
        if (!containsAny(
            mergedText,
            "目标", "价值", "痛点", "要做", "需要", "希望", "goal", "value", "problem", "outcome"
        )) {
            missing.add("业务目标与核心价值");
        }
        if (!containsAny(
            mergedText,
            "范围", "边界", "包括", "不包括", "in scope", "out of scope", "scope"
        )) {
            missing.add("范围边界（包含项/不包含项）");
        }
        if (!containsAny(
            mergedText,
            "验收", "成功标准", "指标", "sla", "kpi", "acceptance", "criteria", "metric"
        )) {
            missing.add("验收标准（如何判断需求完成）");
        }
        return missing;
    }

    private static boolean detectArchitectureNeed(String text) {
        if (looksLikeStructuredDeliverySpec(text)) {
            return false;
        }
        return containsAny(
            text,
            "架构设计", "架构方案", "数据库设计", "表结构", "分库分表", "微服务", "模块拆分", "部署方案", "缓存方案", "redis方案",
            "kafka方案", "消息队列方案", "一致性方案", "事务方案", "读写分离方案", "schema设计", "table design",
            "database design", "architecture design", "microservice split", "module split", "deployment topology",
            "event-driven architecture", "consistency model", "transaction strategy"
        );
    }

    private static boolean looksLikeStructuredDeliverySpec(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        boolean hasProjectIntent = containsAny(
            text,
            "创建一个", "生成一个", "做一个", "搭一个", "脚手架", "后端仓库", "仓库", "repo", "repository",
            "project", "scaffold", "backend", "可直接克隆运行", "clone"
        );
        if (!hasProjectIntent) {
            return false;
        }
        int signals = 0;
        if (containsAny(text, "java 17", "spring boot", "maven", "gradle", "node.js", "react", "vue")) {
            signals++;
        }
        if (containsAny(text, "groupid", "artifactid", "包名", "主启动类", "pom.xml", "readme", "application.properties")) {
            signals++;
        }
        if (containsAny(text, "/api/", "endpoint", "health", "greeting", "controller", "返回 200", "json")) {
            signals++;
        }
        if (containsAny(text, "不要接入", "不要", "不需要", "不要求", "without", "do not", "no database", "no cache")) {
            signals++;
        }
        if (containsAny(text, "mvn test", "gradle test", "启动方式", "测试方式", "run", "test")) {
            signals++;
        }
        return signals >= 3 || (signals >= 2 && countNumberedRequirements(text) >= 5);
    }

    private static int countNumberedRequirements(String text) {
        Matcher matcher = NUMBERED_REQUIREMENT_PATTERN.matcher(text);
        int count = 0;
        while (matcher.find()) {
            count++;
        }
        return count;
    }

    private static List<String> extractNumberedRequirementLines(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        List<String> lines = new ArrayList<>();
        for (String rawLine : text.split("\\R")) {
            if (rawLine == null) {
                continue;
            }
            String trimmed = rawLine.trim();
            if (trimmed.isBlank()) {
                continue;
            }
            Matcher matcher = NUMBERED_REQUIREMENT_PATTERN.matcher(trimmed);
            if (matcher.find()) {
                lines.add(trimmed);
            }
        }
        return lines.isEmpty() ? List.of() : List.copyOf(lines);
    }

    private static List<String> copyMissingInformation(ConversationAssessment rawAssessment) {
        if (rawAssessment == null || rawAssessment.missingInformation() == null || rawAssessment.missingInformation().isEmpty()) {
            return List.of();
        }
        List<String> missing = new ArrayList<>();
        for (String item : rawAssessment.missingInformation()) {
            String normalized = normalizeNullable(item);
            if (normalized != null) {
                missing.add(normalized);
            }
        }
        return missing.isEmpty() ? List.of() : List.copyOf(missing);
    }

    private static boolean containsAny(String text, String... keywords) {
        if (text == null || text.isBlank()) {
            return false;
        }
        for (String keyword : keywords) {
            if (keyword != null && !keyword.isBlank() && text.contains(keyword.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private static String normalizeBaseUrl(String baseUrl) {
        String trimmed = baseUrl == null ? "" : baseUrl.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("agentx.requirement.llm.base-url must not be blank");
        }
        if (trimmed.endsWith("/")) {
            return trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }

    private static String summarize(String userInput) {
        if (userInput == null || userInput.isBlank()) {
            return "No user input provided.";
        }
        String trimmed = userInput.trim().replaceAll("\\s+", " ");
        if (trimmed.length() <= 160) {
            return trimmed;
        }
        return trimmed.substring(0, 157) + "...";
    }

    private static String buildDraftContext(GenerateDraftInput input) {
        StringBuilder merged = new StringBuilder();
        String lastUserContent = "";
        List<ConversationTurn> history = input.history() == null ? List.of() : input.history();
        for (ConversationTurn turn : history) {
            if (turn == null) {
                continue;
            }
            String role = nullSafe(turn.role()).trim().toLowerCase(Locale.ROOT);
            String content = nullSafe(turn.content()).trim();
            if (content.isEmpty() || !"user".equals(role)) {
                continue;
            }
            if (!merged.isEmpty()) {
                merged.append(" ; ");
            }
            merged.append(content);
            lastUserContent = content;
        }
        String latestInput = nullSafe(input.userInput()).trim();
        if (!latestInput.isEmpty() && !latestInput.equals(lastUserContent)) {
            if (!merged.isEmpty()) {
                merged.append(" ; ");
            }
            merged.append(latestInput);
        }
        return merged.toString();
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
        String resolvedBaseUrl = resolveBaseUrl(provider, profile.baseUrl());
        return new ActiveLlmConfig(
            runtimeConfig.outputLanguage(),
            provider,
            framework,
            resolvedBaseUrl,
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

    private static String requiredSchemaVersion(String outputLanguage) {
        if (isChinese(outputLanguage)) {
            return RequirementDocContentPolicy.SCHEMA_VERSION_ZH;
        }
        return RequirementDocContentPolicy.SCHEMA_VERSION;
    }

    private static String requiredHeadingGuide(String outputLanguage) {
        if (isChinese(outputLanguage)) {
            return """
                3) ## 1. 摘要
                4) ## 2. 目标 with at least one '- [G-1] ...'
                5) ## 3. 非目标 with at least one '- [NG-1] ...'
                6) ## 4. 范围 including '### 包含' and '### 不包含'
                7) ## 5. 验收标准 with at least one '- [AC-1] ...'
                8) ## 6. 价值约束 with at least one '- [VC-1] ...'
                9) ## 7. 风险与权衡 with at least one '- [R-1] ...'
                10) ## 8. 开放问题 with at least one '- [Q-1][待确认|已关闭]' or '- [Q-1][OPEN|CLOSED] ...'
                11) ## 9. 参考 with '### 决策' and '### 架构决策记录'
                12) ## 10. 变更记录
                Keep the required headings exactly as written in Chinese.
                """;
        }
        return """
            3) ## 1. Summary
            4) ## 2. Goals with at least one '- [G-1] ...'
            5) ## 3. Non-Goals with at least one '- [NG-1] ...'
            6) ## 4. Scope including '### In' and '### Out'
            7) ## 5. Acceptance Criteria with at least one '- [AC-1] ...'
            8) ## 6. Value Constraints with at least one '- [VC-1] ...'
            9) ## 7. Risks & Tradeoffs with at least one '- [R-1] ...'
            10) ## 8. Open Questions with at least one '- [Q-1][OPEN|CLOSED] ...'
            11) ## 9. References with '### Decisions' and '### ADRs'
            12) ## 10. Change Log
            Keep the required headings exactly as written in English.
            """;
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

    private static String resolveBaseUrl(String provider, String value) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isBlank()) {
            if (MOCK_PROVIDER.equals(provider)) {
                return "https://dashscope.aliyuncs.com/compatible-mode/v1";
            }
            throw new IllegalArgumentException("agentx.requirement.llm.base-url must not be blank");
        }
        return normalizeBaseUrl(normalized);
    }

    private String buildIOExceptionMessage(IOException e, Duration timeoutDuration) {
        if (e instanceof HttpTimeoutException) {
            return "Bailian API call failed: timeout after " + timeoutDuration.toMillis() + "ms";
        }
        String detail = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        return "Bailian API call failed: " + detail;
    }

    private static String nullSafe(String value) {
        return Objects.toString(value, "");
    }

    private static String normalizeNullable(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
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

