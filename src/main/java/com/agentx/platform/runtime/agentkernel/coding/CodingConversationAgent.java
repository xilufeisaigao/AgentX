package com.agentx.platform.runtime.agentkernel.coding;

import com.agentx.platform.domain.catalog.model.AgentDefinition;
import com.agentx.platform.domain.planning.model.WorkTask;
import com.agentx.platform.runtime.application.workflow.WorkflowProfileRef;
import com.agentx.platform.runtime.application.workflow.profile.ActiveStackProfileSnapshot;
import com.agentx.platform.runtime.application.workflow.profile.StackProfileRegistry;
import com.agentx.platform.runtime.agentkernel.model.ModelGateway;
import com.agentx.platform.runtime.agentkernel.model.StructuredModelResult;
import com.agentx.platform.runtime.context.CompiledContextPack;
import com.agentx.platform.runtime.tooling.CompiledToolCatalog;
import com.agentx.platform.runtime.tooling.ExplorationCommandSpec;
import com.agentx.platform.runtime.tooling.ToolCatalogEntry;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Component
public class CodingConversationAgent {

    private static final String PROMPT_VERSION = "coding-v3-unix-exploration";

    private final ModelGateway modelGateway;
    private final StackProfileRegistry stackProfileRegistry;

    public CodingConversationAgent(ModelGateway modelGateway, StackProfileRegistry stackProfileRegistry) {
        this.modelGateway = modelGateway;
        this.stackProfileRegistry = stackProfileRegistry;
    }

    public StructuredModelResult<CodingAgentDecision> evaluate(
            AgentDefinition agentDefinition,
            CompiledContextPack contextPack,
            String recentTurnSummary
    ) {
        return evaluate(agentDefinition, contextPack, recentTurnSummary, stackProfileRegistry.defaultProfileRef());
    }

    public StructuredModelResult<CodingAgentDecision> evaluate(
            AgentDefinition agentDefinition,
            CompiledContextPack contextPack,
            String recentTurnSummary,
            WorkflowProfileRef workflowProfile
    ) {
        ActiveStackProfileSnapshot activeProfile = stackProfileRegistry.resolveRequired(workflowProfile.profileId());
        return modelGateway.generateStructuredObject(
                agentDefinition,
                systemPrompt(activeProfile),
                userPrompt(contextPack, recentTurnSummary, activeProfile),
                CodingAgentDecision.class
        );
    }

    public String promptVersion() {
        return PROMPT_VERSION;
    }

    private String systemPrompt(ActiveStackProfileSnapshot activeProfile) {
        return """
                你是 AgentX 的编码代理。
                你每一轮只能输出一个结构化决策。

                只允许三种决策：
                - TOOL_CALL
                - ASK_BLOCKER
                - DELIVER

                规则：
                - TOOL_CALL 必须从上下文里的 toolCatalog 中选一个可见工具与 operation。
                - 先看结构化边界，再做 Unix exploration，最后才写文件。
                - 需要浏览目录时优先使用 tool-filesystem.list_directory，不要把空 path 的 read_file 当正式协议。
                - 优先使用 tool-filesystem.list_directory、glob_files、grep_text、read_range、tool-git.git_status。
                - tool-shell.run_exploration_command 只能提供 commandId 和结构化参数，绝不能输出任意 shell 字符串。
                - tool-shell.run_command 只能用于执行/验证/交付命令，不能拿来做代码探索。
                - 不允许自己拼接 shell、bash、sh、powershell 或任何命令字符串。
                - write_file 和 delete_file 只能落在给定 write scope 内。
                - exploration 命令是只读补充，不能绕过写边界，也不能假装成执行类命令。
                - 如果 prompt 里已经列出可用工具目录，就不能再声称“缺少 toolCatalog”。
                - 如果 recentTurnSummary 已包含目录浏览结果，优先基于现有结果继续 read_range、grep_text、git_status，而不是重复列根目录。
                - 如果需要修改写域外路径、需要额外权限、或当前边界不足以安全推进，必须用 ASK_BLOCKER。
                - 任务完成后才用 DELIVER。
                - 输出必须是结构化 JSON，且只包含一个决策。

                当前技术栈 profile：
                - profileId=`%s`
                - displayName=`%s`
                - workspaceShape=`%s`

                该 profile 的编码提醒：
                %s
                """.formatted(
                activeProfile.profileId(),
                activeProfile.displayName(),
                activeProfile.workspaceShapeSummary(),
                rulesBlock(activeProfile.codingRules())
        );
    }

