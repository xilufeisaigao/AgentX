package com.agentx.platform.runtime.agentkernel.architect;

import com.agentx.platform.domain.catalog.model.AgentDefinition;
import com.agentx.platform.runtime.application.workflow.TaskTemplateCatalog;
import com.agentx.platform.runtime.application.workflow.WorkflowProfileRef;
import com.agentx.platform.runtime.application.workflow.profile.ActiveStackProfileSnapshot;
import com.agentx.platform.runtime.agentkernel.model.ModelGateway;
import com.agentx.platform.runtime.agentkernel.model.StructuredModelResult;
import com.agentx.platform.runtime.context.CompiledContextPack;
import org.springframework.stereotype.Component;

import java.util.stream.Collectors;

@Component
public class ArchitectConversationAgent {

    private static final String PROMPT_VERSION = "architect-v1";

    private final ModelGateway modelGateway;
    private final TaskTemplateCatalog taskTemplateCatalog;

    public ArchitectConversationAgent(ModelGateway modelGateway, TaskTemplateCatalog taskTemplateCatalog) {
        this.modelGateway = modelGateway;
        this.taskTemplateCatalog = taskTemplateCatalog;
    }

    public StructuredModelResult<ArchitectDecision> evaluate(AgentDefinition agentDefinition, CompiledContextPack contextPack) {
        return evaluate(agentDefinition, contextPack, taskTemplateCatalog.defaultProfile().toProfileRef());
    }

    public StructuredModelResult<ArchitectDecision> evaluate(
            AgentDefinition agentDefinition,
            CompiledContextPack contextPack,
            WorkflowProfileRef workflowProfile
    ) {
        ActiveStackProfileSnapshot activeProfile = taskTemplateCatalog.resolveProfile(workflowProfile.profileId());
        return modelGateway.generateStructuredObject(
                agentDefinition,
                systemPrompt(activeProfile),
                userPrompt(contextPack),
                ArchitectDecision.class
        );
    }

    public String promptVersion() {
        return PROMPT_VERSION;
    }

    private String systemPrompt(ActiveStackProfileSnapshot activeProfile) {
        return """
                你是 AgentX 的架构代理。
                你的职责只有四个：
                1. 判断当前是否还缺关键事实；
                2. 在信息足够时输出初始任务图；
                3. 在已有任务图基础上做有限 replan；
                4. 在确实无需变更时返回 NO_CHANGES。

                规则：
                - 缺信息就返回 NEED_INPUT，并列出 gaps/questions。
                - 有新计划就返回 PLAN_READY 或 REPLAN_READY。
                - 不要输出自由文本任务列表，必须返回结构化 planningGraph。
                - taskTemplateId 只能从下方白名单中逐字选择，绝对不能自造别名或近义词。
                - capabilityPackId 必须与所选 taskTemplateId 绑定的值完全一致。
                - writeScopes 只能留空，或者填写白名单范围本身及其子路径；不要写过宽路径。
                - 不要直接做 coding 或 verify 结论。

                当前技术栈 profile：
                - profileId=`%s`
                - displayName=`%s`
                - workspaceShape=`%s`

                该 profile 的规划提醒：
                %s

                当前允许的任务模板目录：
                %s
                """.formatted(
                activeProfile.profileId(),
                activeProfile.displayName(),
                activeProfile.workspaceShapeSummary(),
                profileRulesBlock(activeProfile.architectRules()),
                supportedTemplateCatalogBlock(activeProfile)
        );
    }

    private String userPrompt(CompiledContextPack contextPack) {
        return """
                promptVersion=%s

                请基于下面的上下文 JSON 输出本轮架构决策：

                %s
                """.formatted(PROMPT_VERSION, contextPack.contentJson());
    }

    private String supportedTemplateCatalogBlock(ActiveStackProfileSnapshot activeProfile) {
        return taskTemplateCatalog.listTemplates(activeProfile.profileId()).stream()
                .map(template -> "- taskTemplateId=`%s`, capabilityPackId=`%s`, writeScopes=%s".formatted(
                        template.templateId(),
                        template.capabilityPackId(),
                        template.defaultWriteScopes().stream()
                                .map(writeScope -> "`" + writeScope.path() + "`")
                                .collect(Collectors.joining(", ", "[", "]"))
                ))
                .collect(Collectors.joining(System.lineSeparator()));
    }

    private String profileRulesBlock(java.util.List<String> rules) {
        if (rules.isEmpty()) {
            return "- 无额外 profile 规划约束";
        }
        return rules.stream()
                .map(rule -> "- " + rule)
                .collect(Collectors.joining(System.lineSeparator()));
    }
}
