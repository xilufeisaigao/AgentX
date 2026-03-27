package com.agentx.platform.runtime.persistence.mybatis.repository;

import com.agentx.platform.domain.flow.model.EntryMode;
import com.agentx.platform.domain.flow.model.WorkflowBindingMode;
import com.agentx.platform.domain.flow.model.WorkflowMutability;
import com.agentx.platform.domain.flow.model.WorkflowNodeBinding;
import com.agentx.platform.domain.flow.model.WorkflowNodeKind;
import com.agentx.platform.domain.flow.model.WorkflowNodeRun;
import com.agentx.platform.domain.flow.model.WorkflowNodeRunStatus;
import com.agentx.platform.domain.flow.model.WorkflowRun;
import com.agentx.platform.domain.flow.model.WorkflowRunStatus;
import com.agentx.platform.domain.flow.model.WorkflowTemplate;
import com.agentx.platform.domain.flow.model.WorkflowTemplateNode;
import com.agentx.platform.domain.flow.port.FlowStore;
import com.agentx.platform.domain.shared.model.ActorRef;
import com.agentx.platform.domain.shared.model.ActorType;
import com.agentx.platform.runtime.persistence.mybatis.mapper.FlowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;
import java.util.Optional;

@Repository
public class MybatisFlowRepository implements FlowStore {

    private final FlowMapper flowMapper;

    public MybatisFlowRepository(FlowMapper flowMapper) {
        this.flowMapper = flowMapper;
    }

    @Override
    public Optional<WorkflowTemplate> findTemplate(String workflowTemplateId) {
        Map<String, Object> row = flowMapper.findTemplateRow(workflowTemplateId);
        if (MybatisRowReader.isEmpty(row)) {
            return Optional.empty();
        }
        List<WorkflowTemplateNode> nodes = listTemplateNodes(workflowTemplateId);
        return Optional.of(new WorkflowTemplate(
                MybatisRowReader.string(row, "workflowTemplateId"),
                MybatisRowReader.string(row, "displayName"),
                MybatisRowReader.string(row, "description"),
                MybatisRowReader.enumValue(row, "mutability", WorkflowMutability.class),
                MybatisRowReader.string(row, "registrationPolicy"),
                MybatisRowReader.bool(row, "systemBuiltin"),
                MybatisRowReader.bool(row, "enabled"),
                MybatisRowReader.string(row, "version"),
                nodes
        ));
    }

    @Override
    public List<WorkflowTemplateNode> listTemplateNodes(String workflowTemplateId) {
        return flowMapper.listTemplateNodeRows(workflowTemplateId).stream()
                .map(row -> new WorkflowTemplateNode(
                        MybatisRowReader.string(row, "workflowTemplateId"),
                        MybatisRowReader.string(row, "nodeId"),
                        MybatisRowReader.string(row, "displayName"),
                        MybatisRowReader.enumValue(row, "nodeKind", WorkflowNodeKind.class),
                        MybatisRowReader.integer(row, "sequenceNo"),
                        MybatisRowReader.nullableString(row, "defaultAgentId"),
                        MybatisRowReader.bool(row, "agentBindingConfigurable")
                ))
                .toList();
    }

    @Override
    public Optional<WorkflowRun> findRun(String workflowRunId) {
        Map<String, Object> row = flowMapper.findRunRow(workflowRunId);
        if (MybatisRowReader.isEmpty(row)) {
            return Optional.empty();
        }
        return Optional.of(new WorkflowRun(
                MybatisRowReader.string(row, "workflowRunId"),
                MybatisRowReader.string(row, "workflowTemplateId"),
                MybatisRowReader.string(row, "title"),
                MybatisRowReader.enumValue(row, "status", WorkflowRunStatus.class),
                MybatisRowReader.enumValue(row, "entryMode", EntryMode.class),
                MybatisRowReader.bool(row, "autoAgentMode"),
                actor(row, "createdByActorType", "createdByActorId")
        ));
    }

    @Override
    public List<WorkflowNodeBinding> listNodeBindings(String workflowRunId) {
        return flowMapper.listNodeBindingRows(workflowRunId).stream()
                .map(row -> new WorkflowNodeBinding(
                        MybatisRowReader.string(row, "bindingId"),
                        MybatisRowReader.string(row, "workflowRunId"),
                        MybatisRowReader.string(row, "nodeId"),
                        MybatisRowReader.enumValue(row, "bindingMode", WorkflowBindingMode.class),
                        MybatisRowReader.string(row, "selectedAgentId"),
                        MybatisRowReader.bool(row, "lockedByUser")
                ))
                .toList();
    }

    @Override
    public List<WorkflowNodeRun> listNodeRuns(String workflowRunId) {
        return flowMapper.listNodeRunRows(workflowRunId).stream()
                .map(row -> new WorkflowNodeRun(
                        MybatisRowReader.string(row, "nodeRunId"),
                        MybatisRowReader.string(row, "workflowRunId"),
                        MybatisRowReader.string(row, "nodeId"),
                        MybatisRowReader.nullableString(row, "selectedAgentId"),
                        MybatisRowReader.nullableString(row, "agentInstanceId"),
                        MybatisRowReader.enumValue(row, "status", WorkflowNodeRunStatus.class)
                ))
                .toList();
    }

    @Override
    public void saveRun(WorkflowRun workflowRun) {
        flowMapper.upsertRun(
                workflowRun,
                workflowRun.createdBy().type().name(),
                workflowRun.createdBy().actorId()
        );
    }

    @Override
    public void saveNodeBinding(WorkflowNodeBinding binding) {
        flowMapper.upsertNodeBinding(binding, null);
    }

    private ActorRef actor(Map<String, Object> row, String typeKey, String actorIdKey) {
        return new ActorRef(
                MybatisRowReader.enumValue(row, typeKey, ActorType.class),
                MybatisRowReader.string(row, actorIdKey)
        );
    }
}
