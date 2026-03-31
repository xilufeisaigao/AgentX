package com.agentx.platform.runtime.application.workflow;

import com.agentx.platform.domain.planning.model.TaskCapabilityRequirement;
import com.agentx.platform.domain.planning.model.TaskDependency;
import com.agentx.platform.domain.planning.model.WorkModule;
import com.agentx.platform.domain.planning.model.WorkTask;
import com.agentx.platform.domain.planning.model.WorkTaskStatus;
import com.agentx.platform.domain.planning.port.PlanningStore;
import com.agentx.platform.domain.shared.model.ActorRef;
import com.agentx.platform.domain.shared.model.ActorType;
import com.agentx.platform.domain.shared.model.WriteScope;
import com.agentx.platform.runtime.agentkernel.architect.PlanningGraphSpec;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Component
public class PlanningGraphMaterializer {

    private static final Set<WorkTaskStatus> REPLAN_MUTABLE_STATUSES = Set.of(
            WorkTaskStatus.PLANNED,
            WorkTaskStatus.READY,
            WorkTaskStatus.BLOCKED
    );

    private final PlanningStore planningStore;
    private final TaskTemplateCatalog taskTemplateCatalog;
    private final WorkflowScenarioResolver workflowScenarioResolver;

    public PlanningGraphMaterializer(
            PlanningStore planningStore,
            TaskTemplateCatalog taskTemplateCatalog,
            WorkflowScenarioResolver workflowScenarioResolver
    ) {
        this.planningStore = planningStore;
        this.taskTemplateCatalog = taskTemplateCatalog;
        this.workflowScenarioResolver = workflowScenarioResolver;
    }

    public int materialize(String workflowRunId, PlanningGraphSpec graphSpec) {
        String profileId = workflowScenarioResolver.resolveProfileId(workflowRunId);
        Set<String> plannedTaskIds = new HashSet<>();
        for (PlanningGraphSpec.ModulePlan modulePlan : graphSpec.modules()) {
            planningStore.saveModule(new WorkModule(
                    moduleId(workflowRunId, modulePlan.moduleKey()),
                    workflowRunId,
                    modulePlan.name(),
                    modulePlan.description()
            ));
        }
        for (PlanningGraphSpec.TaskPlan taskPlan : graphSpec.tasks()) {
            TaskTemplateCatalog.TaskTemplateDefinition templateDefinition = taskTemplateCatalog.find(profileId, taskPlan.taskTemplateId())
                    .orElseThrow(() -> new IllegalArgumentException("unsupported task template: " + taskPlan.taskTemplateId()));
            if (!templateDefinition.capabilityPackId().equals(taskPlan.capabilityPackId())) {
                throw new IllegalArgumentException(
                        "task capability pack does not match template: "
                                + taskPlan.taskTemplateId()
                                + " requires "
                                + templateDefinition.capabilityPackId()
                                + " but got "
                                + taskPlan.capabilityPackId()
                );
            }
            String moduleId = moduleId(workflowRunId, taskPlan.moduleKey());
            String taskId = taskId(workflowRunId, taskPlan.taskKey());
            plannedTaskIds.add(taskId);
            List<WriteScope> writeScopes = taskPlan.writeScopes().isEmpty()
                    ? templateDefinition.defaultWriteScopes()
                    : taskPlan.writeScopes();
            for (WriteScope writeScope : writeScopes) {
                if (!taskTemplateCatalog.allowsWriteScope(templateDefinition, writeScope)) {
                    throw new IllegalArgumentException(
                            "task write scope exceeds template boundary: "
                                    + taskPlan.taskTemplateId()
                                    + " -> "
                                    + writeScope.path()
                    );
                }
            }
            WorkTask existingTask = planningStore.findTask(taskId).orElse(null);
            if (existingTask == null || REPLAN_MUTABLE_STATUSES.contains(existingTask.status())) {
                planningStore.saveTask(new WorkTask(
                        taskId,
                        moduleId,
                        taskPlan.title(),
                        taskPlan.objective(),
                        taskPlan.taskTemplateId(),
                        WorkTaskStatus.READY,
                        writeScopes,
                        null,
                        new ActorRef(ActorType.AGENT, "architect-agent")
                ));
            }
            planningStore.saveCapabilityRequirement(new TaskCapabilityRequirement(
                    taskId,
                    taskPlan.capabilityPackId(),
                    true,
                    "PRIMARY"
            ));
        }
        for (PlanningGraphSpec.DependencyPlan dependencyPlan : graphSpec.dependencies()) {
            planningStore.saveDependency(new TaskDependency(
                    taskId(workflowRunId, dependencyPlan.taskKey()),
                    taskId(workflowRunId, dependencyPlan.dependsOnTaskKey()),
                    WorkTaskStatus.DONE
            ));
        }

        for (WorkTask existingTask : planningStore.listTasksByWorkflow(workflowRunId)) {
            if (plannedTaskIds.contains(existingTask.taskId())) {
                continue;
            }
            if (REPLAN_MUTABLE_STATUSES.contains(existingTask.status())) {
                planningStore.saveTask(new WorkTask(
                        existingTask.taskId(),
                        existingTask.moduleId(),
                        existingTask.title(),
                        existingTask.objective(),
                        existingTask.taskTemplateId(),
                        WorkTaskStatus.CANCELED,
                        existingTask.writeScopes(),
                        existingTask.originTicketId(),
                        existingTask.createdBy()
                ));
            }
        }
        return plannedTaskIds.size();
    }

    public String moduleId(String workflowRunId, String moduleKey) {
        return "module-" + shortToken(workflowRunId, moduleKey);
    }

    public String taskId(String workflowRunId, String taskKey) {
        return "task-" + shortToken(workflowRunId, taskKey);
    }

    private String shortToken(String... values) {
        return UUID.nameUUIDFromBytes(String.join("|", values).getBytes(StandardCharsets.UTF_8))
                .toString()
                .replace("-", "")
                .substring(0, 24);
    }
}
