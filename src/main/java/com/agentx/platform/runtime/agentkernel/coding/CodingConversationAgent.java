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
import com.agentx.platform.runtime.tooling.ToolCatalogEntry;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Component
public class CodingConversationAgent {

    private static final String PROMPT_VERSION = "coding-v2-tool-call";

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
                - 需要浏览目录时优先使用 tool-filesystem.list_directory，不要把空 path 的 read_file 当正式协议。
                - tool-shell.run_command 只能提供 commandId，绝不能输出任意 shell 字符串。
                - write_file 和 delete_file 只能落在给定 write scope 内。
                - 如果 prompt 里已经列出可用工具目录，就不能再声称“缺少 toolCatalog”。
                - 如果 recentTurnSummary 已包含目录浏览结果，优先基于现有结果继续 read_file/search_text/git_status，而不是重复列根目录。
                - 缺关键事实时用 ASK_BLOCKER。
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
                - 如果 recentTurnSummary 已包含 `tool-filesystem.list_directory` 的结果，下一步应优先读取具体文件、搜索符号或执行 allowlisted command，而不是重复列根目录。
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
        }
        String toolSummary = "(toolCatalog unavailable)";
        Object toolCatalogObject = sections.get("toolCatalog");
        if (toolCatalogObject instanceof CompiledToolCatalog toolCatalog) {
            toolSummary = toolCatalog.entries().stream()
                    .map(this::toolEntrySummary)
                    .collect(Collectors.joining(System.lineSeparator()));
        }
        String commandSummary = commandSummary(sections.get("allowedCommandCatalog"));
        return """
                - 任务摘要: %s
                - 可用工具目录:
                %s
                - allowlisted commandIds: %s
                """.formatted(taskSummary, toolSummary, commandSummary);
    }

    private String toolEntrySummary(ToolCatalogEntry entry) {
        return "  - `%s`: operations=%s".formatted(
                entry.toolId(),
                entry.allowedOperations().stream().map(operation -> "`" + operation + "`").collect(Collectors.joining(", ", "[", "]"))
        );
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

    private String rulesBlock(List<String> rules) {
        if (rules.isEmpty()) {
            return "- 无额外 profile 编码约束";
        }
        return rules.stream()
                .map(rule -> "- " + rule)
                .collect(Collectors.joining(System.lineSeparator()));
    }
}
