package com.agentx.platform.runtime.application.workflow;

import com.agentx.platform.domain.planning.model.TaskCapabilityRequirement;
import com.agentx.platform.domain.planning.model.WorkTask;
import com.agentx.platform.domain.shared.model.JsonPayload;
import com.agentx.platform.domain.shared.model.WriteScope;
import com.agentx.platform.runtime.application.workflow.profile.ActiveStackProfileSnapshot;
import com.agentx.platform.runtime.tooling.ToolCall;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

@Component
public class DeterministicTaskExecutionContractBuilder implements TaskExecutionContractBuilder {

    private static final Pattern NON_PORTABLE_SEGMENT = Pattern.compile("[^a-zA-Z0-9._/-]");
    private static final String LINUX_CONTAINER = "LINUX_CONTAINER";
    private static final String POSIX_SH = "POSIX_SH";
    private static final String WORKSPACE_ROOT = "/workspace";
    private static final String BROAD_WORKSPACE = "BROAD_WORKSPACE";

    private final WorkflowScenarioResolver workflowScenarioResolver;
    private final CapabilityRuntimeAssembler capabilityRuntimeAssembler;
    private final TaskTemplateCatalog taskTemplateCatalog;
    private final ObjectMapper objectMapper;

    public DeterministicTaskExecutionContractBuilder(
            WorkflowScenarioResolver workflowScenarioResolver,
            CapabilityRuntimeAssembler capabilityRuntimeAssembler,
            TaskTemplateCatalog taskTemplateCatalog,
            ObjectMapper objectMapper
    ) {
        this.workflowScenarioResolver = workflowScenarioResolver;
        this.capabilityRuntimeAssembler = capabilityRuntimeAssembler;
        this.taskTemplateCatalog = taskTemplateCatalog;
        this.objectMapper = objectMapper;
    }

    @Override
    public TaskExecutionContract build(
            String workflowRunId,
            WorkTask task,
            List<TaskCapabilityRequirement> capabilityRequirements,
            int attemptNumber,
            WorkflowScenario scenario
    ) {
        ActiveStackProfileSnapshot activeProfile = taskTemplateCatalog.resolveProfile(workflowScenarioResolver.resolveProfileId(workflowRunId));
        CapabilityRuntimeAssembly assembly = capabilityRuntimeAssembler.assemble(activeProfile, capabilityRequirements);
        String markerFile = markerFile(task);
        Map<String, String> environment = new LinkedHashMap<>();
        environment.put("WORKFLOW_RUN_ID", workflowRunId);
        environment.put("TASK_ID", task.taskId());
        environment.put("ATTEMPT_NUMBER", String.valueOf(attemptNumber));
        environment.put("MARKER_FILE", markerFile);
        environment.put("TASK_TEMPLATE_ID", task.taskTemplateId());
        environment.put("TASK_TITLE", task.title());
        environment.put("REQUIRES_HUMAN_CLARIFICATION", Boolean.toString(scenario.requireHumanClarification()));
        Map<String, String> toolEnvironment = new LinkedHashMap<>(assembly.toolEnvironment());
        toolEnvironment.putAll(environment);
        toolEnvironment.put("WORKSPACE_ROOT", WORKSPACE_ROOT);
        return new TaskExecutionContract(
                assembly.image(),
                WORKSPACE_ROOT,
                List.of("sh", "-lc", "trap exit TERM INT; while true; do sleep 1; done"),
                environment,
                50,
                LINUX_CONTAINER,
                POSIX_SH,
                WORKSPACE_ROOT,
                WORKSPACE_ROOT,
                explorationRoots(task),
                BROAD_WORKSPACE,
                assembly.toolCatalog(),
                assembly.runtimePacks(),
                toolEnvironment,
                assembly.allowedCommandCatalog(),
                assembly.explorationCommandCatalog(),
                assembly.httpEndpointCatalog(),
                postDeliveryToolCalls(assembly),
                verifyToolCalls(assembly),
                task.writeScopes().stream().map(WriteScope::path).toList(),
                markerFile
        );
    }

    @Override
    public JsonPayload toPayload(TaskExecutionContract taskExecutionContract) {
        try {
            return new JsonPayload(objectMapper.writeValueAsString(taskExecutionContract));
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("failed to serialize task execution contract", exception);
        }
    }

    @Override
    public TaskExecutionContract fromPayload(JsonPayload payload) {
        try {
            return objectMapper.readValue(payload.json(), TaskExecutionContract.class);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("failed to parse task execution contract", exception);
        }
    }

    private String markerFile(WorkTask task) {
        String root = task.writeScopes().isEmpty() ? ".agentx-runtime" : sanitizeSegment(task.writeScopes().get(0).path());
        return root + "/.agentx-" + sanitizeSegment(task.taskId()) + ".txt";
    }

    private List<ToolCall> postDeliveryToolCalls(CapabilityRuntimeAssembly assembly) {
        return assembly.postDeliveryCommandIds().stream()
                .map(commandId -> new ToolCall(
                        "tool-shell",
                        "run_command",
                        Map.of("commandId", commandId),
                        "execute post-delivery command " + commandId
                ))
                .toList();
    }

    private List<ToolCall> verifyToolCalls(CapabilityRuntimeAssembly assembly) {
        List<ToolCall> toolCalls = new java.util.ArrayList<>();
        assembly.verifyCommandIds().forEach(commandId -> toolCalls.add(new ToolCall(
                "tool-shell",
                "run_command",
                Map.of("commandId", commandId),
                "execute verify command " + commandId
        )));
        if (assembly.toolCatalog().find("tool-git").isPresent()) {
            toolCalls.add(new ToolCall(
                    "tool-git",
                    "git_head",
                    Map.of(),
                    "capture merge-candidate HEAD commit"
            ));
        }
        return List.copyOf(toolCalls);
    }

    private List<String> explorationRoots(WorkTask task) {
        LinkedHashSet<String> roots = new LinkedHashSet<>();
        roots.add(".");
        task.writeScopes().stream()
                .map(WriteScope::path)
                .map(path -> path.replace('\\', '/'))
                .forEach(roots::add);
        return List.copyOf(roots);
    }

    private String sanitizeSegment(String rawValue) {
        return NON_PORTABLE_SEGMENT.matcher(rawValue.replace('\\', '/')).replaceAll("-");
    }
}
