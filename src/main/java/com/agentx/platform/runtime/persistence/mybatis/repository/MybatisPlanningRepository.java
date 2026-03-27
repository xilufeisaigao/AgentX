package com.agentx.platform.runtime.persistence.mybatis.repository;

import com.agentx.platform.domain.planning.model.TaskCapabilityRequirement;
import com.agentx.platform.domain.planning.model.TaskDependency;
import com.agentx.platform.domain.planning.model.WorkModule;
import com.agentx.platform.domain.planning.model.WorkTask;
import com.agentx.platform.domain.planning.model.WorkTaskStatus;
import com.agentx.platform.domain.planning.port.PlanningStore;
import com.agentx.platform.domain.shared.model.ActorRef;
import com.agentx.platform.domain.shared.model.ActorType;
import com.agentx.platform.runtime.persistence.mybatis.mapper.PlanningMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;

@Repository
public class MybatisPlanningRepository implements PlanningStore {

    private final PlanningMapper planningMapper;

    public MybatisPlanningRepository(PlanningMapper planningMapper) {
        this.planningMapper = planningMapper;
    }

    @Override
    public List<WorkModule> listModules(String workflowRunId) {
        return planningMapper.listModuleRows(workflowRunId).stream()
                .map(row -> new WorkModule(
                        MybatisRowReader.string(row, "moduleId"),
                        MybatisRowReader.string(row, "workflowRunId"),
                        MybatisRowReader.string(row, "name"),
                        MybatisRowReader.nullableString(row, "description")
                ))
                .toList();
    }

    @Override
    public List<WorkTask> listTasksByWorkflow(String workflowRunId) {
        return planningMapper.listTaskRows(workflowRunId).stream()
                .map(row -> new WorkTask(
                        MybatisRowReader.string(row, "taskId"),
                        MybatisRowReader.string(row, "moduleId"),
                        MybatisRowReader.string(row, "title"),
                        MybatisRowReader.string(row, "objective"),
                        MybatisRowReader.string(row, "taskTemplateId"),
                        MybatisRowReader.enumValue(row, "status", WorkTaskStatus.class),
                        MybatisRowReader.writeScopeList(row, "writeScopesJson"),
                        MybatisRowReader.nullableString(row, "originTicketId"),
                        actor(row, "createdByActorType", "createdByActorId")
                ))
                .toList();
    }

    @Override
    public List<TaskDependency> listDependencies(String workflowRunId) {
        return planningMapper.listDependencyRows(workflowRunId).stream()
                .map(row -> new TaskDependency(
                        MybatisRowReader.string(row, "taskId"),
                        MybatisRowReader.string(row, "dependsOnTaskId"),
                        MybatisRowReader.enumValue(row, "requiredUpstreamStatus", WorkTaskStatus.class)
                ))
                .toList();
    }

    @Override
    public List<TaskCapabilityRequirement> listCapabilityRequirements(String taskId) {
        return planningMapper.listCapabilityRequirementRows(taskId).stream()
                .map(row -> new TaskCapabilityRequirement(
                        MybatisRowReader.string(row, "taskId"),
                        MybatisRowReader.string(row, "capabilityPackId"),
                        MybatisRowReader.bool(row, "required"),
                        MybatisRowReader.string(row, "roleInTask")
                ))
                .toList();
    }

    @Override
    public void saveModule(WorkModule workModule) {
        planningMapper.upsertModule(workModule);
    }

    @Override
    public void saveTask(WorkTask workTask) {
        planningMapper.upsertTask(
                workTask,
                workTask.createdBy().type().name(),
                workTask.createdBy().actorId()
        );
    }

    @Override
    public void saveDependency(TaskDependency dependency) {
        planningMapper.upsertDependency(dependency);
    }

    @Override
    public void saveCapabilityRequirement(TaskCapabilityRequirement requirement) {
        planningMapper.upsertCapabilityRequirement(requirement);
    }

    private ActorRef actor(Map<String, Object> row, String typeKey, String actorIdKey) {
        return new ActorRef(
                MybatisRowReader.enumValue(row, typeKey, ActorType.class),
                MybatisRowReader.string(row, actorIdKey)
        );
    }
}