    private String userPrompt(
            CompiledContextPack contextPack,
            String recentTurnSummary,
            ActiveStackProfileSnapshot activeProfile
    ) {
        return """
                promptVersion=%s
                recentTurnSummary=%s

                执行合同摘要：
                %s

                编码策略提醒：
                - 如果上面的“可用工具目录”非空，说明 toolCatalog 已经提供，禁止再以缺少 toolCatalog 为由 ASK_BLOCKER。
                - 如果 recentTurnSummary 已包含 `tool-filesystem.list_directory` 的结果，下一步应优先读取具体文件、grep、read_range 或 git_status，而不是重复列根目录。
                - 如果只是探索代码，不要调用 `tool-shell.run_command`；只有 build/verify/delivery 时才使用执行类 commandIds。
                - 当前 profile 工作区形状：%s。

                请基于下面的上下文 JSON 决定下一步单个动作：

                %s
                """.formatted(
                PROMPT_VERSION,
                recentTurnSummary,
                contractSummary(contextPack),
                activeProfile.workspaceShapeSummary(),
                contextPack.contentJson()
        );
    }

    private String contractSummary(CompiledContextPack contextPack) {
        Map<String, Object> sections = contextPack.factBundle().sections();
        String taskSummary = "(task unavailable)";
        Object taskObject = sections.get("task");
        if (taskObject instanceof WorkTask task) {
            taskSummary = "taskId=`%s`, template=`%s`, objective=`%s`, writeScopes=%s".formatted(
                    task.taskId(),
                    task.taskTemplateId(),
                    task.objective(),
                    task.writeScopes().stream().map(writeScope -> "`" + writeScope.path() + "`").collect(Collectors.joining(", ", "[", "]"))
            );
        } else if (taskObject instanceof Map<?, ?> taskMap) {
            taskSummary = "taskId=`%s`, template=`%s`, objective=`%s`, writeScopes=%s".formatted(
                    stringValue(taskMap.get("taskId"), "unknown-task"),
                    stringValue(taskMap.get("taskTemplateId"), "unknown-template"),
                    stringValue(taskMap.get("objective"), "unknown-objective"),
                    listSummary(taskMap.get("writeScopes"))
            );
        }
        Object guardrails = sections.get("runtimeGuardrails");
        String toolSummary = "(toolCatalog unavailable)";
        Object toolCatalogObject = guardrailValue(sections, guardrails, "toolCatalog");
        if (toolCatalogObject instanceof CompiledToolCatalog toolCatalog) {
            toolSummary = toolCatalog.entries().stream()
                    .map(this::toolEntrySummary)
                    .collect(Collectors.joining(System.lineSeparator()));
        } else if (toolCatalogObject instanceof Collection<?> rawEntries && !rawEntries.isEmpty()) {
            toolSummary = rawEntries.stream()
                    .map(this::toolEntrySummary)
                    .collect(Collectors.joining(System.lineSeparator()));
        }
        String executionCommandSummary = commandSummary(guardrailValue(sections, guardrails, "allowedCommandCatalog"));
        String explorationCommandSummary = explorationCommandSummary(guardrailValue(sections, guardrails, "explorationCommandCatalog"));
        return """
                - 任务摘要: %s
                - runtimePlatform: `%s`
                - shellFamily: `%s`
                - workspaceRoot: `%s`
                - repoRoot: `%s`
                - explorationRoots: %s
                - workspaceReadPolicy: `%s`
                - writeScopes: %s
                - 可用工具目录:
                %s
                - exploration commandIds: %s
                - execution commandIds: %s
                """.formatted(
                taskSummary,
                stringValue(guardrailValue(sections, guardrails, "runtimePlatform"), "unavailable"),
                stringValue(guardrailValue(sections, guardrails, "shellFamily"), "unavailable"),
                stringValue(guardrailValue(sections, guardrails, "workspaceRoot"), "unavailable"),
                stringValue(guardrailValue(sections, guardrails, "repoRoot"), "unavailable"),
                listSummary(guardrailValue(sections, guardrails, "explorationRoots")),
                stringValue(guardrailValue(sections, guardrails, "workspaceReadPolicy"), "unavailable"),
                writeScopeSummary(taskObject, guardrails),
                toolSummary,
                explorationCommandSummary,
                executionCommandSummary
        );
    }

