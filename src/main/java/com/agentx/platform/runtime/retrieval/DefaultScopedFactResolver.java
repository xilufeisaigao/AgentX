package com.agentx.platform.runtime.retrieval;

import com.agentx.platform.domain.execution.port.ExecutionStore;
import com.agentx.platform.domain.flow.model.WorkflowNodeRun;
import com.agentx.platform.domain.flow.model.WorkflowRun;
import com.agentx.platform.domain.flow.port.FlowStore;
import com.agentx.platform.domain.intake.model.RequirementDoc;
import com.agentx.platform.domain.intake.model.RequirementVersion;
import com.agentx.platform.domain.intake.model.Ticket;
import com.agentx.platform.domain.intake.port.IntakeStore;
import com.agentx.platform.domain.planning.model.TaskDependency;
import com.agentx.platform.domain.planning.model.WorkModule;
import com.agentx.platform.domain.planning.model.WorkTask;
import com.agentx.platform.domain.planning.port.PlanningStore;
import com.agentx.platform.runtime.application.workflow.TaskExecutionContract;
import com.agentx.platform.runtime.context.ContextCompilationRequest;
import com.agentx.platform.runtime.context.ContextPackType;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Component
public class DefaultScopedFactResolver implements ScopedFactResolver {

    private final FlowStore flowStore;
    private final IntakeStore intakeStore;
    private final PlanningStore planningStore;
    private final ExecutionStore executionStore;
    private final ObjectMapper objectMapper;

    public DefaultScopedFactResolver(
            FlowStore flowStore,
            IntakeStore intakeStore,
            PlanningStore planningStore,
            ExecutionStore executionStore,
            ObjectMapper objectMapper
    ) {
        this.flowStore = flowStore;
        this.intakeStore = intakeStore;
        this.planningStore = planningStore;
        this.executionStore = executionStore;
        this.objectMapper = objectMapper;
    }

    @Override
    public Map<String, Object> resolveStructuredFacts(ContextCompilationRequest request) {
        Map<String, Object> sections = new LinkedHashMap<>();
        WorkflowRun workflowRun = flowStore.findRun(request.scope().workflowRunId())
                .orElseThrow(() -> new IllegalArgumentException("workflow run not found: " + request.scope().workflowRunId()));
        Optional<RequirementDoc> requirementDoc = intakeStore.findRequirementByWorkflow(workflowRun.workflowRunId());
        List<RequirementVersion> requirementVersions = requirementDoc
                .map(doc -> intakeStore.listRequirementVersions(doc.docId()))
                .orElse(List.of());
        List<Ticket> tickets = intakeStore.listTicketsForWorkflow(workflowRun.workflowRunId());
        List<WorkModule> modules = planningStore.listModules(workflowRun.workflowRunId());
        List<WorkTask> tasks = planningStore.listTasksByWorkflow(workflowRun.workflowRunId());
        List<TaskDependency> dependencies = planningStore.listDependencies(workflowRun.workflowRunId());
        List<WorkflowNodeRun> nodeRuns = flowStore.listNodeRuns(workflowRun.workflowRunId());

        sections.put("workflowRun", workflowRun);
        sections.put("requirementDoc", requirementDoc.orElse(null));
        sections.put("requirementVersions", requirementVersions);
        sections.put("tickets", tickets);
        sections.put("workflowEvents", flowStore.listRunEvents(workflowRun.workflowRunId()));
        sections.put("nodeRuns", nodeRuns);

        if (request.packType() == ContextPackType.REQUIREMENT || request.packType() == ContextPackType.ARCHITECT) {
            sections.put("modules", modules);
            sections.put("tasks", tasks);
            sections.put("dependencies", dependencies);
        }

        if (request.scope().taskId() != null) {
            WorkTask task = planningStore.findTask(request.scope().taskId())
                    .orElseThrow(() -> new IllegalArgumentException("task not found: " + request.scope().taskId()));
            sections.put("task", task);
            sections.put("taskCapabilities", planningStore.listCapabilityRequirements(task.taskId()));
            sections.put("taskDependencies", planningStore.listDependenciesForTask(task.taskId()));
            sections.put("taskRuns", executionStore.listTaskRuns(task.taskId()));
            sections.put("workspaces", executionStore.listWorkspaces(task.taskId()));
            sections.put("taskTickets", taskScopedTickets(task.taskId(), tickets));
            sections.put("upstreamTasks", upstreamTasks(task.taskId(), tasks));
            if (request.scope().runId() != null) {
                Object currentRun = executionStore.findTaskRun(request.scope().runId()).orElse(null);
                sections.put("currentRun", currentRun);
                sections.put("currentRunEvents", executionStore.listTaskRunEvents(request.scope().runId()));
                sections.put("currentWorkspace", executionStore.findWorkspaceByRun(request.scope().runId()).orElse(null));
                if (currentRun instanceof com.agentx.platform.domain.execution.model.TaskRun taskRun
                        && taskRun.executionContractJson() != null) {
                    TaskExecutionContract contract = parseContract(taskRun.executionContractJson().json());
                    sections.put("currentExecutionContract", contract);
                    sections.put("toolCatalog", contract.toolCatalog());
                    sections.put("runtimePacks", contract.runtimePacks());
                    sections.put("toolEnvironment", contract.toolEnvironment());
                    sections.put("allowedCommandCatalog", contract.allowedCommandCatalog());
                    sections.put("httpEndpointCatalog", contract.httpEndpointCatalog());
                    sections.put("runtimeGuardrails", Map.of(
                            "writeScopes", contract.writeScopes(),
                            "toolCatalog", contract.toolCatalog(),
                            "allowedCommandCatalog", contract.allowedCommandCatalog(),
                            "httpEndpointCatalog", contract.httpEndpointCatalog()
                    ));
                }
            }
        }

        return sections;
    }

    private TaskExecutionContract parseContract(String json) {
        try {
            return objectMapper.readValue(json, TaskExecutionContract.class);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("failed to parse task execution contract from run evidence", exception);
        }
    }

    private List<WorkTask> upstreamTasks(String taskId, List<WorkTask> tasks) {
        List<String> upstreamIds = planningStore.listDependenciesForTask(taskId).stream()
                .map(TaskDependency::dependsOnTaskId)
                .toList();
        return tasks.stream()
                .filter(task -> upstreamIds.contains(task.taskId()))
                .toList();
    }

    private List<Ticket> taskScopedTickets(String taskId, List<Ticket> tickets) {
        return tickets.stream()
                .filter(ticket -> {
                    if (taskId.equals(ticket.taskId())) {
                        return true;
                    }
                    if (ticket.taskId() != null || ticket.payloadJson() == null) {
                        return false;
                    }
                    String payload = ticket.payloadJson().json();
                    return payload.contains("\"taskId\":\"" + taskId + "\"")
                            || payload.contains("\"taskId\": \"" + taskId + "\"");
                })
                .toList();
    }
}
