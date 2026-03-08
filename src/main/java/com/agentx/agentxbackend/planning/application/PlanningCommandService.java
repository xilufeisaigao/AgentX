package com.agentx.agentxbackend.planning.application;

import com.agentx.agentxbackend.planning.application.port.in.PlanningCommandUseCase;
import com.agentx.agentxbackend.planning.application.port.in.TaskAllocationUseCase;
import com.agentx.agentxbackend.planning.application.port.in.TaskQueryUseCase;
import com.agentx.agentxbackend.planning.application.port.in.TaskStateMutationUseCase;
import com.agentx.agentxbackend.planning.application.port.in.WaitingTaskQueryUseCase;
import com.agentx.agentxbackend.planning.application.port.out.WorkTaskDependencyRepository;
import com.agentx.agentxbackend.planning.application.port.out.WorkModuleRepository;
import com.agentx.agentxbackend.planning.application.port.out.WorkTaskRepository;
import com.agentx.agentxbackend.planning.application.port.out.WorkerEligibilityPort;
import com.agentx.agentxbackend.planning.application.port.out.SessionDispatchPolicyPort;
import com.agentx.agentxbackend.planning.domain.model.TaskStatus;
import com.agentx.agentxbackend.planning.domain.model.TaskTemplateId;
import com.agentx.agentxbackend.planning.domain.model.WorkModule;
import com.agentx.agentxbackend.planning.domain.model.WorkTaskDependency;
import com.agentx.agentxbackend.planning.domain.model.WorkTask;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;

