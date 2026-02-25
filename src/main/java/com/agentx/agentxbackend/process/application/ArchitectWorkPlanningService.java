package com.agentx.agentxbackend.process.application;

import com.agentx.agentxbackend.contextpack.application.port.in.ContextCompileUseCase;
import com.agentx.agentxbackend.contextpack.domain.model.TaskContextPack;
import com.agentx.agentxbackend.planning.application.port.in.PlanningCommandUseCase;
import com.agentx.agentxbackend.planning.domain.model.WorkModule;
import com.agentx.agentxbackend.planning.domain.model.WorkTask;
import com.agentx.agentxbackend.process.application.port.out.ArchitectTicketEventContext;
import com.agentx.agentxbackend.process.application.port.out.ArchitectTaskBreakdownGeneratorPort;
import com.agentx.agentxbackend.ticket.domain.model.Ticket;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Locale;

@Service
public class ArchitectWorkPlanningService {

    private final PlanningCommandUseCase planningCommandUseCase;
    private final ContextCompileUseCase contextCompileUseCase;
    private final ArchitectTaskBreakdownGeneratorPort breakdownGeneratorPort;
    private final ObjectMapper objectMapper;

    public ArchitectWorkPlanningService(
        PlanningCommandUseCase planningCommandUseCase,
        ContextCompileUseCase contextCompileUseCase,
        ArchitectTaskBreakdownGeneratorPort breakdownGeneratorPort,
        ObjectMapper objectMapper
    ) {
        this.planningCommandUseCase = planningCommandUseCase;
        this.contextCompileUseCase = contextCompileUseCase;
        this.breakdownGeneratorPort = breakdownGeneratorPort;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public PlanResult planAndPersist(
        Ticket ticket,
        String requirementDocContent,
        List<ArchitectTicketEventContext> recentEvents
    ) {
        if (ticket == null) {
            throw new IllegalArgumentException("ticket must not be null");
        }
        ArchitectTaskBreakdownGeneratorPort.GenerateInput input = new ArchitectTaskBreakdownGeneratorPort.GenerateInput(
            ticket.ticketId(),
            ticket.sessionId(),
            ticket.type() == null ? "" : ticket.type().name(),
            ticket.title(),
            ticket.requirementDocId(),
            ticket.requirementDocVer(),
            ticket.payloadJson(),
            nullSafe(requirementDocContent),
            recentEvents == null ? List.of() : recentEvents
        );
        ArchitectTaskBreakdownGeneratorPort.BreakdownPlan plan = breakdownGeneratorPort.generate(input);
        List<ArchitectTaskBreakdownGeneratorPort.ModulePlan> modules = normalizeModules(plan.modules(), ticket);

        List<CreatedModule> createdModules = new ArrayList<>();
        for (ArchitectTaskBreakdownGeneratorPort.ModulePlan modulePlan : modules) {
            WorkModule createdModule = planningCommandUseCase.createModule(
                ticket.sessionId(),
                modulePlan.name(),
                modulePlan.description()
            );
            List<NormalizedTaskPlan> normalizedTaskPlans = normalizeTaskPlans(modulePlan.tasks());

            Map<String, WorkTask> taskByKey = new LinkedHashMap<>();
            Map<String, CreatedTaskDraft> taskDraftByKey = new LinkedHashMap<>();
            List<CreatedTask> createdTasks = new ArrayList<>();
            for (NormalizedTaskPlan taskPlan : normalizedTaskPlans) {
                WorkTask createdTask = planningCommandUseCase.createTask(
                    createdModule.moduleId(),
                    taskPlan.title(),
                    normalizeTemplate(taskPlan.taskTemplateId()),
                    toRequiredToolpacksJson(taskPlan.requiredToolpackIds()),
                    List.of()
                );
                TaskContextPack compiledPack = contextCompileUseCase.compileTaskContextPack(
                    createdTask.taskId(),
                    resolveRunKind(createdTask.taskTemplateId().value()),
                    "TICKET_DONE"
                );
                taskByKey.put(taskPlan.taskKey(), createdTask);
                taskDraftByKey.put(
                    taskPlan.taskKey(),
                    new CreatedTaskDraft(
                        taskPlan.taskKey(),
                        createdTask,
                        nullSafe(taskPlan.rationale()),
                        compiledPack.snapshotId()
                    )
                );
            }

            for (NormalizedTaskPlan taskPlan : normalizedTaskPlans) {
                WorkTask downstreamTask = taskByKey.get(taskPlan.taskKey());
                CreatedTaskDraft taskDraft = taskDraftByKey.get(taskPlan.taskKey());
                if (downstreamTask == null || taskDraft == null) {
                    throw new IllegalStateException("Planned task key missing after createTask: " + taskPlan.taskKey());
                }
                List<String> dependsOnTaskIds = new ArrayList<>();
                List<String> unresolvedDependsOnKeys = new ArrayList<>();
                for (String dependsOnKey : taskPlan.dependsOnKeys()) {
                    WorkTask upstreamTask = taskByKey.get(dependsOnKey);
                    if (upstreamTask == null) {
                        unresolvedDependsOnKeys.add(dependsOnKey);
                        continue;
                    }
                    planningCommandUseCase.addTaskDependency(
                        downstreamTask.taskId(),
                        upstreamTask.taskId(),
                        "DONE"
                    );
                    dependsOnTaskIds.add(upstreamTask.taskId());
                }
                createdTasks.add(taskDraft.toCreatedTask(dependsOnTaskIds, unresolvedDependsOnKeys));
            }
            createdModules.add(
                new CreatedModule(
                    createdModule.moduleId(),
                    createdModule.name(),
                    nullSafe(createdModule.description()),
                    createdTasks
                )
            );
        }

        return new PlanResult(
            nullSafe(plan.summary()),
            nullSafe(plan.provider()),
            nullSafe(plan.model()),
            createdModules
        );
    }

    private List<ArchitectTaskBreakdownGeneratorPort.ModulePlan> normalizeModules(
        List<ArchitectTaskBreakdownGeneratorPort.ModulePlan> modules,
        Ticket ticket
    ) {
        if (modules == null || modules.isEmpty()) {
            return defaultModules(ticket);
        }
        List<ArchitectTaskBreakdownGeneratorPort.ModulePlan> normalized = new ArrayList<>();
        for (ArchitectTaskBreakdownGeneratorPort.ModulePlan module : modules) {
            if (module == null || module.name() == null || module.name().isBlank()) {
                continue;
            }
            List<ArchitectTaskBreakdownGeneratorPort.TaskPlan> tasks = new ArrayList<>();
            if (module.tasks() != null) {
                for (ArchitectTaskBreakdownGeneratorPort.TaskPlan task : module.tasks()) {
                    if (task == null || task.title() == null || task.title().isBlank()) {
                        continue;
                    }
                    tasks.add(task);
                }
            }
            if (!tasks.isEmpty()) {
                normalized.add(
                    new ArchitectTaskBreakdownGeneratorPort.ModulePlan(
                        module.name().trim(),
                        module.description(),
                        tasks
                    )
                );
            }
        }
        if (normalized.isEmpty()) {
            return defaultModules(ticket);
        }
        return normalized;
    }

    private static List<NormalizedTaskPlan> normalizeTaskPlans(List<ArchitectTaskBreakdownGeneratorPort.TaskPlan> taskPlans) {
        if (taskPlans == null || taskPlans.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<String> reservedKeys = new LinkedHashSet<>();
        List<NormalizedTaskPlan> normalized = new ArrayList<>();
        int idx = 0;
        for (ArchitectTaskBreakdownGeneratorPort.TaskPlan taskPlan : taskPlans) {
            idx++;
            if (taskPlan == null || taskPlan.title() == null || taskPlan.title().isBlank()) {
                continue;
            }
            String key = normalizeTaskKey(taskPlan.taskKey(), taskPlan.title(), idx);
            key = dedupTaskKey(key, reservedKeys);
            reservedKeys.add(key);
            normalized.add(
                new NormalizedTaskPlan(
                    key,
                    taskPlan.title().trim(),
                    taskPlan.taskTemplateId(),
                    taskPlan.requiredToolpackIds() == null ? List.of() : List.copyOf(taskPlan.requiredToolpackIds()),
                    normalizeDependsOnKeys(taskPlan.dependsOnKeys(), key),
                    taskPlan.rationale()
                )
            );
        }
        return normalized;
    }

    private static String resolveRunKind(String taskTemplateId) {
        if ("tmpl.verify.v0".equalsIgnoreCase(nullSafe(taskTemplateId))) {
            return "VERIFY";
        }
        return "IMPL";
    }

    private static List<ArchitectTaskBreakdownGeneratorPort.ModulePlan> defaultModules(Ticket ticket) {
        String suffix = shortId(ticket == null ? null : ticket.ticketId());
        List<ArchitectTaskBreakdownGeneratorPort.TaskPlan> tasks = List.of(
            new ArchitectTaskBreakdownGeneratorPort.TaskPlan(
                "core_impl",
                "Implement baseline flow for " + nullSafe(ticket == null ? null : ticket.title()),
                "tmpl.impl.v0",
                List.of("TP-JAVA-21", "TP-MAVEN-3", "TP-GIT-2"),
                List.of(),
                "Baseline implementation task generated by architect fallback."
            ),
            new ArchitectTaskBreakdownGeneratorPort.TaskPlan(
                "core_test",
                "Add regression tests for " + nullSafe(ticket == null ? null : ticket.title()),
                "tmpl.test.v0",
                List.of("TP-JAVA-21", "TP-MAVEN-3", "TP-GIT-2"),
                List.of("core_impl"),
                "Testing task generated by architect fallback."
            )
        );
        return List.of(
            new ArchitectTaskBreakdownGeneratorPort.ModulePlan(
                "module-auto-" + suffix,
                "Fallback module generated for architect ticket auto planning.",
                tasks
            )
        );
    }

    private String toRequiredToolpacksJson(List<String> requiredToolpackIds) {
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        if (requiredToolpackIds != null) {
            for (String id : requiredToolpackIds) {
                if (id == null) {
                    continue;
                }
                String trimmed = id.trim();
                if (!trimmed.isEmpty()) {
                    ids.add(trimmed);
                }
            }
        }
        if (ids.isEmpty()) {
            ids.add("TP-JAVA-21");
            ids.add("TP-MAVEN-3");
            ids.add("TP-GIT-2");
        }
        try {
            ArrayNode arrayNode = objectMapper.createArrayNode();
            for (String id : ids) {
                arrayNode.add(id);
            }
            return objectMapper.writeValueAsString(arrayNode);
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to serialize required toolpacks", ex);
        }
    }

    private static String normalizeTemplate(String raw) {
        if (raw == null || raw.isBlank()) {
            return "tmpl.impl.v0";
        }
        String normalized = raw.trim().toLowerCase();
        if ("tmpl.init.v0".equals(normalized)
            || "tmpl.impl.v0".equals(normalized)
            || "tmpl.verify.v0".equals(normalized)
            || "tmpl.bugfix.v0".equals(normalized)
            || "tmpl.refactor.v0".equals(normalized)
            || "tmpl.test.v0".equals(normalized)) {
            return normalized;
        }
        return "tmpl.impl.v0";
    }

    private static String shortId(String value) {
        String safe = nullSafe(value);
        if (safe.length() <= 8) {
            return safe.isBlank() ? "default" : safe.toLowerCase();
        }
        return safe.substring(safe.length() - 8).toLowerCase();
    }

    private static String nullSafe(String value) {
        return value == null ? "" : value;
    }

    private static String normalizeTaskKey(String rawKey, String title, int fallbackIndex) {
        String candidate = rawKey == null ? "" : rawKey.trim();
        if (candidate.isBlank()) {
            candidate = title == null ? "" : title.trim();
        }
        candidate = candidate.toLowerCase(Locale.ROOT)
            .replaceAll("[^a-z0-9_\\-]+", "_")
            .replaceAll("_+", "_")
            .replaceAll("^-|-$", "")
            .replaceAll("^_+|_+$", "");
        if (candidate.isBlank()) {
            return "task_" + fallbackIndex;
        }
        return candidate;
    }

    private static String dedupTaskKey(String key, LinkedHashSet<String> reservedKeys) {
        if (!reservedKeys.contains(key)) {
            return key;
        }
        int seq = 2;
        while (reservedKeys.contains(key + "_" + seq)) {
            seq++;
        }
        return key + "_" + seq;
    }

    private static List<String> normalizeDependsOnKeys(List<String> rawKeys, String selfKey) {
        if (rawKeys == null || rawKeys.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String raw : rawKeys) {
            if (raw == null || raw.isBlank()) {
                continue;
            }
            String key = normalizeTaskKey(raw, raw, 0);
            if (key.equals(selfKey)) {
                throw new IllegalArgumentException("Task depends_on_keys must not reference itself: " + selfKey);
            }
            if (key.isBlank()) {
                continue;
            }
            normalized.add(key);
        }
        return List.copyOf(normalized);
    }

    public record PlanResult(
        String summary,
        String provider,
        String model,
        List<CreatedModule> createdModules
    ) {
    }

    public record CreatedModule(
        String moduleId,
        String name,
        String description,
        List<CreatedTask> createdTasks
    ) {
    }

    public record CreatedTask(
        String taskKey,
        String taskId,
        String title,
        String taskTemplateId,
        String status,
        String requiredToolpacksJson,
        List<String> dependsOnTaskIds,
        List<String> unresolvedDependsOnKeys,
        String rationale,
        String contextSnapshotId
    ) {
    }

    private record NormalizedTaskPlan(
        String taskKey,
        String title,
        String taskTemplateId,
        List<String> requiredToolpackIds,
        List<String> dependsOnKeys,
        String rationale
    ) {
    }

    private record CreatedTaskDraft(
        String taskKey,
        WorkTask task,
        String rationale,
        String contextSnapshotId
    ) {
        CreatedTask toCreatedTask(List<String> dependsOnTaskIds, List<String> unresolvedDependsOnKeys) {
            return new CreatedTask(
                taskKey,
                task.taskId(),
                task.title(),
                task.taskTemplateId().value(),
                task.status().name(),
                task.requiredToolpacksJson(),
                dependsOnTaskIds == null ? List.of() : List.copyOf(dependsOnTaskIds),
                unresolvedDependsOnKeys == null ? List.of() : List.copyOf(unresolvedDependsOnKeys),
                rationale,
                contextSnapshotId
            );
        }
    }
}
