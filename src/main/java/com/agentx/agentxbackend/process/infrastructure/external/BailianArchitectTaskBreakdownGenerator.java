package com.agentx.agentxbackend.process.infrastructure.external;

import com.agentx.agentxbackend.process.application.port.in.RuntimeLlmConfigUseCase;
import com.agentx.agentxbackend.process.application.port.out.ArchitectTaskBreakdownGeneratorPort;
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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Component
public class BailianArchitectTaskBreakdownGenerator implements ArchitectTaskBreakdownGeneratorPort {

    private static final Logger log = LoggerFactory.getLogger(BailianArchitectTaskBreakdownGenerator.class);
    private static final String MOCK_PROVIDER = "mock";
    private static final String BAILIAN_PROVIDER = "bailian";
    private static final String LANGCHAIN4J_FRAMEWORK = "langchain4j";
    private static final String OUTPUT_LANGUAGE_HEADER = "X-AgentX-Language";
    private static final Set<String> ALLOWED_TEMPLATES = Set.of(
        "tmpl.init.v0",
        "tmpl.impl.v0",
        "tmpl.verify.v0",
        "tmpl.bugfix.v0",
        "tmpl.refactor.v0",
        "tmpl.test.v0"
    );
    private static final List<String> DEFAULT_TOOLPACKS = List.of(
        "TP-JAVA-21",
        "TP-MAVEN-3",
        "TP-GIT-2"
    );

    private final ObjectMapper objectMapper;
    private final RuntimeLlmConfigUseCase runtimeLlmConfigUseCase;
    private final HttpClient httpClient;

