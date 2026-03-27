package com.agentx.platform.domain.flow.port;

import com.agentx.platform.domain.flow.model.WorkflowNodeBinding;
import com.agentx.platform.domain.flow.model.WorkflowNodeRun;
import com.agentx.platform.domain.flow.model.WorkflowRun;
import com.agentx.platform.domain.flow.model.WorkflowTemplate;
import com.agentx.platform.domain.flow.model.WorkflowTemplateNode;

import java.util.List;
import java.util.Optional;

public interface FlowStore {

    Optional<WorkflowTemplate> findTemplate(String workflowTemplateId);

    List<WorkflowTemplateNode> listTemplateNodes(String workflowTemplateId);

    Optional<WorkflowRun> findRun(String workflowRunId);

    List<WorkflowNodeBinding> listNodeBindings(String workflowRunId);

    List<WorkflowNodeRun> listNodeRuns(String workflowRunId);

    void saveRun(WorkflowRun workflowRun);

    void saveNodeBinding(WorkflowNodeBinding binding);
}
