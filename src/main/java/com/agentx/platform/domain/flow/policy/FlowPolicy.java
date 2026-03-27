package com.agentx.platform.domain.flow.policy;

import com.agentx.platform.domain.flow.model.WorkflowNodeBinding;
import com.agentx.platform.domain.flow.model.WorkflowRun;
import com.agentx.platform.domain.flow.model.WorkflowRunStatus;
import com.agentx.platform.domain.flow.model.WorkflowTemplate;
import com.agentx.platform.domain.flow.model.WorkflowTemplateNode;
import com.agentx.platform.domain.shared.error.DomainRuleViolation;

public final class FlowPolicy {

    private FlowPolicy() {
    }

    public static void assertTemplateRunnable(WorkflowTemplate template) {
        if (!template.enabled()) {
            throw new DomainRuleViolation("workflow template must be enabled");
        }
        if (template.nodes().isEmpty()) {
            throw new DomainRuleViolation("workflow template must declare nodes");
        }
    }

    public static void assertBindingAllowed(WorkflowTemplateNode node, WorkflowNodeBinding binding) {
        if (!node.agentBindingConfigurable() && binding.lockedByUser()) {
            throw new DomainRuleViolation("non-configurable node cannot be user locked");
        }
    }

    public static void assertRunCanAdvance(WorkflowRun run) {
        if (run.status() == WorkflowRunStatus.COMPLETED
                || run.status() == WorkflowRunStatus.CANCELED
                || run.status() == WorkflowRunStatus.FAILED) {
            throw new DomainRuleViolation("closed workflow run cannot advance");
        }
    }
}