    public BailianArchitectTaskBreakdownGenerator(
        RuntimeLlmConfigUseCase runtimeLlmConfigUseCase,
        ObjectMapper objectMapper,
        @Value("${agentx.requirement.llm.timeout-ms:120000}") long timeoutMs
    ) {
        this.runtimeLlmConfigUseCase = runtimeLlmConfigUseCase;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofMillis(Math.max(1000L, timeoutMs)))
            .build();
        log.info("Architect task breakdown generator initialized with runtime LLM config resolver.");
    }

    @Override
    public BreakdownPlan generate(GenerateInput input) {
        if (input == null) {
            throw new IllegalArgumentException("input must not be null");
        }
        ActiveLlmConfig llmConfig = resolveActiveRequirementLlm();
        String outputLanguage = llmConfig.outputLanguage();
        if (MOCK_PROVIDER.equals(llmConfig.provider())) {
            return buildMockPlan(input, MOCK_PROVIDER, outputLanguage, llmConfig.model());
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
            BreakdownPlan parsed = parsePlan(content, input, outputLanguage, llmConfig.model());
            return new BreakdownPlan(
                parsed.summary(),
                parsed.modules(),
                BAILIAN_PROVIDER,
                llmConfig.model()
            );
        } catch (RuntimeException ex) {
            log.warn(
                "Architect task breakdown generation failed, fallback to mock plan. ticketId={}",
                input.ticketId(),
                ex
            );
            BreakdownPlan fallback = buildMockPlan(input, BAILIAN_PROVIDER, outputLanguage, llmConfig.model());
            return new BreakdownPlan(
                fallback.summary(),
                fallback.modules(),
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

    private BreakdownPlan parsePlan(String raw, GenerateInput input, String outputLanguage, String modelName) {
        JsonNode root = tryParseJson(raw);
        if (root == null || !root.isObject()) {
            return buildMockPlan(input, BAILIAN_PROVIDER, outputLanguage, modelName);
        }
        String summary = root.path("summary").asText("").trim();
        if (summary.isBlank()) {
            summary = defaultSummary(input, outputLanguage);
        }
        List<ModulePlan> modules = readModules(root.path("modules"));
        if (modules.isEmpty()) {
            modules = defaultModules(input, outputLanguage);
        }
        return new BreakdownPlan(
            summary,
            modules,
            BAILIAN_PROVIDER,
            modelName
        );
    }

    private BreakdownPlan buildMockPlan(
        GenerateInput input,
        String providerName,
        String outputLanguage,
        String modelName
    ) {
        return new BreakdownPlan(
            defaultSummary(input, outputLanguage),
            defaultModules(input, outputLanguage),
            providerName,
            modelName
        );
    }

    private List<ModulePlan> readModules(JsonNode modulesNode) {
        if (modulesNode == null || !modulesNode.isArray()) {
            return List.of();
        }
        List<ModulePlan> modules = new ArrayList<>();
        for (JsonNode moduleNode : modulesNode) {
            if (moduleNode == null || !moduleNode.isObject()) {
                continue;
            }
            String name = moduleNode.path("name").asText("").trim();
            if (name.isBlank()) {
                continue;
            }
            String description = moduleNode.path("description").asText("").trim();
            List<TaskPlan> tasks = readTasks(moduleNode.path("tasks"));
            if (tasks.isEmpty()) {
                continue;
            }
            modules.add(new ModulePlan(name, description, tasks));
            if (modules.size() >= 8) {
                break;
            }
        }
        return modules;
    }

    private List<TaskPlan> readTasks(JsonNode tasksNode) {
        if (tasksNode == null || !tasksNode.isArray()) {
            return List.of();
        }
        List<TaskPlan> tasks = new ArrayList<>();
        for (JsonNode taskNode : tasksNode) {
            if (taskNode == null || !taskNode.isObject()) {
                continue;
            }
            String title = taskNode.path("title").asText("").trim();
            if (title.isBlank()) {
                continue;
            }
            String taskKey = normalizeTaskKey(taskNode.path("task_key").asText(""), title, tasks.size() + 1);
            String taskTemplateId = normalizeTemplate(taskNode.path("task_template_id").asText(""));
            List<String> requiredToolpacks = normalizeToolpacks(taskNode.path("required_toolpacks"));
            if (requiredToolpacks.isEmpty()) {
                requiredToolpacks = defaultToolpacksByTemplate(taskTemplateId, title);
            }
            List<String> dependsOnKeys = normalizeDependsOnKeys(taskNode.path("depends_on_keys"), taskKey);
            String rationale = taskNode.path("rationale").asText("").trim();
            tasks.add(new TaskPlan(taskKey, title, taskTemplateId, requiredToolpacks, dependsOnKeys, rationale));
            if (tasks.size() >= 20) {
                break;
            }
        }
        return tasks;
    }

    private static String buildSystemPrompt(String outputLanguage) {
        return """
            You are the architect foreman in AgentX.
            Your job is to produce concrete module/task breakdown after user has answered architecture questions.
            Output strict JSON only:
            {
              "summary":"string",
              "modules":[
                {
                  "name":"string",
                  "description":"string",
                  "tasks":[
                    {
                      "task_key":"string",
                      "title":"string",
                      "task_template_id":"tmpl.impl.v0|tmpl.test.v0|tmpl.bugfix.v0|tmpl.refactor.v0|tmpl.verify.v0",
                      "required_toolpacks":["TP-JAVA-21","TP-MAVEN-3","TP-GIT-2"],
                      "depends_on_keys":["task_key_a","task_key_b"],
                      "rationale":"string"
                    }
                  ]
                }
              ]
            }
            Rules:
            - Create executable tasks only; do not create abstract placeholders.
            - Keep module/task count small and practical.
            - required_toolpacks must be minimal.
            - Keep JSON keys/template IDs/toolpack IDs exactly as defined above.
            - Use %s for summary/module names/task titles/rationale.
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
        if (input.roleContextPackJson() != null && !input.roleContextPackJson().isBlank()) {
            prompt.append("role_context_pack_json:\n")
                .append(trimForPrompt(input.roleContextPackJson(), 6000))
                .append('\n');
        } else {
            prompt.append("role_context_pack_json: <not_available>\n");
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
        prompt.append("Please produce one practical module/task plan to start implementation.");
        return prompt.toString();
    }

    private static String defaultSummary(GenerateInput input, String outputLanguage) {
        if (isChinese(outputLanguage)) {
            return "为 " + nullSafe(input.title()) + " 自动生成的架构拆解计划。";
        }
        return "Auto-generated architecture work breakdown for " + nullSafe(input.title()) + ".";
    }

    private static List<ModulePlan> defaultModules(GenerateInput input, String outputLanguage) {
        String title = nullSafe(input.title());
        boolean chinese = isChinese(outputLanguage);
        List<TaskPlan> tasks = new ArrayList<>();
        tasks.add(new TaskPlan(
            "core_impl",
            chinese ? ("实现 " + title + " 的核心流程") : ("Implement core flow for " + title),
            "tmpl.impl.v0",
            defaultToolpacksByTemplate("tmpl.impl.v0", title),
            List.of(),
            chinese
                ? "基于已确认需求完成核心业务流程与 API 边界实现。"
                : "Implement business flow and API boundaries based on confirmed requirement."
        ));
        tasks.add(new TaskPlan(
            "core_test",
            chinese ? ("为 " + title + " 增加集成与契约测试") : ("Add integration and contract tests for " + title),
            "tmpl.test.v0",
            defaultToolpacksByTemplate("tmpl.test.v0", title),
            List.of("core_impl"),
            chinese
                ? "在 worker 执行前验证关键验收路径。"
                : "Verify key acceptance paths before worker execution starts."
        ));
        return List.of(
            new ModulePlan(
                "module-core-" + shortId(input.ticketId()),
                chinese ? "由架构提请派生的核心交付切片。" : "Core delivery slice derived from architect ticket.",
                tasks
            )
        );
    }

    private static String shortId(String ticketId) {
        String safe = nullSafe(ticketId);
        if (safe.length() <= 8) {
            return safe.isBlank() ? "default" : safe.toLowerCase(Locale.ROOT);
        }
        return safe.substring(safe.length() - 8).toLowerCase(Locale.ROOT);
    }

    private static String normalizeTemplate(String raw) {
        String normalized = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        if (ALLOWED_TEMPLATES.contains(normalized)) {
            return normalized;
        }
        return "tmpl.impl.v0";
    }

    private static String normalizeTaskKey(String raw, String title, int fallbackIndex) {
        String key = raw == null ? "" : raw.trim();
        if (key.isBlank()) {
            key = title == null ? "" : title.trim();
        }
        key = key.toLowerCase(Locale.ROOT)
            .replaceAll("[^a-z0-9_\\-]+", "_")
            .replaceAll("_+", "_")
            .replaceAll("^_+|_+$", "");
        if (key.isBlank()) {
            return "task_" + fallbackIndex;
        }
        return key;
    }

    private static List<String> normalizeDependsOnKeys(JsonNode node, String selfKey) {
        if (node == null || !node.isArray()) {
            return List.of();
        }
        LinkedHashSet<String> keys = new LinkedHashSet<>();
        for (JsonNode item : node) {
            if (item == null || !item.isTextual()) {
                continue;
            }
            String key = item.asText().trim().toLowerCase(Locale.ROOT);
            if (key.isEmpty() || key.equals(selfKey)) {
                continue;
            }
            keys.add(key);
        }
        return new ArrayList<>(keys);
    }

    private static List<String> normalizeToolpacks(JsonNode node) {
        if (node == null || !node.isArray()) {
            return List.of();
        }
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        for (JsonNode item : node) {
            if (item == null || !item.isTextual()) {
                continue;
            }
            String id = item.asText().trim();
            if (!id.isEmpty()) {
                ids.add(id);
            }
        }
        return new ArrayList<>(ids);
    }

    private static List<String> defaultToolpacksByTemplate(String taskTemplateId, String title) {
        LinkedHashSet<String> ids = new LinkedHashSet<>(DEFAULT_TOOLPACKS);
        String text = (taskTemplateId + " " + nullSafe(title)).toLowerCase(Locale.ROOT);
        if (text.contains("sql") || text.contains("mysql") || text.contains("db") || text.contains("database")) {
            ids.add("TP-MYSQL-8");
        }
        if (text.contains("python")) {
            ids.add("TP-PYTHON-3_11");
        }
        return new ArrayList<>(ids);
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

    private static String trimForPrompt(String raw, int maxLen) {
        String safe = nullSafe(raw).replaceAll("\\s+", " ").trim();
        if (safe.length() <= maxLen) {
            return safe;
        }
        return safe.substring(0, Math.max(0, maxLen - 3)) + "...";
    }

    private static String nullSafe(String value) {
        return value == null ? "" : value;
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

