package com.agentx.agentxbackend.process.application;

import com.agentx.agentxbackend.contextpack.application.port.in.ContextCompileUseCase;
import com.agentx.agentxbackend.planning.application.port.in.PlanningCommandUseCase;
import com.agentx.agentxbackend.planning.domain.model.WorkModule;
import com.agentx.agentxbackend.planning.domain.model.WorkTask;
import com.agentx.agentxbackend.session.domain.event.SessionCreatedEvent;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

@Component
public class SessionBootstrapInitProcessManager {

    private final PlanningCommandUseCase planningCommandUseCase;
    private final ContextCompileUseCase contextCompileUseCase;
    private final ObjectMapper objectMapper;
    private final String initModuleName;
    private final String initModuleDescription;
    private final String initTaskTitle;
    private final String initTaskTemplateId;
    private final String initRequiredToolpacksJson;
    private final String initContextTriggerType;
    private final boolean compileVerifyContext;

    public SessionBootstrapInitProcessManager(
        PlanningCommandUseCase planningCommandUseCase,
        ContextCompileUseCase contextCompileUseCase,
        ObjectMapper objectMapper,
        @Value("${agentx.session.bootstrap.init.module-name:bootstrap}") String initModuleName,
        @Value("${agentx.session.bootstrap.init.module-description:Session bootstrap module for initialization gate task.}") String initModuleDescription,
        @Value("${agentx.session.bootstrap.init.task-title:Initialize project baseline and runtime workspace}") String initTaskTitle,
        @Value("${agentx.session.bootstrap.init.task-template-id:tmpl.init.v0}") String initTaskTemplateId,
        @Value("${agentx.session.bootstrap.init.required-toolpacks-json:[\"TP-GIT-2\",\"TP-JAVA-21\",\"TP-MAVEN-3\"]}") String initRequiredToolpacksJson,
        @Value("${agentx.session.bootstrap.init.context-trigger-type:MANUAL_REFRESH}") String initContextTriggerType,
        @Value("${agentx.session.bootstrap.init.compile-verify-context:true}") boolean compileVerifyContext
    ) {
        this.planningCommandUseCase = planningCommandUseCase;
        this.contextCompileUseCase = contextCompileUseCase;
        this.objectMapper = objectMapper;
        this.initModuleName = requireNotBlank(initModuleName, "initModuleName");
        this.initModuleDescription = normalizeNullable(initModuleDescription);
        this.initTaskTitle = requireNotBlank(initTaskTitle, "initTaskTitle");
        this.initTaskTemplateId = normalizeInitTemplate(initTaskTemplateId);
        this.initRequiredToolpacksJson = normalizeRequiredToolpacksJson(initRequiredToolpacksJson);
        this.initContextTriggerType = requireNotBlank(initContextTriggerType, "initContextTriggerType");
        this.compileVerifyContext = compileVerifyContext;
    }

    public void handle(SessionCreatedEvent event) {
        if (event == null) {
            throw new IllegalArgumentException("event must not be null");
        }
        String sessionId = requireNotBlank(event.sessionId(), "sessionId");
        WorkModule module = planningCommandUseCase.createModule(
            sessionId,
            initModuleName,
            initModuleDescription
        );
        WorkTask initTask = planningCommandUseCase.createTask(
            module.moduleId(),
            initTaskTitle,
            initTaskTemplateId,
            initRequiredToolpacksJson,
            List.of()
        );
        contextCompileUseCase.compileTaskContextPack(
            initTask.taskId(),
            "IMPL",
            initContextTriggerType
        );
        if (compileVerifyContext) {
            contextCompileUseCase.compileTaskContextPack(
                initTask.taskId(),
                "VERIFY",
                initContextTriggerType
            );
        }
    }

    private String normalizeRequiredToolpacksJson(String jsonText) {
        String normalized = requireNotBlank(jsonText, "initRequiredToolpacksJson");
        try {
            JsonNode root = objectMapper.readTree(normalized);
            if (root == null || !root.isArray()) {
                throw new IllegalArgumentException("initRequiredToolpacksJson must be JSON array text");
            }
            LinkedHashSet<String> ids = new LinkedHashSet<>();
            for (JsonNode node : root) {
                if (!node.isTextual()) {
                    throw new IllegalArgumentException("initRequiredToolpacksJson element must be string");
                }
                String id = node.asText().trim();
                if (!id.isEmpty()) {
                    ids.add(id);
                }
            }
            if (ids.isEmpty()) {
                throw new IllegalArgumentException("initRequiredToolpacksJson must not be empty");
            }
            ArrayNode canonical = objectMapper.createArrayNode();
            for (String id : ids) {
                canonical.add(id);
            }
            return objectMapper.writeValueAsString(canonical);
        } catch (RuntimeException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to normalize init required toolpacks json", ex);
        }
    }

    private static String normalizeInitTemplate(String templateId) {
        String normalized = requireNotBlank(templateId, "initTaskTemplateId")
            .toLowerCase(Locale.ROOT);
        if (!"tmpl.init.v0".equals(normalized)) {
            throw new IllegalArgumentException("initTaskTemplateId must be tmpl.init.v0");
        }
        return normalized;
    }

    private static String requireNotBlank(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }

    private static String normalizeNullable(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        return normalized.isEmpty() ? null : normalized;
    }
}
