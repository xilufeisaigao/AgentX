package com.agentx.agentxbackend.execution.application;

import com.agentx.agentxbackend.execution.application.port.in.RunCommandUseCase;
import com.agentx.agentxbackend.execution.application.port.in.RunInternalUseCase;
import com.agentx.agentxbackend.execution.application.port.in.RunLeaseRecoveryUseCase;
import com.agentx.agentxbackend.execution.application.port.in.RunQueryUseCase;
import com.agentx.agentxbackend.execution.application.port.out.ContextSnapshotReadPort;
import com.agentx.agentxbackend.execution.application.port.out.DomainEventPublisher;
import com.agentx.agentxbackend.execution.application.port.out.TaskAllocationPort;
import com.agentx.agentxbackend.execution.application.port.out.TaskRunEventRepository;
import com.agentx.agentxbackend.execution.application.port.out.TaskRunRepository;
import com.agentx.agentxbackend.execution.application.port.out.WorkerRuntimePort;
import com.agentx.agentxbackend.execution.application.port.out.WorkspacePort;
import com.agentx.agentxbackend.execution.domain.event.RunFinishedEvent;
import com.agentx.agentxbackend.execution.domain.event.RunNeedsClarificationEvent;
import com.agentx.agentxbackend.execution.domain.event.RunNeedsDecisionEvent;
import com.agentx.agentxbackend.execution.domain.model.GitAlloc;
import com.agentx.agentxbackend.execution.domain.model.RunFinishedPayload;
import com.agentx.agentxbackend.execution.domain.model.RunEventType;
import com.agentx.agentxbackend.execution.domain.model.RunKind;
import com.agentx.agentxbackend.execution.domain.model.RunStatus;
import com.agentx.agentxbackend.execution.domain.model.TaskContext;
import com.agentx.agentxbackend.execution.domain.model.TaskPackage;
import com.agentx.agentxbackend.execution.domain.model.TaskRun;
import com.agentx.agentxbackend.execution.domain.model.TaskRunEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Service
public class RunCommandService implements RunCommandUseCase, RunInternalUseCase, RunLeaseRecoveryUseCase, RunQueryUseCase {

    private static final Logger log = LoggerFactory.getLogger(RunCommandService.class);
    private static final Set<String> VERIFY_COMMAND_PREFIX_ALLOWLIST = Set.of(
        "mvn",
        "./mvnw",
        "gradle",
        "./gradlew",
        "python",
        "pytest",
        "npm",
        "pnpm",
        "yarn",
        "go",
        "dotnet",
        "cargo",
        "git",
        "bash",
        "sh",
        "pwsh",
        "powershell"
    );

    private final TaskRunRepository taskRunRepository;
    private final TaskRunEventRepository taskRunEventRepository;
    private final TaskAllocationPort taskAllocationPort;
    private final WorkspacePort workspacePort;
    private final ContextSnapshotReadPort contextSnapshotReadPort;
    private final WorkerRuntimePort workerRuntimePort;
    private final DomainEventPublisher domainEventPublisher;
    private final ObjectMapper objectMapper;
    private final int leaseSeconds;
    private final String defaultBaseCommit;
    private final int verifyMaxAttemptsPerCandidate;
    private final String verifyWorkerId;
    private final Path repoRoot;
    private final List<Path> artifactReadRoots;

    public RunCommandService(
        TaskRunRepository taskRunRepository,
        TaskRunEventRepository taskRunEventRepository,
        TaskAllocationPort taskAllocationPort,
        WorkspacePort workspacePort,
        ContextSnapshotReadPort contextSnapshotReadPort,
        WorkerRuntimePort workerRuntimePort,
        DomainEventPublisher domainEventPublisher,
        ObjectMapper objectMapper,
        @Value("${agentx.execution.lease-seconds:300}") int leaseSeconds,
        @Value("${agentx.execution.default-base-commit:BASELINE_UNAVAILABLE}") String defaultBaseCommit,
        @Value("${agentx.execution.verify.max-attempts-per-candidate:3}") int verifyMaxAttemptsPerCandidate,
        @Value("${agentx.execution.verify-worker-id:WRK-VERIFY-SYSTEM}") String verifyWorkerId,
        @Value("${agentx.execution.repo-root:.}") String repoRoot,
        @Value("${agentx.contextpack.artifact-root:.agentx}") String contextpackArtifactRoot
    ) {
        this.taskRunRepository = taskRunRepository;
        this.taskRunEventRepository = taskRunEventRepository;
        this.taskAllocationPort = taskAllocationPort;
        this.workspacePort = workspacePort;
        this.contextSnapshotReadPort = contextSnapshotReadPort;
        this.workerRuntimePort = workerRuntimePort;
        this.domainEventPublisher = domainEventPublisher;
        this.objectMapper = objectMapper;
        this.leaseSeconds = Math.max(60, leaseSeconds);
        this.defaultBaseCommit = (defaultBaseCommit == null || defaultBaseCommit.isBlank())
            ? "BASELINE_UNAVAILABLE"
            : defaultBaseCommit.trim();
        this.verifyMaxAttemptsPerCandidate = Math.max(1, verifyMaxAttemptsPerCandidate);
        this.verifyWorkerId = requireNotBlank(verifyWorkerId, "verifyWorkerId");
        this.repoRoot = Path.of(repoRoot == null || repoRoot.isBlank() ? "." : repoRoot.trim())
            .toAbsolutePath()
            .normalize();
        Path contextpackRoot = Path.of(
            contextpackArtifactRoot == null || contextpackArtifactRoot.isBlank()
                ? ".agentx"
                : contextpackArtifactRoot.trim()
        );
        if (!contextpackRoot.isAbsolute()) {
            contextpackRoot = this.repoRoot.resolve(contextpackRoot);
        }
        contextpackRoot = contextpackRoot.toAbsolutePath().normalize();
        LinkedHashSet<Path> artifactRoots = new LinkedHashSet<>();
        artifactRoots.add(this.repoRoot);
        artifactRoots.add(contextpackRoot);
        this.artifactReadRoots = List.copyOf(artifactRoots);
    }

