package com.agentx.platform.runtime.agentruntime.local;

import com.agentx.platform.domain.execution.model.GitWorkspace;
import com.agentx.platform.domain.planning.model.WorkTask;
import com.agentx.platform.domain.shared.model.JsonPayload;
import com.agentx.platform.runtime.application.workflow.WorkflowScenario;
import org.springframework.stereotype.Component;

@Component
public class LocalCodingAgent {

    public CodingTaskOutcome execute(WorkTask task, int attemptNumber, WorkflowScenario scenario, GitWorkspace workspace) {
        if (scenario.requireHumanClarification() && attemptNumber == 1) {
            return new CodingTaskOutcome(
                    CodingOutcomeType.NEED_CLARIFICATION,
                    "首次执行发现缺失事实，当前任务需要澄清后才能继续。",
                    !scenario.architectCanAutoResolveClarification(),
                    new JsonPayload("""
                            {
                              "question":"healthz 是否需要附带额外探测语义？"
                            }
                            """)
            );
        }
        return new CodingTaskOutcome(
                CodingOutcomeType.SUCCEEDED,
                "本地 fake 编码代理已生成交付候选。",
                false,
                new JsonPayload("""
                        {
                          "workspace":"%s",
                          "result":"delivered"
                        }
                        """.formatted(workspace.worktreePath().replace("\\", "\\\\")))
        );
    }

    public record CodingTaskOutcome(
            CodingOutcomeType type,
            String body,
            boolean requiresHuman,
            JsonPayload payload
    ) {
    }

    public enum CodingOutcomeType {
        SUCCEEDED,
        NEED_CLARIFICATION
    }
}