    private String toolEntrySummary(ToolCatalogEntry entry) {
        return "  - `%s`: operations=%s".formatted(
                entry.toolId(),
                entry.allowedOperations().stream().map(operation -> "`" + operation + "`").collect(Collectors.joining(", ", "[", "]"))
        );
    }

    private String toolEntrySummary(Object rawEntry) {
        if (rawEntry instanceof ToolCatalogEntry entry) {
            return toolEntrySummary(entry);
        }
        if (rawEntry instanceof Map<?, ?> entryMap) {
            return "  - `%s`: operations=%s".formatted(
                    stringValue(entryMap.get("toolId"), "unknown-tool"),
                    listSummary(entryMap.get("allowedOperations"))
            );
        }
        return "  - " + String.valueOf(rawEntry);
    }

    private String commandSummary(Object rawCommandCatalog) {
        if (!(rawCommandCatalog instanceof Map<?, ?> commandCatalog) || commandCatalog.isEmpty()) {
            return "[]";
        }
        return commandCatalog.keySet().stream()
                .map(String::valueOf)
                .sorted()
                .map(commandId -> "`" + commandId + "`")
                .collect(Collectors.joining(", ", "[", "]"));
    }

    private String explorationCommandSummary(Object rawCommandCatalog) {
        if (!(rawCommandCatalog instanceof Map<?, ?> commandCatalog) || commandCatalog.isEmpty()) {
            return "[]";
        }
        return commandCatalog.entrySet().stream()
                .sorted((left, right) -> String.valueOf(left.getKey()).compareTo(String.valueOf(right.getKey())))
                .map(entry -> {
                    Object rawValue = entry.getValue();
                    String description = "";
                    if (rawValue instanceof ExplorationCommandSpec spec) {
                        description = spec.description();
                    } else if (rawValue instanceof Map<?, ?> valueMap) {
                        description = stringValue(valueMap.get("description"), "");
                    }
                    String label = "`" + entry.getKey() + "`";
                    return description.isBlank() ? label : label + " (" + description + ")";
                })
                .collect(Collectors.joining(", ", "[", "]"));
    }

    private Object guardrailValue(Map<String, Object> sections, Object rawGuardrails, String key) {
        if (sections.containsKey(key)) {
            return sections.get(key);
        }
        if (rawGuardrails instanceof Map<?, ?> guardrails) {
            return guardrails.get(key);
        }
        return null;
    }

    private String writeScopeSummary(Object taskObject, Object rawGuardrails) {
        if (taskObject instanceof WorkTask task) {
            return task.writeScopes().stream()
                    .map(writeScope -> "`" + writeScope.path() + "`")
                    .collect(Collectors.joining(", ", "[", "]"));
        }
        if (taskObject instanceof Map<?, ?> taskMap && taskMap.get("writeScopes") != null) {
            return listSummary(taskMap.get("writeScopes"));
        }
        if (rawGuardrails instanceof Map<?, ?> guardrails) {
            return listSummary(guardrails.get("writeScopes"));
        }
        return "[]";
    }

    private String listSummary(Object rawValue) {
        if (!(rawValue instanceof Collection<?> values) || values.isEmpty()) {
            return "[]";
        }
        return values.stream()
                .map(value -> "`" + String.valueOf(value) + "`")
                .collect(Collectors.joining(", ", "[", "]"));
    }

    private String stringValue(Object rawValue, String defaultValue) {
        if (rawValue == null) {
            return defaultValue;
        }
        String value = String.valueOf(rawValue);
        return value.isBlank() ? defaultValue : value;
    }

    private String rulesBlock(List<String> rules) {
        if (rules.isEmpty()) {
            return "- 无额外 profile 编码约束";
        }
        return rules.stream()
                .map(rule -> "- " + rule)
                .collect(Collectors.joining(System.lineSeparator()));
    }
}