import java.time.Instant;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Service
public class PlanningCommandService implements PlanningCommandUseCase, TaskStateMutationUseCase, TaskAllocationUseCase,
    WaitingTaskQueryUseCase, TaskQueryUseCase {

    private static final String CREATED_BY_ROLE = "architect_agent";
    private static final TaskStatus DEFAULT_DEPENDENCY_REQUIRED_STATUS = TaskStatus.DONE;

    private final WorkModuleRepository workModuleRepository;
    private final WorkTaskRepository workTaskRepository;
    private final WorkTaskDependencyRepository workTaskDependencyRepository;
    private final WorkerEligibilityPort workerEligibilityPort;
    private final SessionDispatchPolicyPort sessionDispatchPolicyPort;
    private final ObjectMapper objectMapper;
    private final int claimScanBatchSize;
    private final int claimScanMaxRows;

    public PlanningCommandService(
        WorkModuleRepository workModuleRepository,
        WorkTaskRepository workTaskRepository,
        WorkTaskDependencyRepository workTaskDependencyRepository,
        WorkerEligibilityPort workerEligibilityPort,
        SessionDispatchPolicyPort sessionDispatchPolicyPort,
        ObjectMapper objectMapper,
        @Value("${agentx.planning.claim-scan.batch-size:64}") int claimScanBatchSize,
        @Value("${agentx.planning.claim-scan.max-rows:2048}") int claimScanMaxRows
    ) {
        this.workModuleRepository = workModuleRepository;
        this.workTaskRepository = workTaskRepository;
        this.workTaskDependencyRepository = workTaskDependencyRepository;
        this.workerEligibilityPort = workerEligibilityPort;
        this.sessionDispatchPolicyPort = sessionDispatchPolicyPort;
        this.objectMapper = objectMapper;
        this.claimScanBatchSize = clamp(claimScanBatchSize, 1, 500);
        this.claimScanMaxRows = Math.max(this.claimScanBatchSize, claimScanMaxRows);
    }

    @Override
    @Transactional
    public WorkModule createModule(String sessionId, String name, String description) {
        String normalizedSessionId = requireNotBlank(sessionId, "sessionId");
        String normalizedName = requireNotBlank(name, "name");
        String normalizedDescription = normalizeNullable(description);
        Instant now = Instant.now();
        WorkModule module = new WorkModule(
            generateModuleId(),
            normalizedSessionId,
            normalizedName,
            normalizedDescription,
            now,
            now
        );
        return workModuleRepository.save(module);
    }

    @Override
    @Transactional
    public WorkTask createTask(String moduleId, String title, String taskTemplateId, String requiredToolpacksJson) {
        return createTask(moduleId, title, taskTemplateId, requiredToolpacksJson, List.of());
    }

    @Override
    @Transactional
    public WorkTask createTask(
        String moduleId,
        String title,
        String taskTemplateId,
        String requiredToolpacksJson,
        List<String> dependsOnTaskIds
    ) {
        String normalizedModuleId = requireNotBlank(moduleId, "moduleId");
        String normalizedTitle = requireNotBlank(title, "title");
        TaskTemplateId parsedTemplateId = TaskTemplateId.fromValue(taskTemplateId);
        String normalizedRequiredToolpacksJson = normalizeRequiredToolpacksJson(requiredToolpacksJson);

        workModuleRepository.findById(normalizedModuleId)
            .orElseThrow(() -> new NoSuchElementException("Work module not found: " + normalizedModuleId));

        Instant now = Instant.now();
        WorkTask task = new WorkTask(
            generateTaskId(),
            normalizedModuleId,
            normalizedTitle,
            parsedTemplateId,
            TaskStatus.PLANNED,
            normalizedRequiredToolpacksJson,
            null,
            CREATED_BY_ROLE,
            now,
            now
        );
        WorkTask created = workTaskRepository.save(task);

        for (String dependsOnTaskId : normalizeDependsOnTaskIds(dependsOnTaskIds, created.taskId())) {
            addTaskDependencyInternal(
                created,
                dependsOnTaskId,
                DEFAULT_DEPENDENCY_REQUIRED_STATUS,
                now,
                false
            );
        }
        return recomputeDispatchStatus(created);
    }

    @Override
    @Transactional
    public WorkTaskDependency addTaskDependency(String taskId, String dependsOnTaskId, String requiredUpstreamStatus) {
        String normalizedTaskId = requireNotBlank(taskId, "taskId");
        String normalizedDependsOnTaskId = requireNotBlank(dependsOnTaskId, "dependsOnTaskId");
        TaskStatus requiredStatus = parseRequiredUpstreamStatus(requiredUpstreamStatus);
        return addTaskDependencyInternal(
            loadTaskOrThrow(normalizedTaskId),
            normalizedDependsOnTaskId,
            requiredStatus,
            Instant.now(),
            true
        );
    }

    @Override
    @Transactional
    public WorkTask markAssigned(String taskId, String runId) {
        WorkTask current = loadTaskOrThrow(taskId);
        String normalizedRunId = requireNotBlank(runId, "runId");
        if (current.status() != TaskStatus.READY_FOR_ASSIGN) {
            throw new IllegalStateException(
                "Task can be assigned only from READY_FOR_ASSIGN: " + current.taskId() + ", status=" + current.status()
            );
        }
        WorkTask updated = new WorkTask(
            current.taskId(),
            current.moduleId(),
            current.title(),
            current.taskTemplateId(),
            TaskStatus.ASSIGNED,
            current.requiredToolpacksJson(),
            normalizedRunId,
            current.createdByRole(),
            current.createdAt(),
            Instant.now()
        );
        return workTaskRepository.update(updated);
    }

    @Override
    @Transactional
    public WorkTask markDelivered(String taskId) {
        WorkTask current = loadTaskOrThrow(taskId);
        if (current.status() == TaskStatus.DELIVERED || current.status() == TaskStatus.DONE) {
            return current;
        }
        if (current.status() != TaskStatus.ASSIGNED) {
            throw new IllegalStateException(
                "Task can be delivered only from ASSIGNED: " + current.taskId() + ", status=" + current.status()
            );
        }
        WorkTask updated = new WorkTask(
            current.taskId(),
            current.moduleId(),
            current.title(),
            current.taskTemplateId(),
            TaskStatus.DELIVERED,
            current.requiredToolpacksJson(),
            null,
            current.createdByRole(),
            current.createdAt(),
            Instant.now()
        );
        return workTaskRepository.update(updated);
    }

    @Override
    @Transactional
    public WorkTask markDone(String taskId) {
        WorkTask current = loadTaskOrThrow(taskId);
        if (current.status() == TaskStatus.DONE) {
            return current;
        }
        if (!canTransitionToDone(current)) {
            throw new IllegalStateException(
                "Task can be done only from DELIVERED or ASSIGNED verify: "
                    + current.taskId()
                    + ", status="
                    + current.status()
                    + ", template="
                    + current.taskTemplateId().value()
            );
        }
        WorkTask updated = new WorkTask(
            current.taskId(),
            current.moduleId(),
            current.title(),
            current.taskTemplateId(),
            TaskStatus.DONE,
            current.requiredToolpacksJson(),
            null,
            current.createdByRole(),
            current.createdAt(),
            Instant.now()
        );
        WorkTask done = workTaskRepository.update(updated);

        List<WorkTaskDependency> dependents = workTaskDependencyRepository.findByDependsOnTaskId(done.taskId());
        for (WorkTaskDependency dependent : dependents) {
            workTaskRepository.findById(dependent.taskId()).ifPresent(this::recomputeDispatchStatus);
        }
        return done;
    }

    @Override
    @Transactional
    public WorkTask releaseAssignment(String taskId) {
        WorkTask current = loadTaskOrThrow(taskId);
        if (current.status() == TaskStatus.READY_FOR_ASSIGN && current.activeRunId() == null) {
            return current;
        }
        if (current.status() != TaskStatus.ASSIGNED) {
            throw new IllegalStateException(
                "Task assignment can be released only from ASSIGNED: " + current.taskId() + ", status=" + current.status()
            );
        }
        TaskStatus nextStatus = resolveDispatchStatus(current.taskId(), current.requiredToolpacksJson());
        WorkTask updated = new WorkTask(
            current.taskId(),
            current.moduleId(),
            current.title(),
            current.taskTemplateId(),
            nextStatus,
            current.requiredToolpacksJson(),
            null,
            current.createdByRole(),
            current.createdAt(),
            Instant.now()
        );
        return workTaskRepository.update(updated);
    }

    @Override
    @Transactional
    public WorkTask reopenDelivered(String taskId) {
        WorkTask current = loadTaskOrThrow(taskId);
        if (current.status() == TaskStatus.DONE) {
            throw new IllegalStateException(
                "Done task cannot be reopened: " + current.taskId()
            );
        }
        if (current.status() != TaskStatus.DELIVERED) {
            return current;
        }
        TaskStatus nextStatus = resolveDispatchStatus(current.taskId(), current.requiredToolpacksJson());
        WorkTask updated = new WorkTask(
            current.taskId(),
            current.moduleId(),
            current.title(),
            current.taskTemplateId(),
            nextStatus,
            current.requiredToolpacksJson(),
            null,
            current.createdByRole(),
            current.createdAt(),
            Instant.now()
        );
        return workTaskRepository.update(updated);
    }

    @Transactional
    public int refreshWaitingTasks(int limit) {
        int cappedLimit = limit <= 0 ? 100 : Math.min(limit, 500);
        List<WorkTask> waitingTasks = workTaskRepository.findByStatus(TaskStatus.WAITING_WORKER, cappedLimit);
        int advancedCount = 0;
        for (WorkTask waitingTask : waitingTasks) {
            WorkTask refreshed = recomputeDispatchStatus(waitingTask);
            if (waitingTask.status() != TaskStatus.READY_FOR_ASSIGN
                && refreshed.status() == TaskStatus.READY_FOR_ASSIGN) {
                advancedCount++;
            }
        }
        return advancedCount;
    }

    @Override
    @Transactional
    public Optional<WorkTask> claimReadyTaskForWorker(String workerId, String runId) {
        String normalizedWorkerId = requireNotBlank(workerId, "workerId");
        String normalizedRunId = requireNotBlank(runId, "runId");
        for (int offset = 0; offset < claimScanMaxRows; offset += claimScanBatchSize) {
            List<WorkTask> candidates = workTaskRepository.findByStatus(
                TaskStatus.READY_FOR_ASSIGN,
                claimScanBatchSize,
                offset
            );
            if (candidates.isEmpty()) {
                break;
            }
            for (WorkTask candidate : candidates) {
                String sessionId = findSessionIdByModuleId(candidate.moduleId()).orElse("");
                if (sessionId.isBlank()) {
                    continue;
                }
                if (!sessionDispatchPolicyPort.isSessionDispatchable(sessionId)) {
                    continue;
                }
                if (isInitGateActive(sessionId) && !isInitTemplate(candidate.taskTemplateId())) {
                    continue;
                }
                if (!workerEligibilityPort.isWorkerEligible(normalizedWorkerId, candidate.requiredToolpacksJson())) {
                    continue;
                }
                boolean claimed = workTaskRepository.claimIfReady(
                    candidate.taskId(),
                    normalizedRunId,
                    Instant.now()
                );
                if (claimed) {
                    return workTaskRepository.findById(candidate.taskId());
                }
            }
        }
        return Optional.empty();
    }

    private static boolean isInitTemplate(TaskTemplateId taskTemplateId) {
        if (taskTemplateId == null) {
            return false;
        }
        return TaskTemplateId.TMPL_INIT_V0 == taskTemplateId;
    }

    @Override
    @Transactional(readOnly = true)
    public boolean isInitGateActive(String sessionId) {
        String normalizedSessionId = requireNotBlank(sessionId, "sessionId");
        return workTaskRepository.countNonDoneBySessionIdAndTemplateId(
            normalizedSessionId,
            TaskTemplateId.TMPL_INIT_V0.value()
        ) > 0;
    }

    @Override
    @Transactional(readOnly = true)
    public List<WorkTask> listWaitingWorkerTasks(int limit) {
        int cappedLimit = limit <= 0 ? 100 : Math.min(limit, 500);
        return workTaskRepository.findByStatus(TaskStatus.WAITING_WORKER, cappedLimit);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<String> findSessionIdByModuleId(String moduleId) {
        String normalizedModuleId = requireNotBlank(moduleId, "moduleId");
        return workModuleRepository.findById(normalizedModuleId).map(WorkModule::sessionId);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<String> findSessionIdByTaskId(String taskId) {
        String normalizedTaskId = requireNotBlank(taskId, "taskId");
        return workTaskRepository.findById(normalizedTaskId)
            .flatMap(task -> workModuleRepository.findById(task.moduleId()).map(WorkModule::sessionId));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<WorkTask> findTaskById(String taskId) {
        String normalizedTaskId = requireNotBlank(taskId, "taskId");
        return workTaskRepository.findById(normalizedTaskId);
    }

    @Override
    @Transactional(readOnly = true)
    public List<WorkTask> listTasksByStatus(TaskStatus status, int limit) {
        if (status == null) {
            throw new IllegalArgumentException("status must not be null");
        }
        int cappedLimit = limit <= 0 ? 100 : Math.min(limit, 500);
        return workTaskRepository.findByStatus(status, cappedLimit);
    }

    @Override
    @Transactional(readOnly = true)
    public boolean hasNonDoneTasksBySession(String sessionId) {
        String normalizedSessionId = requireNotBlank(sessionId, "sessionId");
        return workTaskRepository.countNonDoneBySessionId(normalizedSessionId) > 0;
    }

    @Override
    @Transactional(readOnly = true)
    public boolean hasNonDoneDependentTaskByTemplate(String taskId, String taskTemplateId) {
        String normalizedTaskId = requireNotBlank(taskId, "taskId");
        TaskTemplateId expectedTemplate = TaskTemplateId.fromValue(taskTemplateId);
        List<WorkTaskDependency> dependents = workTaskDependencyRepository.findByDependsOnTaskId(normalizedTaskId);
        for (WorkTaskDependency dependent : dependents) {
            if (dependent == null || dependent.taskId() == null || dependent.taskId().isBlank()) {
                continue;
            }
            Optional<WorkTask> dependentTask = workTaskRepository.findById(dependent.taskId());
            if (dependentTask.isEmpty()) {
                continue;
            }
            WorkTask task = dependentTask.get();
            if (task.taskTemplateId() == expectedTemplate && task.status() != TaskStatus.DONE) {
                return true;
            }
        }
        return false;
    }

    private WorkTask loadTaskOrThrow(String taskId) {
        String normalizedTaskId = requireNotBlank(taskId, "taskId");
        return workTaskRepository.findById(normalizedTaskId)
            .orElseThrow(() -> new NoSuchElementException("Work task not found: " + normalizedTaskId));
    }

    private WorkTaskDependency addTaskDependencyInternal(
        WorkTask task,
        String dependsOnTaskId,
        TaskStatus requiredUpstreamStatus,
        Instant createdAt,
        boolean recomputeTask
    ) {
        String taskId = task.taskId();
        if (taskId.equals(dependsOnTaskId)) {
            throw new IllegalArgumentException("Task dependency must not be self-reference: " + taskId);
        }
        WorkTask dependsOnTask = loadTaskOrThrow(dependsOnTaskId);
        assertSameSession(task, dependsOnTask);
        assertDependencyStatusSupported(requiredUpstreamStatus);
        assertNoCycle(taskId, dependsOnTaskId);

        if (workTaskDependencyRepository.exists(taskId, dependsOnTaskId)) {
            return workTaskDependencyRepository.findByTaskId(taskId)
                .stream()
                .filter(it -> dependsOnTaskId.equals(it.dependsOnTaskId()))
                .findFirst()
                .orElse(new WorkTaskDependency(taskId, dependsOnTaskId, requiredUpstreamStatus, createdAt));
        }

        WorkTaskDependency dependency = new WorkTaskDependency(
            taskId,
            dependsOnTaskId,
            requiredUpstreamStatus,
            createdAt
        );
        WorkTaskDependency saved = workTaskDependencyRepository.save(dependency);
        if (recomputeTask) {
            recomputeDispatchStatus(task);
        }
        return saved;
    }

    private WorkTask recomputeDispatchStatus(WorkTask task) {
        if (task.status() == TaskStatus.ASSIGNED || task.status() == TaskStatus.DELIVERED || task.status() == TaskStatus.DONE) {
            return task;
        }
        TaskStatus nextStatus = resolveDispatchStatus(task.taskId(), task.requiredToolpacksJson());
        if (task.status() == nextStatus && task.activeRunId() == null) {
            return task;
        }
        WorkTask updated = new WorkTask(
            task.taskId(),
            task.moduleId(),
            task.title(),
            task.taskTemplateId(),
            nextStatus,
            task.requiredToolpacksJson(),
            null,
            task.createdByRole(),
            task.createdAt(),
            Instant.now()
        );
        return workTaskRepository.update(updated);
    }

    private TaskStatus resolveDispatchStatus(String taskId, String requiredToolpacksJson) {
        if (hasUnmetDependency(taskId)) {
            return TaskStatus.WAITING_DEPENDENCY;
        }
        boolean hasEligibleWorker = workerEligibilityPort.hasEligibleWorker(requiredToolpacksJson);
        return hasEligibleWorker ? TaskStatus.READY_FOR_ASSIGN : TaskStatus.WAITING_WORKER;
    }

    private boolean hasUnmetDependency(String taskId) {
        List<WorkTaskDependency> dependencies = workTaskDependencyRepository.findByTaskId(taskId);
        for (WorkTaskDependency dependency : dependencies) {
            Optional<WorkTask> upstream = workTaskRepository.findById(dependency.dependsOnTaskId());
            if (upstream.isEmpty()) {
                return true;
            }
            if (upstream.get().status() != dependency.requiredUpstreamStatus()) {
                return true;
            }
        }
        return false;
    }

    private static boolean canTransitionToDone(WorkTask task) {
        if (task == null) {
            return false;
        }
        if (task.status() == TaskStatus.DELIVERED) {
            return true;
        }
        return task.status() == TaskStatus.ASSIGNED && task.taskTemplateId() == TaskTemplateId.TMPL_VERIFY_V0;
    }

    private void assertSameSession(WorkTask task, WorkTask dependsOnTask) {
        String sessionId = findSessionIdByTask(task);
        String dependsOnSessionId = findSessionIdByTask(dependsOnTask);
        if (!sessionId.equals(dependsOnSessionId)) {
            throw new IllegalArgumentException(
                "Task dependency must be in same session, task=" + task.taskId()
                    + ", dependsOn=" + dependsOnTask.taskId()
            );
        }
    }

    private String findSessionIdByTask(WorkTask task) {
        return workModuleRepository.findById(task.moduleId())
            .map(WorkModule::sessionId)
            .orElseThrow(() -> new NoSuchElementException("Work module not found: " + task.moduleId()));
    }

    private void assertNoCycle(String taskId, String dependsOnTaskId) {
        if (wouldCreateCycle(taskId, dependsOnTaskId, new HashSet<>())) {
            throw new IllegalArgumentException(
                "Task dependency cycle detected, task=" + taskId + ", dependsOn=" + dependsOnTaskId
            );
        }
    }

    private boolean wouldCreateCycle(String targetTaskId, String currentTaskId, Set<String> visited) {
        if (targetTaskId.equals(currentTaskId)) {
            return true;
        }
        if (!visited.add(currentTaskId)) {
            return false;
        }
        List<WorkTaskDependency> upstreamDependencies = workTaskDependencyRepository.findByTaskId(currentTaskId);
        for (WorkTaskDependency upstream : upstreamDependencies) {
            if (wouldCreateCycle(targetTaskId, upstream.dependsOnTaskId(), visited)) {
                return true;
            }
        }
        return false;
    }

    private static TaskStatus parseRequiredUpstreamStatus(String rawStatus) {
        String normalized = rawStatus == null || rawStatus.isBlank()
            ? DEFAULT_DEPENDENCY_REQUIRED_STATUS.name()
            : rawStatus.trim().toUpperCase();
        TaskStatus parsed = TaskStatus.valueOf(normalized);
        assertDependencyStatusSupported(parsed);
        return parsed;
    }

    private static void assertDependencyStatusSupported(TaskStatus requiredUpstreamStatus) {
        if (requiredUpstreamStatus != TaskStatus.DONE) {
            throw new IllegalArgumentException(
                "requiredUpstreamStatus currently supports only DONE, got: " + requiredUpstreamStatus
            );
        }
    }

    private String normalizeRequiredToolpacksJson(String rawJson) {
        String normalizedInput = requireNotBlank(rawJson, "requiredToolpacksJson");
        JsonNode jsonNode;
        try {
            jsonNode = objectMapper.readTree(normalizedInput);
        } catch (Exception ex) {
            throw new IllegalArgumentException("requiredToolpacksJson must be valid JSON array text", ex);
        }
        if (jsonNode == null || !jsonNode.isArray()) {
            throw new IllegalArgumentException("requiredToolpacksJson must be JSON array text");
        }
        LinkedHashSet<String> normalizedToolpackIds = new LinkedHashSet<>();
        for (JsonNode element : jsonNode) {
            if (!element.isTextual()) {
                throw new IllegalArgumentException("requiredToolpacksJson element must be string");
            }
            String toolpackId = element.asText().trim();
            if (toolpackId.isEmpty()) {
                throw new IllegalArgumentException("requiredToolpacksJson element must not be blank");
            }
            normalizedToolpackIds.add(toolpackId);
        }
        ArrayNode canonicalJson = objectMapper.createArrayNode();
        for (String toolpackId : normalizedToolpackIds) {
            canonicalJson.add(toolpackId);
        }
        try {
            return objectMapper.writeValueAsString(canonicalJson);
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to normalize requiredToolpacksJson", ex);
        }
    }

    private static String requireNotBlank(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value.trim();
    }

    private static String normalizeNullable(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        return normalized.isEmpty() ? null : normalized;
    }

    private static List<String> normalizeDependsOnTaskIds(List<String> dependsOnTaskIds, String taskId) {
        if (dependsOnTaskIds == null || dependsOnTaskIds.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String dependsOnTaskId : dependsOnTaskIds) {
            if (dependsOnTaskId == null || dependsOnTaskId.isBlank()) {
                continue;
            }
            String trimmed = dependsOnTaskId.trim();
            if (trimmed.equals(taskId)) {
                throw new IllegalArgumentException("Task dependency must not be self-reference: " + taskId);
            }
            normalized.add(trimmed);
        }
        return List.copyOf(normalized);
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(value, max));
    }

    private static String generateModuleId() {
        return "MOD-" + UUID.randomUUID().toString().replace("-", "");
    }

    private static String generateTaskId() {
        return "TASK-" + UUID.randomUUID().toString().replace("-", "");
    }
}
