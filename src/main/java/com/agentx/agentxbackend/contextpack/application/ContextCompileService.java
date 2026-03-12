package com.agentx.agentxbackend.contextpack.application;

import com.agentx.agentxbackend.contextpack.application.port.in.ContextCompileUseCase;
import com.agentx.agentxbackend.contextpack.application.port.out.ArtifactStorePort;
import com.agentx.agentxbackend.contextpack.application.port.out.ContextFactsQueryPort;
import com.agentx.agentxbackend.contextpack.application.port.out.RepoContextQueryPort;
import com.agentx.agentxbackend.contextpack.application.port.out.TaskContextSnapshotRepository;
import com.agentx.agentxbackend.contextpack.domain.model.RoleContextPack;
import com.agentx.agentxbackend.contextpack.domain.model.TaskContextPack;
import com.agentx.agentxbackend.contextpack.domain.model.TaskContextSnapshot;
import com.agentx.agentxbackend.contextpack.domain.model.TaskContextSnapshotStatus;
import com.agentx.agentxbackend.contextpack.domain.model.TaskContextSnapshotStatusView;
import com.agentx.agentxbackend.contextpack.domain.model.TaskSkill;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class ContextCompileService implements ContextCompileUseCase {

    private static final int MAX_ARCHITECTURE_REFS = 12;
    private static final int MAX_DECISION_REFS = 12;
    private static final int MAX_DECISION_SUMMARIES = 6;
    private static final int MAX_REQUIREMENT_HIGHLIGHTS = 12;
    private static final Pattern ROOT_CAUSE_PATTERN = Pattern.compile(
        "(?i)caused by:\\s*([\\w.$]+(?:Exception|Error):\\s*.+?)(?:\\s+at\\s+[\\w.$]+\\(|$)"
    );
    private static final Pattern EXCEPTION_PATTERN = Pattern.compile(
        "([\\w.$]+(?:Exception|Error):\\s*.+?)(?:\\s+at\\s+[\\w.$]+\\(|$)"
    );

    private final ContextFactsQueryPort contextFactsQueryPort;
    private final ArtifactStorePort artifactStorePort;
    private final TaskContextSnapshotRepository snapshotRepository;
    private final RepoContextQueryPort repoContextQueryPort;
    private final ObjectMapper objectMapper;
    private final int retentionDays;
    private final int recentTicketLimit;
    private final int recentRunLimit;

    public ContextCompileService(
        ContextFactsQueryPort contextFactsQueryPort,
        ArtifactStorePort artifactStorePort,
        TaskContextSnapshotRepository snapshotRepository,
        RepoContextQueryPort repoContextQueryPort,
        ObjectMapper objectMapper,
        @Value("${agentx.contextpack.snapshot-retention-days:180}") int retentionDays,
        @Value("${agentx.contextpack.facts.recent-ticket-limit:20}") int recentTicketLimit,
        @Value("${agentx.contextpack.facts.recent-run-limit:8}") int recentRunLimit
    ) {
        this.contextFactsQueryPort = contextFactsQueryPort;
        this.artifactStorePort = artifactStorePort;
        this.snapshotRepository = snapshotRepository;
        this.repoContextQueryPort = repoContextQueryPort;
        this.objectMapper = objectMapper;
        this.retentionDays = Math.max(30, retentionDays);
        this.recentTicketLimit = Math.max(1, Math.min(100, recentTicketLimit));
        this.recentRunLimit = Math.max(1, Math.min(100, recentRunLimit));
    }

    @Override
    public RoleContextPack compileRolePack(String sessionId, String role) {
        String normalizedSessionId = requireNotBlank(sessionId, "sessionId");
        String normalizedRole = requireNotBlank(role, "role").toLowerCase(Locale.ROOT);

        Optional<ContextFactsQueryPort.RequirementBaselineFact> requirementOptional = contextFactsQueryPort
            .findRequirementBaselineBySessionId(normalizedSessionId);
        List<ContextFactsQueryPort.TicketFact> tickets = contextFactsQueryPort.listRecentArchitectureTickets(
            normalizedSessionId,
            recentTicketLimit
        );

        List<String> sourceRefs = new ArrayList<>();
        requirementOptional.ifPresent(req -> {
            if (req.docId() != null && req.baselineVersion() != null) {
                sourceRefs.add("req:" + req.docId() + "@v" + req.baselineVersion());
            }
        });
        for (ContextFactsQueryPort.TicketFact ticket : tickets) {
            sourceRefs.add("ticket:" + ticket.ticketId());
        }

        RepoContextQueryPort.RepoContext repoContext = safeQueryRepoContext(
            buildRepoQueryForRolePack(requirementOptional, tickets),
            List.of("./"),
            18,
            3,
            900,
            3_000
        );

        String goal = requirementOptional
            .map(req -> {
                if (req.title() != null && !req.title().isBlank()) {
                    return req.title();
                }
                return "Goal extracted from confirmed requirement baseline.";
            })
            .orElse("No confirmed requirement baseline yet.");

        List<String> hardConstraints = new ArrayList<>();
        requirementOptional.ifPresent(req -> hardConstraints.addAll(extractSectionList(req.content(), "## 6.")));
        if (hardConstraints.isEmpty()) {
            hardConstraints.add("No explicit value constraints extracted; require architect clarification if needed.");
        }

        List<String> currentState = new ArrayList<>();
        if (tickets.isEmpty()) {
            currentState.add("No architect-facing tickets found for this session.");
        } else {
            for (ContextFactsQueryPort.TicketFact ticket : tickets) {
                currentState.add("Ticket " + ticket.ticketId() + " is " + ticket.status() + " (" + ticket.type() + ").");
            }
        }
        appendRepoContextState(currentState, repoContext);

        List<String> openQuestions = new ArrayList<>();
        for (ContextFactsQueryPort.TicketFact ticket : tickets) {
            if ("WAITING_USER".equalsIgnoreCase(nullSafe(ticket.status()))) {
                openQuestions.add("Awaiting user response on ticket " + ticket.ticketId() + ".");
            }
            List<ContextFactsQueryPort.TicketEventFact> events = contextFactsQueryPort.listRecentTicketEvents(
                ticket.ticketId(),
                8
            );
            if (events == null || events.isEmpty()) {
                continue;
            }
            for (ContextFactsQueryPort.TicketEventFact event : events) {
                if ("DECISION_REQUESTED".equalsIgnoreCase(nullSafe(event.eventType()))) {
                    openQuestions.add(nullSafe(event.body()));
                }
            }
        }
        if (openQuestions.isEmpty()) {
            openQuestions.add("No unresolved decision request detected in recent architect events.");
        }

        List<String> nextActions = new ArrayList<>();
        if (tickets.stream().anyMatch(t -> "WAITING_USER".equalsIgnoreCase(nullSafe(t.status())))) {
            nextActions.add("Wait for user responses on WAITING_USER architect tickets.");
        } else {
            nextActions.add("Consume OPEN architect tickets and produce decision/clarification requests when needed.");
        }
        nextActions.add("After decision responses, split work into modules/tasks and compile task context snapshots.");

        RoleContextPack pack = new RoleContextPack(
            generateRolePackId(),
            normalizedSessionId,
            normalizedRole,
            Instant.now(),
            sourceRefs,
            new RoleContextPack.Summary(
                goal,
                hardConstraints,
                currentState,
                openQuestions
            ),
            nextActions
        );
        storeRolePackArtifact(pack);
        return pack;
    }

    @Override
    public TaskContextPack compileTaskContextPack(String taskId, String runKind) {
        return compileTaskContextPack(taskId, runKind, "MANUAL_REFRESH");
    }

    @Override
    public TaskContextPack compileTaskContextPack(String taskId, String runKind, String triggerType) {
        return compileTaskArtifacts(taskId, runKind, triggerType).taskContextPack();
    }

    @Override
    public TaskSkill compileTaskSkill(String taskId) {
        String normalizedTaskId = requireNotBlank(taskId, "taskId");
        ContextFactsQueryPort.TaskPlanningFact taskPlanning = contextFactsQueryPort.findTaskPlanningByTaskId(normalizedTaskId)
            .orElseThrow(() -> new NoSuchElementException("Task not found: " + normalizedTaskId));
        return compileTaskArtifacts(
            normalizedTaskId,
            resolveRunKindByTemplate(taskPlanning.taskTemplateId()),
            "MANUAL_REFRESH"
        ).taskSkill();
    }

    @Override
    public TaskContextSnapshotStatusView getTaskContextStatus(String taskId, int limit) {
        String normalizedTaskId = requireNotBlank(taskId, "taskId");
        int cappedLimit = limit <= 0 ? 10 : Math.min(limit, 100);
        List<TaskContextSnapshot> snapshots = snapshotRepository.findLatestByTaskId(normalizedTaskId, cappedLimit);
        TaskContextSnapshot latest = snapshots.isEmpty() ? null : snapshots.get(0);
        return new TaskContextSnapshotStatusView(normalizedTaskId, latest, snapshots);
    }

    @Override
    public int refreshTaskContextsBySession(String sessionId, String triggerType, int limit) {
        String normalizedSessionId = requireNotBlank(sessionId, "sessionId");
        String normalizedTriggerType = requireNotBlank(triggerType, "triggerType");
        int cappedLimit = limit <= 0 ? 200 : Math.min(limit, 1000);
        List<ContextFactsQueryPort.TaskPlanningFact> tasks = contextFactsQueryPort.listTaskPlanningBySessionId(
            normalizedSessionId,
            cappedLimit
        );
        int refreshed = 0;
        for (ContextFactsQueryPort.TaskPlanningFact task : tasks) {
            if (task == null || task.taskId() == null || task.taskId().isBlank()) {
                continue;
            }
            if (refreshTaskContextByTask(task.taskId(), normalizedTriggerType)) {
                refreshed++;
            }
        }
        return refreshed;
    }

    @Override
    public int refreshTaskContextsByTicket(String ticketId, String triggerType, int limit) {
        String normalizedTicketId = requireNotBlank(ticketId, "ticketId");
        String normalizedTriggerType = requireNotBlank(triggerType, "triggerType");
        Optional<ContextFactsQueryPort.TicketSessionFact> ticketOptional = contextFactsQueryPort
            .findTicketSessionByTicketId(normalizedTicketId);
        if (ticketOptional.isEmpty()) {
            return 0;
        }
        ContextFactsQueryPort.TicketSessionFact ticket = ticketOptional.get();
        if (ticket.sessionId() == null || ticket.sessionId().isBlank()) {
            return 0;
        }
        return refreshTaskContextsBySession(ticket.sessionId(), normalizedTriggerType, limit);
    }

    @Override
    public boolean refreshTaskContextByTask(String taskId, String triggerType) {
        String normalizedTaskId = requireNotBlank(taskId, "taskId");
        String normalizedTriggerType = requireNotBlank(triggerType, "triggerType");
        ContextFactsQueryPort.TaskPlanningFact taskPlanning = contextFactsQueryPort.findTaskPlanningByTaskId(normalizedTaskId)
            .orElseThrow(() -> new NoSuchElementException("Task not found: " + normalizedTaskId));
        String runKind = resolveRunKindByTemplate(taskPlanning.taskTemplateId());
        String beforeSnapshotId = snapshotRepository.findLatestByTaskAndRunKind(normalizedTaskId, runKind)
            .map(TaskContextSnapshot::snapshotId)
            .orElse(null);
        TaskContextPack refreshed = compileTaskArtifacts(
            normalizedTaskId,
            runKind,
            normalizedTriggerType
        ).taskContextPack();
        return beforeSnapshotId == null || !beforeSnapshotId.equals(refreshed.snapshotId());
    }

    private CompileArtifactsResult compileTaskArtifacts(
        String taskId,
        String runKind,
        String triggerType
    ) {
        String normalizedTaskId = requireNotBlank(taskId, "taskId");
        String normalizedRunKind = normalizeRunKind(runKind);
        String normalizedTriggerType = requireNotBlank(triggerType, "triggerType");

        ContextFactsQueryPort.TaskPlanningFact taskPlanning = contextFactsQueryPort.findTaskPlanningByTaskId(normalizedTaskId)
            .orElseThrow(() -> new NoSuchElementException("Task not found: " + normalizedTaskId));
        Optional<ContextFactsQueryPort.RequirementBaselineFact> requirementOptional = contextFactsQueryPort
            .findRequirementBaselineBySessionId(taskPlanning.sessionId());
        List<ContextFactsQueryPort.TicketFact> tickets = contextFactsQueryPort.listRecentArchitectureTickets(
            taskPlanning.sessionId(),
            recentTicketLimit
        );
        List<ContextFactsQueryPort.RunFact> runs = contextFactsQueryPort.listRecentTaskRuns(
            normalizedTaskId,
            recentRunLimit
        );
        List<String> requiredToolpackIds = parseStringArray(taskPlanning.requiredToolpacksJson());
        List<ContextFactsQueryPort.ToolpackFact> toolpacks = contextFactsQueryPort.listToolpacksByIds(requiredToolpackIds);
        List<String> ticketEventRefs = buildTicketEventRefs(tickets);

        String requirementRef = requirementOptional
            .filter(req -> req.docId() != null && req.baselineVersion() != null)
            .map(req -> "req:" + req.docId() + "@v" + req.baselineVersion())
            .orElse("req:session:" + taskPlanning.sessionId() + "@UNCONFIRMED");
        List<String> architectureRefs = buildArchitectureRefs(tickets);
        List<String> decisionRefs = buildDecisionRefs(tickets);
        decisionRefs = enrichDecisionRefsWithRecentResponses(decisionRefs, tickets);
        List<String> priorRunRefs = buildPriorRunRefs(runs);
        String repoBaselineRef = resolveRepoBaselineRef(runs);

        Instant generatedAt = Instant.now();
        TaskContextPack draftTaskContextPack = new TaskContextPack(
            null,
            normalizedTaskId,
            normalizedRunKind,
            requirementRef,
            architectureRefs,
            "module:" + taskPlanning.moduleId(),
            priorRunRefs,
            repoBaselineRef,
            decisionRefs
        );
        TaskSkill draftTaskSkill = new TaskSkill(
            null,
            generateSkillId(),
            normalizedTaskId,
            generatedAt,
            buildSourceFragments(
                taskPlanning.taskTemplateId(),
                taskPlanning.taskTitle(),
                requirementOptional,
                requiredToolpackIds,
                toolpacks
            ),
            buildToolpackAssumptions(requiredToolpackIds, toolpacks),
            TaskSkillTemplateSupport.buildConventions(taskPlanning.taskTemplateId()),
            TaskSkillTemplateSupport.buildRecommendedCommands(
                taskPlanning.taskTemplateId(),
                requiredToolpackIds,
                normalizedRunKind
            ),
            TaskSkillTemplateSupport.buildPitfalls(normalizedRunKind, taskPlanning.taskTemplateId()),
            List.of(
                "Missing required facts must be raised via NEED_CLARIFICATION.",
                "Architecture tradeoffs or governance choices must be raised via NEED_DECISION."
            ),
            TaskSkillTemplateSupport.buildExpectedOutputs(normalizedRunKind, taskPlanning.taskTemplateId())
        );

        String sourceFingerprint = computeFingerprint(
            draftTaskContextPack,
            draftTaskSkill,
            tickets,
            runs,
            ticketEventRefs
        );

        Optional<TaskContextSnapshot> existingReady = snapshotRepository.findLatestReadyByFingerprint(
            normalizedTaskId,
            normalizedRunKind,
            sourceFingerprint
        );
        if (existingReady.isPresent()) {
            TaskContextSnapshot existing = existingReady.get();
            return new CompileArtifactsResult(
                withSnapshotId(draftTaskContextPack, existing.snapshotId()),
                withSnapshotId(draftTaskSkill, existing.snapshotId())
            );
        }

        snapshotRepository.markReadyAsStale(normalizedTaskId, normalizedRunKind, Instant.now());

        String snapshotId = generateSnapshotId();
        Instant now = Instant.now();
        TaskContextSnapshot snapshot = new TaskContextSnapshot(
            snapshotId,
            normalizedTaskId,
            normalizedRunKind,
            TaskContextSnapshotStatus.PENDING,
            normalizedTriggerType,
            sourceFingerprint,
            null,
            null,
            null,
            null,
            null,
            now.plus(retentionDays, ChronoUnit.DAYS),
            now,
            now
        );
        snapshotRepository.save(snapshot);
        boolean movedToCompiling = snapshotRepository.transitionStatus(
            snapshotId,
            TaskContextSnapshotStatus.PENDING,
            TaskContextSnapshotStatus.COMPILING,
            Instant.now()
        );
        if (!movedToCompiling) {
            throw new IllegalStateException("Failed to move snapshot to COMPILING: " + snapshotId);
        }

        try {
            TaskContextPack taskContextPack = withSnapshotId(draftTaskContextPack, snapshotId);
            TaskSkill taskSkill = withSnapshotId(draftTaskSkill, snapshotId);
            RepoContextQueryPort.RepoContext repoContext = safeQueryRepoContext(
                buildRepoQueryForTask(taskPlanning, taskContextPack, taskSkill),
                List.of("./"),
                26,
                4,
                900,
                4_800
            );

            String taskContextRef = artifactStorePort.store(
                "context/task-context-packs/" + normalizedTaskId + "/" + normalizedRunKind + "/" + snapshotId + ".json",
                objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(taskContextPack)
            );
            String taskSkillRef = artifactStorePort.store(
                "context/task-skills/" + normalizedTaskId + "/" + normalizedRunKind + "/" + snapshotId + ".md",
                TaskSkillTemplateSupport.renderTaskSkillMarkdown(taskSkill, repoContext)
            );
            Instant compiledAt = Instant.now();
            boolean markedReady = snapshotRepository.markReady(
                snapshotId,
                taskContextRef,
                taskSkillRef,
                compiledAt,
                compiledAt
            );
            if (!markedReady) {
                throw new IllegalStateException("Failed to mark snapshot READY: " + snapshotId);
            }
            return new CompileArtifactsResult(taskContextPack, taskSkill);
        } catch (RuntimeException ex) {
            try {
                snapshotRepository.markFailed(
                    snapshotId,
                    "COMPILE_ERROR",
                    abbreviate(ex.getMessage(), 512),
                    Instant.now()
                );
            } catch (RuntimeException ignored) {
                // best effort: keep original error
            }
            throw ex;
        } catch (Exception ex) {
            try {
                snapshotRepository.markFailed(
                    snapshotId,
                    "COMPILE_ERROR",
                    abbreviate(ex.getMessage(), 512),
                    Instant.now()
                );
            } catch (RuntimeException ignored) {
                // best effort: keep original error
            }
            throw new IllegalStateException("Failed to compile task artifacts for task " + normalizedTaskId, ex);
        }
    }

    private void storeRolePackArtifact(RoleContextPack pack) {
        try {
            String rolePath = pack.role() == null ? "unknown" : pack.role();
            artifactStorePort.store(
                "context/role-packs/" + pack.sessionId() + "/" + rolePath + "/" + pack.packId() + ".json",
                objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(pack)
            );
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to persist role context pack artifact", ex);
        }
    }

    private RepoContextQueryPort.RepoContext safeQueryRepoContext(
        String queryText,
        List<String> includeRoots,
        int maxFiles,
        int maxExcerpts,
        int maxExcerptChars,
        int maxTotalExcerptChars
    ) {
        if (repoContextQueryPort == null) {
            return null;
        }
        try {
            return repoContextQueryPort.query(new RepoContextQueryPort.RepoContextQuery(
                queryText,
                includeRoots,
                maxFiles,
                maxExcerpts,
                maxExcerptChars,
                maxTotalExcerptChars
            ));
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private static void appendRepoContextState(List<String> currentState, RepoContextQueryPort.RepoContext repoContext) {
        if (currentState == null || repoContext == null) {
            return;
        }
        if (repoContext.repoHeadRef() != null && !repoContext.repoHeadRef().isBlank()) {
            currentState.add("Repo baseline: " + abbreviate(repoContext.repoHeadRef().trim(), 80));
        }
        if (repoContext.topLevelEntries() != null && !repoContext.topLevelEntries().isEmpty()) {
            String joined = String.join(
                ", ",
                repoContext.topLevelEntries().subList(0, Math.min(10, repoContext.topLevelEntries().size()))
            );
            currentState.add("Repo top-level: " + abbreviate(joined, 240));
        }
        if (repoContext.relevantFiles() != null && !repoContext.relevantFiles().isEmpty()) {
            List<String> paths = new ArrayList<>();
            for (RepoContextQueryPort.ScoredPath file : repoContext.relevantFiles()) {
                if (file == null || file.path() == null || file.path().isBlank()) {
                    continue;
                }
                paths.add(file.path().trim());
                if (paths.size() >= 6) {
                    break;
                }
            }
            if (!paths.isEmpty()) {
                currentState.add("Repo relevant files: " + abbreviate(String.join(", ", paths), 240));
            }
        }
    }

    private static String buildRepoQueryForRolePack(
        Optional<ContextFactsQueryPort.RequirementBaselineFact> requirementOptional,
        List<ContextFactsQueryPort.TicketFact> tickets
    ) {
        StringBuilder query = new StringBuilder();
        if (requirementOptional != null && requirementOptional.isPresent()) {
            ContextFactsQueryPort.RequirementBaselineFact req = requirementOptional.get();
            appendQueryLine(query, req.title());
            appendQueryLine(query, extractSectionFirstLine(req.content(), "## 1."));
        }
        if (tickets != null) {
            for (ContextFactsQueryPort.TicketFact ticket : tickets) {
                if (ticket == null) {
                    continue;
                }
                appendQueryLine(query, ticket.title());
                appendQueryLine(query, ticket.type());
            }
        }
        return query.toString().trim();
    }

    private static String buildRepoQueryForTask(
        ContextFactsQueryPort.TaskPlanningFact planning,
        TaskContextPack taskContextPack,
        TaskSkill taskSkill
    ) {
        StringBuilder query = new StringBuilder();
        if (planning != null) {
            appendQueryLine(query, planning.taskTitle());
            appendQueryLine(query, planning.taskTemplateId());
        }
        if (taskContextPack != null) {
            appendQueryLine(query, taskContextPack.requirementRef());
            for (String ref : nullSafeList(taskContextPack.decisionRefs())) {
                if (ref != null && ref.startsWith("ticket-summary:")) {
                    appendQueryLine(query, ref);
                }
            }
            for (String ref : nullSafeList(taskContextPack.priorRunRefs())) {
                appendQueryLine(query, ref);
            }
        }
        if (taskSkill != null) {
            for (String frag : nullSafeList(taskSkill.sourceFragments())) {
                appendQueryLine(query, frag);
            }
            for (String line : nullSafeList(taskSkill.toolpackAssumptions())) {
                appendQueryLine(query, line);
            }
        }
        String raw = query.toString().trim();
        if (raw.length() <= 2_200) {
            return raw;
        }
        return raw.substring(0, 2_200);
    }

    private static void appendQueryLine(StringBuilder query, String value) {
        if (query == null || value == null) {
            return;
        }
        String normalized = value.trim();
        if (!normalized.isBlank()) {
            query.append(normalized).append('\n');
        }
    }

    private static List<String> nullSafeList(List<String> value) {
        return value == null ? List.of() : value;
    }

    private String computeFingerprint(
        TaskContextPack taskContextPack,
        TaskSkill taskSkill,
        List<ContextFactsQueryPort.TicketFact> tickets,
        List<ContextFactsQueryPort.RunFact> runs,
        List<String> ticketEventRefs
    ) {
        try {
            ObjectNode root = objectMapper.createObjectNode();
            root.put("task_id", taskContextPack.taskId());
            root.put("run_kind", taskContextPack.runKind());
            root.put("requirement_ref", taskContextPack.requirementRef());
            root.put("module_ref", taskContextPack.moduleRef());
            root.put("repo_baseline_ref", taskContextPack.repoBaselineRef());
            writeArray(root.putArray("architecture_refs"), taskContextPack.architectureRefs());
            writeArray(root.putArray("decision_refs"), taskContextPack.decisionRefs());
            writeArray(root.putArray("prior_run_refs"), taskContextPack.priorRunRefs());
            writeArray(root.putArray("source_fragments"), taskSkill.sourceFragments());
            writeArray(root.putArray("toolpack_assumptions"), taskSkill.toolpackAssumptions());
            ArrayNode ticketArray = root.putArray("ticket_refs");
            for (ContextFactsQueryPort.TicketFact ticket : tickets) {
                ticketArray.add(toTicketRef(ticket));
            }
            writeArray(root.putArray("ticket_event_refs"), ticketEventRefs);
            ArrayNode runArray = root.putArray("run_refs");
            for (ContextFactsQueryPort.RunFact run : runs) {
                runArray.add(run.runId() + "|" + run.status() + "|" + run.runKind());
            }
            String payload = objectMapper.writeValueAsString(root);
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashBytes = digest.digest(payload.getBytes(StandardCharsets.UTF_8));
            return "sha256:" + toHex(hashBytes);
        } catch (RuntimeException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to compute context source fingerprint", ex);
        }
    }

    private List<String> buildTicketEventRefs(List<ContextFactsQueryPort.TicketFact> tickets) {
        if (tickets == null || tickets.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<String> refs = new LinkedHashSet<>();
        for (ContextFactsQueryPort.TicketFact ticket : tickets) {
            if (ticket == null || ticket.ticketId() == null || ticket.ticketId().isBlank()) {
                continue;
            }
            List<ContextFactsQueryPort.TicketEventFact> events = contextFactsQueryPort.listRecentTicketEvents(
                ticket.ticketId(),
                recentTicketLimit
            );
            if (events == null || events.isEmpty()) {
                continue;
            }
            for (ContextFactsQueryPort.TicketEventFact event : events) {
                if (event == null || event.eventType() == null || event.eventId() == null) {
                    continue;
                }
                String eventType = event.eventType().trim().toUpperCase(Locale.ROOT);
                if (!isFingerprintRelevantTicketEvent(eventType)) {
                    continue;
                }
                refs.add(
                    "ticket-event:" + nullSafe(event.ticketId()) + "|" + eventType + "|" + nullSafe(event.eventId())
                );
            }
        }
        return List.copyOf(refs);
    }

    private static boolean isFingerprintRelevantTicketEvent(String eventType) {
        return "DECISION_REQUESTED".equals(eventType)
            || "USER_RESPONDED".equals(eventType)
            || "STATUS_CHANGED".equals(eventType)
            || "ARTIFACT_LINKED".equals(eventType);
    }

    private List<String> buildArchitectureRefs(List<ContextFactsQueryPort.TicketFact> tickets) {
        if (tickets == null || tickets.isEmpty()) {
            return List.of();
        }
        List<String> refs = new ArrayList<>();
        for (ContextFactsQueryPort.TicketFact ticket : tickets) {
            if (refs.size() >= MAX_ARCHITECTURE_REFS) {
                break;
            }
            refs.add(toTicketRef(ticket));
        }
        return refs;
    }

    private List<String> buildDecisionRefs(List<ContextFactsQueryPort.TicketFact> tickets) {
        if (tickets == null || tickets.isEmpty()) {
            return List.of();
        }
        List<String> refs = new ArrayList<>();
        for (ContextFactsQueryPort.TicketFact ticket : tickets) {
            if (refs.size() >= MAX_DECISION_REFS) {
                break;
            }
            String type = nullSafe(ticket.type()).toUpperCase(Locale.ROOT);
            if ("DECISION".equals(type) || "CLARIFICATION".equals(type)) {
                refs.add("ticket:" + ticket.ticketId());
            }
        }
        return refs;
    }

    private List<String> enrichDecisionRefsWithRecentResponses(
        List<String> decisionRefs,
        List<ContextFactsQueryPort.TicketFact> tickets
    ) {
        LinkedHashSet<String> merged = new LinkedHashSet<>();
        LinkedHashSet<String> summaryDedupKeys = new LinkedHashSet<>();
        int summaryCount = 0;
        if (decisionRefs != null) {
            merged.addAll(decisionRefs);
        }
        if (tickets == null || tickets.isEmpty()) {
            return List.copyOf(merged);
        }
        for (ContextFactsQueryPort.TicketFact ticket : tickets) {
            if (ticket == null || ticket.ticketId() == null || ticket.ticketId().isBlank()) {
                continue;
            }
            String ticketType = nullSafe(ticket.type()).toUpperCase(Locale.ROOT);
            if (!"DECISION".equals(ticketType) && !"CLARIFICATION".equals(ticketType)) {
                continue;
            }
            List<ContextFactsQueryPort.TicketEventFact> events = contextFactsQueryPort.listRecentTicketEvents(
                ticket.ticketId(),
                recentTicketLimit
            );
            if (events == null || events.isEmpty()) {
                continue;
            }
            String question = null;
            String answer = null;
            for (ContextFactsQueryPort.TicketEventFact event : events) {
                if (event == null || event.eventType() == null) {
                    continue;
                }
                String eventType = event.eventType().trim().toUpperCase(Locale.ROOT);
                if (question == null && "DECISION_REQUESTED".equals(eventType)) {
                    question = abbreviate(normalizeFreeText(event.body()), 200);
                }
                if (answer == null && "USER_RESPONDED".equals(eventType)) {
                    answer = abbreviate(normalizeFreeText(event.body()), 200);
                }
                if (question != null && answer != null) {
                    break;
                }
            }
            if (answer == null || answer.isBlank()) {
                continue;
            }
            String questionText = (question == null || question.isBlank()) ? "N/A" : question;
            String dedupKey = normalizeFreeText(questionText) + "|A=" + normalizeFreeText(answer);
            if (!summaryDedupKeys.add(dedupKey)) {
                continue;
            }
            merged.add("ticket-summary:" + ticket.ticketId() + "|Q=" + questionText + "|A=" + answer);
            summaryCount++;
            if (summaryCount >= MAX_DECISION_SUMMARIES) {
                break;
            }
        }
        return List.copyOf(merged);
    }

    private List<String> buildPriorRunRefs(List<ContextFactsQueryPort.RunFact> runs) {
        if (runs == null || runs.isEmpty()) {
            return List.of();
        }
        List<String> refs = new ArrayList<>();
        for (ContextFactsQueryPort.RunFact run : runs) {
            if (run == null || run.runId() == null || run.runId().isBlank()) {
                continue;
            }
            StringBuilder ref = new StringBuilder("run:");
            ref.append(run.runId().trim());
            appendRunRefPart(ref, normalizeNullable(run.status()));
            appendRunRefPart(ref, normalizeNullable(run.runKind()));
            String baseCommit = normalizeNullable(run.baseCommit());
            if (baseCommit != null) {
                appendRunRefKeyValue(ref, "base", abbreviate(baseCommit, 40));
            }
            String latestEventType = normalizeNullable(run.latestEventType());
            if (latestEventType != null) {
                appendRunRefKeyValue(ref, "event", latestEventType.toUpperCase(Locale.ROOT));
            }
            String summary = summarizeRunFact(run);
            if (summary != null) {
                appendRunRefKeyValue(ref, "summary", summary);
            }
            refs.add(ref.toString());
        }
        return refs;
    }

    private String summarizeRunFact(ContextFactsQueryPort.RunFact run) {
        if (run == null) {
            return null;
        }
        LinkedHashSet<String> parts = new LinkedHashSet<>();
        String latestEventBody = abbreviate(normalizeFreeText(run.latestEventBody()), 220);
        if (latestEventBody != null && !latestEventBody.isBlank()) {
            parts.add(latestEventBody);
        }
        JsonNode latestEventData = parseJson(run.latestEventDataJson());
        if (latestEventData != null && latestEventData.isObject()) {
            String resultStatus = abbreviate(normalizeFreeText(readJsonText(latestEventData, "result_status")), 40);
            if (resultStatus != null && !resultStatus.isBlank()) {
                parts.add("result=" + resultStatus);
            }
            String workReport = abbreviate(
                summarizeWorkReport(readJsonText(latestEventData, "work_report")),
                260
            );
            if (workReport != null && !workReport.isBlank()) {
                parts.add(workReport);
            }
            String deliveryCommit = normalizeDeliveryCommit(readJsonText(latestEventData, "delivery_commit"));
            if (deliveryCommit != null) {
                parts.add("delivery_commit=" + deliveryCommit);
            }
        }
        if (parts.isEmpty()) {
            return null;
        }
        return abbreviate(String.join("; ", parts), 360);
    }

    private String summarizeWorkReport(String rawWorkReport) {
        String normalized = normalizeFreeText(rawWorkReport);
        if (normalized == null || normalized.isBlank()) {
            return null;
        }
        String rootCause = extractRootCause(normalized);
        if (rootCause != null && !rootCause.isBlank()) {
            return rootCause;
        }
        return normalized;
    }

    private String extractRootCause(String normalizedWorkReport) {
        if (normalizedWorkReport == null || normalizedWorkReport.isBlank()) {
            return null;
        }
        Matcher rootCauseMatcher = ROOT_CAUSE_PATTERN.matcher(normalizedWorkReport);
        if (rootCauseMatcher.find()) {
            String candidate = normalizeFreeText(rootCauseMatcher.group(1));
            if (candidate != null && !candidate.isBlank()) {
                return candidate;
            }
        }
        Matcher exceptionMatcher = EXCEPTION_PATTERN.matcher(normalizedWorkReport);
        while (exceptionMatcher.find()) {
            String candidate = normalizeFreeText(exceptionMatcher.group(1));
            if (candidate == null || candidate.isBlank()) {
                continue;
            }
            if (candidate.toLowerCase(Locale.ROOT).contains("command failed (exit")) {
                continue;
            }
            return candidate;
        }
        return null;
    }

    private JsonNode parseJson(String rawValue) {
        if (rawValue == null || rawValue.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readTree(rawValue);
        } catch (Exception ex) {
            return null;
        }
    }

    private static String readJsonText(JsonNode root, String fieldName) {
        if (root == null || fieldName == null || fieldName.isBlank()) {
            return null;
        }
        JsonNode fieldNode = root.path(fieldName);
        if (fieldNode.isMissingNode() || fieldNode.isNull()) {
            return null;
        }
        if (fieldNode.isTextual()) {
            return fieldNode.asText();
        }
        return fieldNode.toString();
    }

    private static String normalizeDeliveryCommit(String rawValue) {
        String normalized = normalizeNullable(rawValue);
        if (normalized == null) {
            return null;
        }
        String candidate = normalized.startsWith("git:") ? normalized.substring("git:".length()) : normalized;
        candidate = candidate.trim();
        if (candidate.isBlank()) {
            return null;
        }
        return abbreviate(candidate, 40);
    }

    private static void appendRunRefPart(StringBuilder ref, String rawValue) {
        String normalized = normalizeNullable(rawValue);
        if (normalized == null) {
            return;
        }
        ref.append('|').append(normalized.toUpperCase(Locale.ROOT));
    }

    private static void appendRunRefKeyValue(StringBuilder ref, String key, String rawValue) {
        String normalized = normalizeNullable(rawValue);
        if (normalized == null) {
            return;
        }
        ref.append('|').append(key).append('=').append(normalized);
    }

    private static String resolveRepoBaselineRef(List<ContextFactsQueryPort.RunFact> runs) {
        if (runs == null || runs.isEmpty()) {
            return "git:BASELINE_UNAVAILABLE";
        }
        for (ContextFactsQueryPort.RunFact run : runs) {
            if (run.baseCommit() != null && !run.baseCommit().isBlank()) {
                return "git:" + run.baseCommit().trim();
            }
        }
        return "git:BASELINE_UNAVAILABLE";
    }

    private static List<String> buildSourceFragments(
        String taskTemplateId,
        String taskTitle,
        Optional<ContextFactsQueryPort.RequirementBaselineFact> requirementOptional,
        List<String> requiredToolpackIds,
        List<ContextFactsQueryPort.ToolpackFact> toolpacks
    ) {
        LinkedHashSet<String> fragments = new LinkedHashSet<>();
        fragments.add("contextpack_v1:repo_context=lexical_v1");
        fragments.add("template:" + nullSafe(taskTemplateId));
        if (taskTitle != null && !taskTitle.isBlank()) {
            fragments.add("task_title:" + abbreviate(normalizeFreeText(taskTitle), 220));
        }
        if ("tmpl.init.v0".equalsIgnoreCase(nullSafe(taskTemplateId))) {
            fragments.add("init_scope:bootstrap scaffold only; no business endpoints, controllers, or feature tests");
            fragments.add("init_outputs:build file, runtime entrypoint, minimal config, and project-level docs only");
        }
        fragments.addAll(buildRequirementHighlights(requirementOptional));
        if (toolpacks != null) {
            for (ContextFactsQueryPort.ToolpackFact toolpack : toolpacks) {
                fragments.add("toolpack:" + toolpack.toolpackId() + "@" + nullSafe(toolpack.version()));
            }
        }
        if (requiredToolpackIds != null) {
            for (String id : requiredToolpackIds) {
                fragments.add("toolpack:" + id);
            }
        }
        return List.copyOf(fragments);
    }

    private static List<String> buildRequirementHighlights(
        Optional<ContextFactsQueryPort.RequirementBaselineFact> requirementOptional
    ) {
        if (requirementOptional == null || requirementOptional.isEmpty()) {
            return List.of();
        }
        ContextFactsQueryPort.RequirementBaselineFact requirement = requirementOptional.get();
        LinkedHashSet<String> highlights = new LinkedHashSet<>();
        if (requirement.title() != null && !requirement.title().isBlank()) {
            highlights.add("requirement_title:" + abbreviate(normalizeFreeText(requirement.title()), 220));
        }
        String summary = extractSectionFirstLine(requirement.content(), "## 1.");
        if (!summary.isBlank()) {
            highlights.add("requirement_summary:" + abbreviate(normalizeFreeText(summary), 220));
        }
        appendRequirementBullets(
            highlights,
            "scope",
            extractSectionSubsectionList(requirement.content(), "## 4.", "### 包含", "### In"),
            5,
            true
        );
        appendRequirementBullets(
            highlights,
            "scope_out",
            extractSectionSubsectionList(requirement.content(), "## 4.", "### 不包含", "### Out"),
            3,
            true
        );
        appendRequirementBullets(highlights, "goal", extractSectionList(requirement.content(), "## 2."), 2);
        appendRequirementBullets(highlights, "constraint", extractSectionList(requirement.content(), "## 6."), 4, true);
        appendRequirementBullets(highlights, "acceptance", extractSectionList(requirement.content(), "## 5."), 2);
        if (highlights.size() > MAX_REQUIREMENT_HIGHLIGHTS) {
            return List.copyOf(new ArrayList<>(highlights).subList(0, MAX_REQUIREMENT_HIGHLIGHTS));
        }
        return List.copyOf(highlights);
    }

    private static void appendRequirementBullets(
        LinkedHashSet<String> highlights,
        String prefix,
        List<String> bullets,
        int maxItems
    ) {
        appendRequirementBullets(highlights, prefix, bullets, maxItems, false);
    }

    private static void appendRequirementBullets(
        LinkedHashSet<String> highlights,
        String prefix,
        List<String> bullets,
        int maxItems,
        boolean preserveCase
    ) {
        if (bullets == null || bullets.isEmpty() || maxItems <= 0) {
            return;
        }
        int appended = 0;
        for (String bullet : bullets) {
            if (bullet == null || bullet.isBlank()) {
                continue;
            }
            String normalized = preserveCase
                ? normalizeRequirementTextPreserveCase(bullet)
                : normalizeFreeText(bullet);
            highlights.add("requirement_" + prefix + ":" + abbreviate(normalized, 260));
            appended++;
            if (appended >= maxItems || highlights.size() >= MAX_REQUIREMENT_HIGHLIGHTS) {
                break;
            }
        }
    }

    private static List<String> buildToolpackAssumptions(
        List<String> requiredToolpackIds,
        List<ContextFactsQueryPort.ToolpackFact> toolpacks
    ) {
        if (toolpacks == null || toolpacks.isEmpty()) {
            return requiredToolpackIds == null ? List.of() : requiredToolpackIds.stream()
                .map(id -> "toolpack:" + id)
                .toList();
        }
        List<String> assumptions = new ArrayList<>(toolpacks.size());
        for (ContextFactsQueryPort.ToolpackFact toolpack : toolpacks) {
            assumptions.add(
                "toolpack:" + toolpack.toolpackId()
                    + " (" + nullSafe(toolpack.name()) + " " + nullSafe(toolpack.version()) + ")"
            );
        }
        return assumptions;
    }

    private static void writeArray(ArrayNode arrayNode, List<String> values) {
        if (values == null) {
            return;
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                arrayNode.add(value);
            }
        }
    }

    private static List<String> extractSectionList(String markdown, String sectionPrefix) {
        if (markdown == null || markdown.isBlank()) {
            return List.of();
        }
        String[] lines = markdown.split("\\R");
        List<String> result = new ArrayList<>();
        boolean inTarget = false;
        for (String line : lines) {
            if (line == null) {
                continue;
            }
            String trimmed = line.trim();
            if (trimmed.startsWith("## ")) {
                inTarget = trimmed.startsWith(sectionPrefix);
                continue;
            }
            if (!inTarget) {
                continue;
            }
            if (trimmed.startsWith("- ")) {
                result.add(trimmed.substring(2).trim());
            }
        }
        return result;
    }

    private static List<String> extractSectionSubsectionList(
        String markdown,
        String sectionPrefix,
        String... subsectionPrefixes
    ) {
        if (markdown == null || markdown.isBlank() || subsectionPrefixes == null || subsectionPrefixes.length == 0) {
            return List.of();
        }
        String[] lines = markdown.split("\\R");
        List<String> result = new ArrayList<>();
        boolean inTargetSection = false;
        boolean inTargetSubsection = false;
        for (String line : lines) {
            if (line == null) {
                continue;
            }
            String trimmed = line.trim();
            if (trimmed.startsWith("## ")) {
                inTargetSection = trimmed.startsWith(sectionPrefix);
                inTargetSubsection = false;
                continue;
            }
            if (!inTargetSection) {
                continue;
            }
            if (trimmed.startsWith("### ")) {
                inTargetSubsection = matchesAnyHeadingPrefix(trimmed, subsectionPrefixes);
                continue;
            }
            if (inTargetSubsection && trimmed.startsWith("- ")) {
                result.add(trimmed.substring(2).trim());
            }
        }
        return result;
    }

    private static boolean matchesAnyHeadingPrefix(String value, String... prefixes) {
        if (value == null || prefixes == null || prefixes.length == 0) {
            return false;
        }
        for (String prefix : prefixes) {
            if (prefix != null && !prefix.isBlank() && value.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    private static String extractSectionFirstLine(String markdown, String sectionPrefix) {
        if (markdown == null || markdown.isBlank()) {
            return "";
        }
        String[] lines = markdown.split("\\R");
        boolean inTarget = false;
        for (String line : lines) {
            if (line == null) {
                continue;
            }
            String trimmed = line.trim();
            if (trimmed.startsWith("## ")) {
                inTarget = trimmed.startsWith(sectionPrefix);
                continue;
            }
            if (!inTarget || trimmed.isBlank()) {
                continue;
            }
            if (trimmed.startsWith("- ")) {
                return trimmed.substring(2).trim();
            }
            return trimmed;
        }
        return "";
    }

    private static String normalizeRequirementTextPreserveCase(String value) {
        if (value == null) {
            return "";
        }
        return value.replaceAll("\\s+", " ").trim();
    }

    private List<String> parseStringArray(String jsonText) {
        String normalized = requireNotBlank(jsonText, "requiredToolpacksJson");
        try {
            JsonNode root = objectMapper.readTree(normalized);
            if (root == null || !root.isArray()) {
                throw new IllegalArgumentException("requiredToolpacksJson must be JSON array text");
            }
            LinkedHashSet<String> values = new LinkedHashSet<>();
            for (JsonNode node : root) {
                if (!node.isTextual()) {
                    throw new IllegalArgumentException("requiredToolpacksJson element must be string");
                }
                String value = node.asText().trim();
                if (!value.isEmpty()) {
                    values.add(value);
                }
            }
            return List.copyOf(values);
        } catch (RuntimeException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalArgumentException("requiredToolpacksJson must be valid JSON array text", ex);
        }
    }

    private static String normalizeRunKind(String runKind) {
        String normalized = requireNotBlank(runKind, "runKind").toUpperCase(Locale.ROOT);
        if (!"IMPL".equals(normalized) && !"VERIFY".equals(normalized)) {
            throw new IllegalArgumentException("runKind must be IMPL or VERIFY");
        }
        return normalized;
    }

    private static String toTicketRef(ContextFactsQueryPort.TicketFact ticket) {
        if (ticket == null) {
            return "ticket:UNKNOWN";
        }
        StringBuilder ref = new StringBuilder("ticket:")
            .append(nullSafe(ticket.ticketId()));
        if (ticket.type() != null && !ticket.type().isBlank()) {
            ref.append("|").append(ticket.type().trim().toUpperCase(Locale.ROOT));
        }
        if (ticket.requirementDocId() != null && !ticket.requirementDocId().isBlank()) {
            ref.append("|req=").append(ticket.requirementDocId().trim());
            if (ticket.requirementDocVer() != null) {
                ref.append("@").append(ticket.requirementDocVer());
            }
        }
        return ref.toString();
    }

    private static String resolveRunKindByTemplate(String taskTemplateId) {
        if ("tmpl.verify.v0".equalsIgnoreCase(nullSafe(taskTemplateId))) {
            return "VERIFY";
        }
        return "IMPL";
    }

    private static TaskContextPack withSnapshotId(TaskContextPack pack, String snapshotId) {
        return new TaskContextPack(
            snapshotId,
            pack.taskId(),
            pack.runKind(),
            pack.requirementRef(),
            pack.architectureRefs(),
            pack.moduleRef(),
            pack.priorRunRefs(),
            pack.repoBaselineRef(),
            pack.decisionRefs()
        );
    }

    private static TaskSkill withSnapshotId(TaskSkill skill, String snapshotId) {
        return new TaskSkill(
            snapshotId,
            skill.skillId(),
            skill.taskId(),
            skill.generatedAt(),
            skill.sourceFragments(),
            skill.toolpackAssumptions(),
            skill.conventions(),
            skill.recommendedCommands(),
            skill.pitfalls(),
            skill.stopRules(),
            skill.expectedOutputs()
        );
    }

    private static String requireNotBlank(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value.trim();
    }

    private static String nullSafe(String value) {
        return value == null ? "" : value;
    }

    private static String normalizeNullable(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private static String normalizeFreeText(String value) {
        if (value == null) {
            return "";
        }
        return value.replaceAll("\\s+", " ").trim();
    }

    private static String abbreviate(String value, int maxLen) {
        if (value == null) {
            return "";
        }
        if (value.length() <= maxLen) {
            return value;
        }
        return value.substring(0, maxLen);
    }

    private static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    private static String generateRolePackId() {
        return "CTX-" + UUID.randomUUID().toString().replace("-", "");
    }

    private static String generateSkillId() {
        return "TSKILL-" + UUID.randomUUID().toString().replace("-", "");
    }

    private static String generateSnapshotId() {
        return "CTXS-" + UUID.randomUUID().toString().replace("-", "");
    }

    private record CompileArtifactsResult(
        TaskContextPack taskContextPack,
        TaskSkill taskSkill
    ) {
    }
}
