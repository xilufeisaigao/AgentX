package com.agentx.platform.runtime.agentruntime.local;

import com.agentx.platform.domain.shared.model.WriteScope;
import com.agentx.platform.runtime.application.workflow.WorkflowScenario;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class LocalArchitectAgent {

    public PlannedTaskGraph plan(String workflowRunId) {
        String moduleId = "module-" + workflowRunId + "-core";
        String taskId = "task-" + workflowRunId + "-impl";
        return new PlannedTaskGraph(
                moduleId,
                "core-flow",
                "Fixed runtime v1 module",
                taskId,
                "实现固定主链编码任务",
                "产出一份可被 merge-gate 和 verify 接受的交付候选",
                List.of(new WriteScope("src/main/java"), new WriteScope("src/test/java")),
                "cap-java-backend-coding"
        );
    }

    public ClarificationDisposition reviewClarification(WorkflowScenario scenario) {
        if (scenario.architectCanAutoResolveClarification()) {
            return new ClarificationDisposition(
                    ClarificationAction.RESOLVE_DIRECTLY,
                    "架构代理已根据现有事实完成澄清，无需人工介入。"
            );
        }
        return new ClarificationDisposition(
                ClarificationAction.ESCALATE_TO_HUMAN,
                "当前缺失事实会影响任务继续执行，已转交给人类处理。"
        );
    }

    public ClarificationDisposition applyHumanAnswer() {
        return new ClarificationDisposition(
                ClarificationAction.APPLY_HUMAN_ANSWER,
                "已吸收人类回复，任务可以恢复执行。"
        );
    }

    public record PlannedTaskGraph(
            String moduleId,
            String moduleName,
            String moduleDescription,
            String taskId,
            String taskTitle,
            String taskObjective,
            List<WriteScope> writeScopes,
            String capabilityPackId
    ) {
    }

    public record ClarificationDisposition(
            ClarificationAction action,
            String body
    ) {
    }

    public enum ClarificationAction {
        RESOLVE_DIRECTLY,
        ESCALATE_TO_HUMAN,
        APPLY_HUMAN_ANSWER
    }
}