    @Override
    @Transactional
    public Optional<TaskPackage> claimTask(String workerId) {
        String normalizedWorkerId = requireNotBlank(workerId, "workerId");
        if (!workerRuntimePort.workerExists(normalizedWorkerId)) {
            throw new NoSuchElementException("Worker not found: " + normalizedWorkerId);
        }
        if (!workerRuntimePort.isWorkerReady(normalizedWorkerId)) {
            throw new IllegalStateException("Worker is not READY: " + normalizedWorkerId);
        }
        String runId = generateRunId();
        Optional<TaskAllocationPort.ClaimedTask> claimedTaskOptional = taskAllocationPort.claimReadyTaskForWorker(
            normalizedWorkerId,
            runId
        );
        if (claimedTaskOptional.isEmpty()) {
            return Optional.empty();
        }
        TaskAllocationPort.ClaimedTask claimedTask = claimedTaskOptional.get();
        boolean initGateActive = taskAllocationPort.isInitGateActive(claimedTask.sessionId());
        if (initGateActive && taskRunRepository.existsActiveRunBySessionId(claimedTask.sessionId())) {
            taskAllocationPort.releaseTaskAssignment(claimedTask.taskId());
            throw new PreconditionFailedException(
                "INIT gate is active for session " + claimedTask.sessionId()
                    + ": only one active run is allowed until tmpl.init.v0 task reaches DONE."
            );
        }
        if (!taskAllocationPort.isSessionActive(claimedTask.sessionId())) {
            taskAllocationPort.releaseTaskAssignment(claimedTask.taskId());
            log.debug(
                "Skip task claim because session is not ACTIVE, sessionId={}, taskId={}",
                claimedTask.sessionId(),
                claimedTask.taskId()
            );
            return Optional.empty();
        }
        RunKind runKind = resolveRunKind(claimedTask.taskTemplateId());

        if (initGateActive && !isInitTemplate(claimedTask.taskTemplateId())) {
            taskAllocationPort.releaseTaskAssignment(claimedTask.taskId());
            log.debug(
                "Skip non-init task under init gate, taskId={}, templateId={}, sessionId={}",
                claimedTask.taskId(),
                claimedTask.taskTemplateId(),
                claimedTask.sessionId()
            );
            return Optional.empty();
        }

        ContextSnapshotReadPort.ReadySnapshot readySnapshot = contextSnapshotReadPort
            .findLatestReadySnapshot(claimedTask.taskId(), runKind)
            .orElseThrow(() -> {
                taskAllocationPort.releaseTaskAssignment(claimedTask.taskId());
                return new PreconditionFailedException(
                    "No READY context snapshot available for task " + claimedTask.taskId() + ", runKind=" + runKind
                );
            });

        String baseCommit = resolveBaseCommitFromContextRef(readySnapshot.taskContextRef());
        if (baseCommit == null || baseCommit.isBlank()) {
            baseCommit = defaultBaseCommit;
        }
        String branchName = "run/" + runId;
        String worktreePath = buildWorktreePath(claimedTask.sessionId(), runId);

        Instant now = Instant.now();
        TaskRun run = new TaskRun(
            runId,
            claimedTask.taskId(),
            normalizedWorkerId,
            RunStatus.RUNNING,
            runKind,
            readySnapshot.snapshotId(),
            now.plusSeconds(leaseSeconds),
            now,
            now,
            null,
            readySnapshot.taskSkillRef(),
            toJsonArray(workerRuntimePort.listWorkerToolpackIds(normalizedWorkerId)),
            baseCommit,
            branchName,
            worktreePath,
            now,
            now
        );
        try {
            taskRunRepository.save(run);
            workspacePort.allocateWorkspace(
                runId,
                claimedTask.sessionId(),
                claimedTask.taskId(),
                baseCommit,
                branchName
            );
            taskRunEventRepository.save(newEvent(
                runId,
                RunEventType.RUN_STARTED,
                "Run started by worker claim.",
                null
            ));
        } catch (RuntimeException ex) {
            safeReleaseWorkspace(runId, worktreePath);
            taskAllocationPort.releaseTaskAssignment(claimedTask.taskId());
            throw ex;
        }

        return Optional.of(toTaskPackage(claimedTask, run, readySnapshot));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<TaskPackage> pickupRunningVerifyRun(String workerId) {
        String normalizedWorkerId = requireNotBlank(workerId, "workerId");
        if (!workerRuntimePort.workerExists(normalizedWorkerId) || !workerRuntimePort.isWorkerReady(normalizedWorkerId)) {
            return Optional.empty();
        }
        Optional<TaskRun> runningVerifyOptional = taskRunRepository.findOldestRunningVerifyRunByWorker(normalizedWorkerId);
        if (runningVerifyOptional.isEmpty()) {
            return Optional.empty();
        }
        TaskRun run = runningVerifyOptional.get();
        List<String> requiredToolpacks = parseRequiredToolpacks(run.toolpacksSnapshotJson());
        ContextSnapshotReadPort.ReadySnapshot readySnapshot = contextSnapshotReadPort
            .findLatestReadySnapshot(run.taskId(), RunKind.VERIFY)
            .orElse(new ContextSnapshotReadPort.ReadySnapshot(
                run.contextSnapshotId(),
                null,
                run.taskSkillRef()
            ));
        TaskContext taskContext = resolveTaskContext(run, readySnapshot);
        List<String> verifyCommands = resolveVerifyCommands(run.taskSkillRef(), requiredToolpacks);
        return Optional.of(new TaskPackage(
            run.runId(),
            run.taskId(),
            "Verify merge candidate for task " + run.taskId(),
            "mergegate",
            run.contextSnapshotId(),
            RunKind.VERIFY,
            "tmpl.verify.v0",
            requiredToolpacks,
            run.taskSkillRef(),
            taskContext,
            List.of("./"),
            List.of(),
            verifyCommands,
            List.of(
                "VERIFY run must remain read-only.",
                "Any verification failure should fail the run with concise evidence."
            ),
            List.of("work_report"),
            new GitAlloc(
                run.baseCommit(),
                run.branchName(),
                run.worktreePath()
            )
        ));
    }

    @Override
    @Transactional
    public TaskRun heartbeat(String runId) {
        String normalizedRunId = requireNotBlank(runId, "runId");
        TaskRun current = taskRunRepository.findById(normalizedRunId)
            .orElseThrow(() -> new NoSuchElementException("Run not found: " + normalizedRunId));
        if (isTerminal(current.status())) {
            throw new IllegalStateException(
                "Cannot heartbeat terminal run: " + normalizedRunId + ", status=" + current.status()
            );
        }

        Instant now = Instant.now();
        TaskRun updated = new TaskRun(
            current.runId(),
            current.taskId(),
            current.workerId(),
            current.status(),
            current.runKind(),
            current.contextSnapshotId(),
            now.plusSeconds(leaseSeconds),
            now,
            current.startedAt(),
            current.finishedAt(),
            current.taskSkillRef(),
            current.toolpacksSnapshotJson(),
            current.baseCommit(),
            current.branchName(),
            current.worktreePath(),
            current.createdAt(),
            now
        );
        taskRunRepository.update(updated);
        taskRunEventRepository.save(newEvent(
            normalizedRunId,
            RunEventType.HEARTBEAT,
            "Heartbeat received, lease extended.",
            null
        ));
        return updated;
    }

    @Override
    @Transactional
    public TaskRunEvent appendEvent(String runId, String eventType, String body, String dataJson) {
        String normalizedRunId = requireNotBlank(runId, "runId");
        requireNotBlank(body, "body");
        RunEventType parsedEventType = RunEventType.valueOf(requireNotBlank(eventType, "eventType").toUpperCase(Locale.ROOT));
        TaskRun current = taskRunRepository.findById(normalizedRunId)
            .orElseThrow(() -> new NoSuchElementException("Run not found: " + normalizedRunId));

        if (isTerminal(current.status())) {
            throw new IllegalStateException(
                "Cannot append event to terminal run: " + normalizedRunId + ", status=" + current.status()
            );
        }
        TaskRunEvent event = newEvent(normalizedRunId, parsedEventType, body, dataJson);
        taskRunEventRepository.save(event);

        if (parsedEventType == RunEventType.NEED_DECISION || parsedEventType == RunEventType.NEED_CLARIFICATION) {
            TaskRun waiting = new TaskRun(
                current.runId(),
                current.taskId(),
                current.workerId(),
                RunStatus.WAITING_FOREMAN,
                current.runKind(),
                current.contextSnapshotId(),
                current.leaseUntil(),
                current.lastHeartbeatAt(),
                current.startedAt(),
                current.finishedAt(),
                current.taskSkillRef(),
                current.toolpacksSnapshotJson(),
                current.baseCommit(),
                current.branchName(),
                current.worktreePath(),
                current.createdAt(),
                Instant.now()
            );
            taskRunRepository.update(waiting);
            if (parsedEventType == RunEventType.NEED_DECISION) {
                domainEventPublisher.publish(new RunNeedsDecisionEvent(
                    waiting.runId(),
                    waiting.taskId(),
                    body,
                    dataJson
                ));
            } else {
                domainEventPublisher.publish(new RunNeedsClarificationEvent(
                    waiting.runId(),
                    waiting.taskId(),
                    body,
                    dataJson
                ));
            }
        }
        return event;
    }

    @Override
    @Transactional
    public TaskRun finishRun(String runId, RunFinishedPayload payload) {
        String normalizedRunId = requireNotBlank(runId, "runId");
        if (payload == null) {
            throw new IllegalArgumentException("payload must not be null");
        }
        TaskRun current = taskRunRepository.findById(normalizedRunId)
            .orElseThrow(() -> new NoSuchElementException("Run not found: " + normalizedRunId));
        if (isTerminal(current.status())) {
            return current;
        }
        RunStatus resultStatus = parseResultStatus(payload.resultStatus());
        if (shouldUpdateTaskBranch(current, resultStatus)) {
            String sessionId = resolveSessionIdForTask(current.taskId());
            workspacePort.updateTaskBranch(
                sessionId,
                current.taskId(),
                requireNotBlank(payload.deliveryCommit(), "deliveryCommit")
            );
        }
        Instant now = Instant.now();
        TaskRun finished = new TaskRun(
            current.runId(),
            current.taskId(),
            current.workerId(),
            resultStatus,
            current.runKind(),
            current.contextSnapshotId(),
            current.leaseUntil(),
            current.lastHeartbeatAt(),
            current.startedAt(),
            now,
            current.taskSkillRef(),
            current.toolpacksSnapshotJson(),
            current.baseCommit(),
            current.branchName(),
            current.worktreePath(),
            current.createdAt(),
            now
        );
        taskRunRepository.update(finished);
        taskRunEventRepository.save(newEvent(
            normalizedRunId,
            RunEventType.RUN_FINISHED,
            "Run finished with status " + resultStatus.name(),
            toRunFinishedDataJson(payload, resultStatus)
        ));
        safeReleaseWorkspace(normalizedRunId, current.worktreePath());
        if (shouldReleaseAssignmentOnFinish(current, resultStatus)) {
            try {
                taskAllocationPort.releaseTaskAssignment(current.taskId());
            } catch (RuntimeException ex) {
                log.warn(
                    "Failed to release assignment after run finished, runId={}, taskId={}, status={}",
                    normalizedRunId,
                    current.taskId(),
                    resultStatus,
                    ex
                );
            }
        }
        domainEventPublisher.publish(new RunFinishedEvent(
            finished.runId(),
            finished.taskId(),
            finished.runKind(),
            finished.baseCommit(),
            payload
        ));
        return finished;
    }

    @Override
    @Transactional
    public TaskRun createVerifyRun(String taskId, String mergeCandidateCommit) {
        String normalizedTaskId = requireNotBlank(taskId, "taskId");
        String normalizedMergeCandidateCommit = requireNotBlank(mergeCandidateCommit, "mergeCandidateCommit");
        if (!workerRuntimePort.workerExists(verifyWorkerId) || !workerRuntimePort.isWorkerReady(verifyWorkerId)) {
            throw new PreconditionFailedException(
                "Verify worker is not READY or does not exist: " + verifyWorkerId
            );
        }
        ContextSnapshotReadPort.ReadySnapshot readySnapshot = contextSnapshotReadPort
            .findLatestReadySnapshot(normalizedTaskId, RunKind.VERIFY)
            .orElseThrow(() -> new PreconditionFailedException(
                "No READY context snapshot available for task " + normalizedTaskId + ", runKind=VERIFY"
            ));
          ensureNoDuplicateVerifyRun(normalizedTaskId, normalizedMergeCandidateCommit);

          String runId = generateRunId();
          String branchName = "run/" + runId;
          String sessionId = resolveSessionIdForTask(normalizedTaskId);
          ensureSessionActive(sessionId, normalizedTaskId);
          String worktreePath = buildWorktreePath(sessionId, runId);
        Instant now = Instant.now();
        TaskRun run = new TaskRun(
            runId,
            normalizedTaskId,
            verifyWorkerId,
            RunStatus.RUNNING,
            RunKind.VERIFY,
            readySnapshot.snapshotId(),
            now.plusSeconds(leaseSeconds),
            now,
            now,
            null,
            readySnapshot.taskSkillRef(),
            toJsonArray(workerRuntimePort.listWorkerToolpackIds(verifyWorkerId)),
            normalizedMergeCandidateCommit,
            branchName,
            worktreePath,
            now,
            now
        );
        try {
            taskRunRepository.save(run);
            workspacePort.allocateWorkspace(
                runId,
                sessionId,
                normalizedTaskId,
                normalizedMergeCandidateCommit,
                branchName
            );
            taskRunEventRepository.save(newEvent(
                runId,
                RunEventType.RUN_STARTED,
                "VERIFY run started by merge gate.",
                null
            ));
            return run;
        } catch (RuntimeException ex) {
            safeReleaseWorkspace(runId, worktreePath);
            throw ex;
        }
    }

    private void ensureNoDuplicateVerifyRun(String taskId, String mergeCandidateCommit) {
        Optional<TaskRun> existingOptional = taskRunRepository.findLatestVerifyRunByTaskAndBaseCommit(taskId, mergeCandidateCommit);
        if (existingOptional.isEmpty()) {
            return;
        }
        TaskRun existing = existingOptional.get();
        switch (existing.status()) {
            case RUNNING, WAITING_FOREMAN -> throw new PreconditionFailedException(
                "VERIFY run already active for task "
                    + taskId
                    + ", mergeCandidateCommit="
                    + mergeCandidateCommit
                    + ", runId="
                    + existing.runId()
            );
            case SUCCEEDED -> throw new PreconditionFailedException(
                "Merge candidate already verified successfully for task "
                    + taskId
                    + ", mergeCandidateCommit="
                    + mergeCandidateCommit
                    + ", runId="
                    + existing.runId()
            );
            case FAILED -> {
                if (isVerifyRetryAllowed(taskId, mergeCandidateCommit)) {
                    return;
                }
                throw new PreconditionFailedException(
                    buildVerifyRetryRejectedMessage(taskId, mergeCandidateCommit)
                );
            }
            case CANCELLED -> {
                // Explicit cancellation allows a retried VERIFY run on the same candidate commit.
            }
            default -> throw new PreconditionFailedException(
                "Unexpected existing VERIFY run status for task "
                    + taskId
                    + ", mergeCandidateCommit="
                    + mergeCandidateCommit
                    + ", runId="
                    + existing.runId()
                    + ", status="
                    + existing.status()
            );
        }
    }

    @Override
    @Transactional
    public TaskRun failRun(String runId, String reason) {
        String normalizedRunId = requireNotBlank(runId, "runId");
        String normalizedReason = requireNotBlank(reason, "reason");
        return finishRun(
            normalizedRunId,
            new RunFinishedPayload(
                RunStatus.FAILED.name(),
                normalizedReason,
                null,
                null
            )
        );
    }

    @Override
    @Transactional
    public TaskRun failWaitingRunForUserResponse(String runId, String reason) {
        String normalizedRunId = requireNotBlank(runId, "runId");
        String normalizedReason = requireNotBlank(reason, "reason");
        TaskRun current = taskRunRepository.findById(normalizedRunId)
            .orElseThrow(() -> new NoSuchElementException("Run not found: " + normalizedRunId));
        if (isTerminal(current.status())) {
            return current;
        }
        if (current.status() != RunStatus.WAITING_FOREMAN) {
            return current;
        }
        return finishRun(
            normalizedRunId,
            new RunFinishedPayload(
                RunStatus.FAILED.name(),
                normalizedReason,
                null,
                null
            )
        );
    }

    @Override
    @Transactional
    public int recoverExpiredRuns(int limit) {
        int cappedLimit = limit <= 0 ? 100 : Math.min(limit, 500);
        Instant now = Instant.now();
        List<TaskRun> candidates = taskRunRepository.findExpiredActiveRuns(now, cappedLimit);
        int recovered = 0;
        for (TaskRun candidate : candidates) {
            TaskRun latest = taskRunRepository.findById(candidate.runId()).orElse(null);
            if (latest == null) {
                continue;
            }
            if (isTerminal(latest.status()) || !latest.leaseUntil().isBefore(now)) {
                continue;
            }
            if (latest.status() == RunStatus.WAITING_FOREMAN) {
                continue;
            }
            boolean marked = taskRunRepository.markFailedIfLeaseExpired(latest.runId(), now, now);
            if (!marked) {
                continue;
            }
            RunFinishedPayload payload = new RunFinishedPayload(
                RunStatus.FAILED.name(),
                "Lease expired; run reclaimed by watchdog.",
                null,
                null
            );
            taskRunEventRepository.save(newEvent(
                latest.runId(),
                RunEventType.RUN_FINISHED,
                "Run marked FAILED due to lease expiration.",
                toRunFinishedDataJson(payload, RunStatus.FAILED)
            ));
            safeReleaseWorkspace(latest.runId(), latest.worktreePath());
            if (shouldReleaseAssignmentOnLeaseRecovery(latest)) {
                try {
                    taskAllocationPort.releaseTaskAssignment(latest.taskId());
                } catch (RuntimeException ex) {
                    log.warn(
                        "Failed to release assignment for expired run, runId={}, taskId={}",
                        latest.runId(),
                        latest.taskId(),
                        ex
                    );
                }
            }
            domainEventPublisher.publish(new RunFinishedEvent(
                latest.runId(),
                latest.taskId(),
                latest.runKind(),
                latest.baseCommit(),
                payload
            ));
            recovered++;
        }
        return recovered;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<TaskRun> findRunById(String runId) {
        String normalizedRunId = requireNotBlank(runId, "runId");
        return taskRunRepository.findById(normalizedRunId);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<TaskRun> findLatestRunByTaskAndKind(String taskId, RunKind runKind) {
        String normalizedTaskId = requireNotBlank(taskId, "taskId");
        if (runKind == null) {
            throw new IllegalArgumentException("runKind must not be null");
        }
        return taskRunRepository.findLatestRunByTaskAndKind(normalizedTaskId, runKind);
    }

    @Override
    @Transactional(readOnly = true)
    public boolean hasActiveRunByTaskAndKind(String taskId, RunKind runKind) {
        String normalizedTaskId = requireNotBlank(taskId, "taskId");
        if (runKind == null) {
            throw new IllegalArgumentException("runKind must not be null");
        }
        return taskRunRepository.existsActiveRunByTaskAndKind(normalizedTaskId, runKind);
    }

    @Override
    @Transactional(readOnly = true)
    public boolean hasActiveRunsBySession(String sessionId) {
        String normalizedSessionId = requireNotBlank(sessionId, "sessionId");
        return taskRunRepository.existsActiveRunBySessionId(normalizedSessionId);
    }

    @Override
    @Transactional(readOnly = true)
    public int countVerifyRunsByTaskAndBaseCommit(String taskId, String baseCommit) {
        String normalizedTaskId = requireNotBlank(taskId, "taskId");
        String normalizedBaseCommit = requireNotBlank(baseCommit, "baseCommit");
        return taskRunRepository.countVerifyRunsByTaskAndBaseCommit(normalizedTaskId, normalizedBaseCommit);
    }

    private TaskPackage toTaskPackage(
        TaskAllocationPort.ClaimedTask claimedTask,
        TaskRun run,
        ContextSnapshotReadPort.ReadySnapshot readySnapshot
    ) {
        List<String> requiredToolpacks = parseRequiredToolpacks(claimedTask.requiredToolpacksJson());
        boolean hasPendingDependentTestTask = taskAllocationPort.hasNonDoneDependentTaskByTemplate(
            run.taskId(),
            "tmpl.test.v0"
        );
        List<String> writeScope = resolveWriteScope(
            run.runKind(),
            claimedTask.taskTemplateId(),
            claimedTask.moduleId(),
            requiredToolpacks,
            hasPendingDependentTestTask
        );
        List<String> verifyCommands = run.runKind() == RunKind.VERIFY
            ? resolveVerifyCommands(readySnapshot.taskSkillRef(), requiredToolpacks)
            : List.of();
        List<String> expectedOutputs = new ArrayList<>();
        expectedOutputs.add("work_report");
        expectedOutputs.add("artifact_refs");
        if (run.runKind() == RunKind.IMPL) {
            expectedOutputs.add("delivery_commit");
        }
        TaskContext taskContext = resolveTaskContext(run, readySnapshot);
        return new TaskPackage(
            run.runId(),
            run.taskId(),
            claimedTask.taskTitle(),
            claimedTask.moduleId(),
            run.contextSnapshotId(),
            run.runKind(),
            claimedTask.taskTemplateId(),
            requiredToolpacks,
            readySnapshot.taskSkillRef(),
            taskContext,
            List.of("./"),
            writeScope,
            verifyCommands,
            List.of(
                "Encountering missing facts requires NEED_CLARIFICATION.",
                "Encountering architecture tradeoff requires NEED_DECISION."
            ),
            expectedOutputs,
            new GitAlloc(
                run.baseCommit(),
                run.branchName(),
                run.worktreePath()
            )
        );
    }

    private TaskContext resolveTaskContext(TaskRun run, ContextSnapshotReadPort.ReadySnapshot readySnapshot) {
        TaskContext fallback = new TaskContext(
            "task:" + run.taskId(),
            List.of(),
            List.of(),
            "git:" + run.baseCommit()
        );
        if (readySnapshot == null || readySnapshot.taskContextRef() == null || readySnapshot.taskContextRef().isBlank()) {
            return fallback;
        }
        Optional<Path> taskContextPathOptional = resolveArtifactPath(readySnapshot.taskContextRef());
        if (taskContextPathOptional.isEmpty()) {
            return fallback;
        }
        Path taskContextPath = taskContextPathOptional.get();
        try {
            JsonNode root = objectMapper.readTree(Files.readString(taskContextPath, StandardCharsets.UTF_8));
            if (root == null || !root.isObject()) {
                return fallback;
            }
            String requirementRef = readTextOrDefault(root, "requirement_ref", "requirementRef", fallback.requirementRef());
            LinkedHashSet<String> architectureRefs = new LinkedHashSet<>(readTextArray(root, "architecture_refs", "architectureRefs"));
            architectureRefs.addAll(readTextArray(root, "decision_refs", "decisionRefs"));
            List<String> priorRunRefs = readTextArray(root, "prior_run_refs", "priorRunRefs");
            String repoBaselineRef = readTextOrDefault(root, "repo_baseline_ref", "repoBaselineRef", fallback.repoBaselineRef());
            return new TaskContext(
                requirementRef,
                List.copyOf(architectureRefs),
                priorRunRefs,
                repoBaselineRef
            );
        } catch (Exception ex) {
            log.warn(
                "Failed to parse task context artifact, fallback to minimal context. runId={}, taskId={}, taskContextRef={}",
                run.runId(),
                run.taskId(),
                readySnapshot.taskContextRef(),
                ex
            );
            return fallback;
        }
    }

    private String resolveBaseCommitFromContextRef(String taskContextRef) {
        Optional<Path> taskContextPathOptional = resolveArtifactPath(taskContextRef);
        if (taskContextPathOptional.isEmpty()) {
            return null;
        }
        Path taskContextPath = taskContextPathOptional.get();
        try {
            JsonNode root = objectMapper.readTree(Files.readString(taskContextPath, StandardCharsets.UTF_8));
            if (root == null || !root.isObject()) {
                return null;
            }
            String repoBaselineRef = readTextOrDefault(root, "repo_baseline_ref", "repoBaselineRef", "");
            if (repoBaselineRef.isBlank()) {
                return null;
            }
            if (repoBaselineRef.startsWith("git:")) {
                return repoBaselineRef.substring("git:".length()).trim();
            }
            return repoBaselineRef.trim();
        } catch (Exception ex) {
            log.warn("Failed to resolve repo baseline from task context, taskContextRef={}", taskContextRef, ex);
            return null;
        }
    }

    private static List<String> readTextArray(JsonNode node) {
        if (node == null || !node.isArray()) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        for (JsonNode element : node) {
            if (!element.isTextual()) {
                continue;
            }
            String text = element.asText().trim();
            if (!text.isEmpty()) {
                values.add(text);
            }
        }
        return List.copyOf(values);
    }

    private static List<String> readTextArray(JsonNode root, String snakeCaseField, String camelCaseField) {
        JsonNode node = readField(root, snakeCaseField, camelCaseField);
        return readTextArray(node);
    }

    private static String readTextOrDefault(JsonNode node, String fallback) {
        if (node == null || !node.isTextual()) {
            return fallback;
        }
        String value = node.asText().trim();
        return value.isEmpty() ? fallback : value;
    }

    private static String readTextOrDefault(
        JsonNode root,
        String snakeCaseField,
        String camelCaseField,
        String fallback
    ) {
        JsonNode node = readField(root, snakeCaseField, camelCaseField);
        return readTextOrDefault(node, fallback);
    }

    private static JsonNode readField(JsonNode root, String snakeCaseField, String camelCaseField) {
        if (root == null || !root.isObject()) {
            return null;
        }
        JsonNode snakeNode = root.path(snakeCaseField);
        if (!snakeNode.isMissingNode()) {
            return snakeNode;
        }
        JsonNode camelNode = root.path(camelCaseField);
        if (!camelNode.isMissingNode()) {
            return camelNode;
        }
        return null;
    }

    private List<String> resolveWriteScope(
        RunKind runKind,
        String taskTemplateId,
        String moduleId,
        List<String> requiredToolpacks,
        boolean hasPendingDependentTestTask
    ) {
        if (runKind == RunKind.VERIFY) {
            return List.of();
        }
        String normalizedTemplate = normalizeTemplate(taskTemplateId);
        if ("tmpl.init.v0".equals(normalizedTemplate)) {
            return List.of("./");
        }

        LinkedHashSet<String> scopes = new LinkedHashSet<>();
        String moduleScope = toModuleScope(moduleId);
        if (!moduleScope.isBlank()) {
            scopes.add("modules/" + moduleScope + "/");
            scopes.add("services/" + moduleScope + "/");
            scopes.add(moduleScope + "/");
        }

        boolean hasMaven = hasToolpack(requiredToolpacks, "TP-MAVEN-3");
        boolean hasJava = hasToolpack(requiredToolpacks, "TP-JAVA-21") || hasMaven;
        boolean hasPython = requiredToolpacks.stream().anyMatch(id -> id.toUpperCase(Locale.ROOT).startsWith("TP-PYTHON"));
        if ("tmpl.test.v0".equals(normalizedTemplate)) {
            if (hasJava) {
                scopes.add("src/test/java/");
                scopes.add("src/test/resources/");
                scopes.add("pom.xml");
            } else if (hasPython) {
                scopes.add("tests/");
                scopes.add("pyproject.toml");
                scopes.add("requirements.txt");
            } else {
                scopes.add("tests/");
            }
            return List.copyOf(scopes);
        }

        if (hasJava) {
            scopes.add("src/main/java/");
            scopes.add("src/main/resources/");
            if (!hasPendingDependentTestTask) {
                scopes.add("src/test/java/");
                scopes.add("src/test/resources/");
            }
            scopes.add("pom.xml");
            if (hasMaven) {
                scopes.add(".mvn/");
                scopes.add("mvnw");
                scopes.add("mvnw.cmd");
            }
        } else if (hasPython) {
            scopes.add("src/");
            scopes.add("tests/");
            scopes.add("pyproject.toml");
            scopes.add("requirements.txt");
        } else {
            scopes.add("src/");
            scopes.add("tests/");
            scopes.add("README.md");
        }
        return List.copyOf(scopes);
    }

    private List<String> resolveVerifyCommands(String taskSkillRef, List<String> requiredToolpacks) {
        LinkedHashSet<String> commands = new LinkedHashSet<>(readRecommendedCommands(taskSkillRef));
        if (commands.isEmpty()) {
            commands.addAll(fallbackVerifyCommands(requiredToolpacks));
        }
        return List.copyOf(commands);
    }

    private List<String> readRecommendedCommands(String taskSkillRef) {
        Optional<Path> taskSkillPathOptional = resolveTaskSkillPath(taskSkillRef);
        if (taskSkillPathOptional.isEmpty()) {
            return List.of();
        }
        Path taskSkillPath = taskSkillPathOptional.get();
        try {
            List<String> lines = Files.readAllLines(taskSkillPath, StandardCharsets.UTF_8);
            boolean inRecommendedCommands = false;
            LinkedHashSet<String> commands = new LinkedHashSet<>();
            for (String line : lines) {
                if (line == null) {
                    continue;
                }
                String trimmed = line.trim();
                if (trimmed.startsWith("## ")) {
                    inRecommendedCommands = "## Recommended Commands".equalsIgnoreCase(trimmed);
                    continue;
                }
                if (!inRecommendedCommands || !trimmed.startsWith("- ")) {
                    continue;
                }
                String candidate = trimmed.substring(2).trim();
                if (!candidate.isEmpty() && looksLikeCommand(candidate)) {
                    commands.add(candidate);
                }
            }
            return List.copyOf(commands);
        } catch (Exception ex) {
            log.warn("Failed to parse task skill markdown for verify commands, taskSkillRef={}", taskSkillRef, ex);
            return List.of();
        }
    }

    private Optional<Path> resolveTaskSkillPath(String taskSkillRef) {
        return resolveArtifactPath(taskSkillRef);
    }

    private Optional<Path> resolveArtifactPath(String artifactRef) {
        if (artifactRef == null || artifactRef.isBlank() || !artifactRef.startsWith("file:")) {
            return Optional.empty();
        }
        Path parsedPath = Path.of(artifactRef.substring("file:".length()));
        Path normalized = parsedPath.isAbsolute()
            ? parsedPath.toAbsolutePath().normalize()
            : repoRoot.resolve(parsedPath).toAbsolutePath().normalize();
        if (!isPathWithinAllowedArtifactRoots(normalized) || !Files.exists(normalized) || !Files.isRegularFile(normalized)) {
            return Optional.empty();
        }
        return Optional.of(normalized);
    }

    private boolean isPathWithinAllowedArtifactRoots(Path path) {
        for (Path root : artifactReadRoots) {
            if (path.startsWith(root)) {
                return true;
            }
        }
        return false;
    }

    private List<String> fallbackVerifyCommands(List<String> requiredToolpacks) {
        if (hasToolpack(requiredToolpacks, "TP-MAVEN-3")) {
            return List.of("mvn -q test");
        }
        boolean hasPython = requiredToolpacks.stream().anyMatch(id -> id.toUpperCase(Locale.ROOT).startsWith("TP-PYTHON"));
        if (hasPython) {
            return List.of("python -m pytest -q");
        }
        if (hasToolpack(requiredToolpacks, "TP-JAVA-21")) {
            return List.of("./gradlew test");
        }
        return List.of("git status --porcelain");
    }

    private static boolean looksLikeCommand(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        String token = value.trim().split("\\s+")[0].toLowerCase(Locale.ROOT);
        return VERIFY_COMMAND_PREFIX_ALLOWLIST.contains(token);
    }

    private static String normalizeTemplate(String taskTemplateId) {
        if (taskTemplateId == null) {
            return "";
        }
        return taskTemplateId.trim().toLowerCase(Locale.ROOT);
    }

    private static String toModuleScope(String moduleId) {
        if (moduleId == null || moduleId.isBlank()) {
            return "";
        }
        String normalized = moduleId.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9._/-]+", "-");
        while (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    private static boolean hasToolpack(List<String> toolpacks, String toolpackId) {
        if (toolpacks == null || toolpacks.isEmpty() || toolpackId == null) {
            return false;
        }
        for (String toolpack : toolpacks) {
            if (toolpackId.equalsIgnoreCase(toolpack)) {
                return true;
            }
        }
        return false;
    }

    private String toRunFinishedDataJson(RunFinishedPayload payload, RunStatus resultStatus) {
        try {
            var node = objectMapper.createObjectNode();
            node.put("result_status", resultStatus.name());
            node.put("work_report", payload.workReport());
            if (payload.deliveryCommit() != null) {
                node.put("delivery_commit", payload.deliveryCommit());
            } else {
                node.putNull("delivery_commit");
            }
            if (payload.artifactRefsJson() != null) {
                node.put("artifact_refs_json", payload.artifactRefsJson());
            } else {
                node.putNull("artifact_refs_json");
            }
            return objectMapper.writeValueAsString(node);
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to build RUN_FINISHED data json", ex);
        }
    }

    private List<String> parseRequiredToolpacks(String requiredToolpacksJson) {
        try {
            JsonNode root = objectMapper.readTree(requiredToolpacksJson);
            if (root == null || !root.isArray()) {
                throw new IllegalArgumentException("required_toolpacks_json must be json array");
            }
            List<String> values = new ArrayList<>();
            for (JsonNode node : root) {
                if (!node.isTextual()) {
                    throw new IllegalArgumentException("required_toolpacks_json element must be string");
                }
                String value = node.asText().trim();
                if (!value.isEmpty()) {
                    values.add(value);
                }
            }
            return values;
        } catch (RuntimeException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalArgumentException("required_toolpacks_json must be valid json array", ex);
        }
    }

    private String toJsonArray(List<String> values) {
        try {
            ArrayNode arrayNode = objectMapper.createArrayNode();
            if (values != null) {
                for (String value : values) {
                    if (value != null && !value.isBlank()) {
                        arrayNode.add(value.trim());
                    }
                }
            }
            return objectMapper.writeValueAsString(arrayNode);
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to serialize json array", ex);
        }
    }

    private static RunKind resolveRunKind(String taskTemplateId) {
        String normalized = requireNotBlank(taskTemplateId, "taskTemplateId").toLowerCase(Locale.ROOT);
        if ("tmpl.verify.v0".equals(normalized)) {
            return RunKind.VERIFY;
        }
        return RunKind.IMPL;
    }

    private static boolean isInitTemplate(String taskTemplateId) {
        return "tmpl.init.v0".equalsIgnoreCase(requireNotBlank(taskTemplateId, "taskTemplateId"));
    }

    private static RunStatus parseResultStatus(String value) {
        String normalized = requireNotBlank(value, "resultStatus").toUpperCase(Locale.ROOT);
        if ("SUCCEEDED".equals(normalized)) {
            return RunStatus.SUCCEEDED;
        }
        if ("FAILED".equals(normalized)) {
            return RunStatus.FAILED;
        }
        if ("CANCELLED".equals(normalized)) {
            return RunStatus.CANCELLED;
        }
        throw new IllegalArgumentException("Unsupported resultStatus: " + value);
    }

    private static boolean isTerminal(RunStatus status) {
        return status == RunStatus.SUCCEEDED || status == RunStatus.FAILED || status == RunStatus.CANCELLED;
    }

    private static boolean shouldReleaseAssignmentOnFinish(TaskRun run, RunStatus status) {
        return run != null
            && run.runKind() == RunKind.IMPL
            && (status == RunStatus.FAILED || status == RunStatus.CANCELLED);
    }

    private static boolean shouldReleaseAssignmentOnLeaseRecovery(TaskRun run) {
        return run != null
            && run.runKind() == RunKind.IMPL
            && run.status() == RunStatus.RUNNING;
    }

    private static boolean shouldUpdateTaskBranch(TaskRun run, RunStatus resultStatus) {
        return run != null
            && run.runKind() == RunKind.IMPL
            && resultStatus == RunStatus.SUCCEEDED;
    }

    private static TaskRunEvent newEvent(String runId, RunEventType eventType, String body, String dataJson) {
        return new TaskRunEvent(
            generateEventId(),
            runId,
            eventType,
            body,
            dataJson,
            Instant.now()
        );
    }

    private void safeReleaseWorkspace(String runId, String worktreePath) {
        try {
            workspacePort.releaseWorkspace(runId, worktreePath);
        } catch (RuntimeException ignored) {
            // best effort; workspace cleanup failure is handled by workspace status in later phases.
        }
    }

    private static String requireNotBlank(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value.trim();
    }

    private static String generateRunId() {
        return "RUN-" + UUID.randomUUID().toString().replace("-", "");
    }

    private static String generateEventId() {
        return "REV-" + UUID.randomUUID().toString().replace("-", "");
    }

    private String buildVerifyRetryRejectedMessage(String taskId, String mergeCandidateCommit) {
        int attempts = taskRunRepository.countVerifyRunsByTaskAndBaseCommit(taskId, mergeCandidateCommit);
        return "Merge candidate verify attempt limit reached for task "
            + taskId
            + ", mergeCandidateCommit="
            + mergeCandidateCommit
            + ". attempts="
            + attempts
            + ", max="
            + verifyMaxAttemptsPerCandidate
            + ". A new delivery commit is required before re-verify.";
    }

    private boolean isVerifyRetryAllowed(String taskId, String mergeCandidateCommit) {
        int attempts = taskRunRepository.countVerifyRunsByTaskAndBaseCommit(taskId, mergeCandidateCommit);
        return attempts < verifyMaxAttemptsPerCandidate;
    }

    private String resolveSessionIdForTask(String taskId) {
        String normalizedTaskId = requireNotBlank(taskId, "taskId");
        return taskAllocationPort.findSessionIdByTaskId(normalizedTaskId)
            .orElseThrow(() -> new PreconditionFailedException(
                "Session not found for task " + normalizedTaskId + "."
            ));
    }

    private void ensureSessionActive(String sessionId, String taskId) {
        String normalizedSessionId = requireNotBlank(sessionId, "sessionId");
        String normalizedTaskId = requireNotBlank(taskId, "taskId");
        if (!taskAllocationPort.isSessionActive(normalizedSessionId)) {
            throw new PreconditionFailedException(
                "Session is not ACTIVE for task " + normalizedTaskId + ": " + normalizedSessionId
            );
        }
    }

    private static String buildWorktreePath(String sessionId, String runId) {
        return "worktrees/" + requireNotBlank(sessionId, "sessionId") + "/" + runId;
    }
}
