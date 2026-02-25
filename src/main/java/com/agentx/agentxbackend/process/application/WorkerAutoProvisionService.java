package com.agentx.agentxbackend.process.application;

import com.agentx.agentxbackend.planning.application.port.in.WaitingTaskQueryUseCase;
import com.agentx.agentxbackend.process.application.port.out.RuntimeEnvironmentPort;
import com.agentx.agentxbackend.planning.domain.model.WorkTask;
import com.agentx.agentxbackend.ticket.application.port.in.TicketCommandUseCase;
import com.agentx.agentxbackend.ticket.application.port.in.TicketQueryUseCase;
import com.agentx.agentxbackend.ticket.domain.model.Ticket;
import com.agentx.agentxbackend.ticket.domain.model.TicketStatus;
import com.agentx.agentxbackend.ticket.domain.model.TicketType;
import com.agentx.agentxbackend.workforce.application.port.in.WorkerCapabilityUseCase;
import com.agentx.agentxbackend.workforce.domain.model.Toolpack;
import com.agentx.agentxbackend.workforce.domain.model.Worker;
import com.agentx.agentxbackend.workforce.domain.model.WorkerStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

@Service
public class WorkerAutoProvisionService {

    private static final Logger log = LoggerFactory.getLogger(WorkerAutoProvisionService.class);
    private static final String CLARIFICATION_KIND = "missing_toolpack_request";
    private static final String CLARIFICATION_SOURCE = "worker_auto_provisioner";

    private final WaitingTaskQueryUseCase waitingTaskQueryUseCase;
    private final TicketCommandUseCase ticketCommandUseCase;
    private final TicketQueryUseCase ticketQueryUseCase;
    private final WorkerCapabilityUseCase workerCapabilityUseCase;
    private final RuntimeEnvironmentPort runtimeEnvironmentPort;
    private final ObjectMapper objectMapper;
    private final int waitThresholdSeconds;
    private final int maxWorkersTotal;
    private final int maxWorkersProvisioning;
    private final int maxCreatesPerCycle;
    private final String clarificationAgentId;
    private final int clarificationLeaseSeconds;

    public WorkerAutoProvisionService(
        WaitingTaskQueryUseCase waitingTaskQueryUseCase,
        TicketCommandUseCase ticketCommandUseCase,
        TicketQueryUseCase ticketQueryUseCase,
        WorkerCapabilityUseCase workerCapabilityUseCase,
        RuntimeEnvironmentPort runtimeEnvironmentPort,
        ObjectMapper objectMapper,
        @Value("${agentx.workforce.auto-provisioner.wait-threshold-seconds:5}") int waitThresholdSeconds,
        @Value("${agentx.workforce.auto-provisioner.max-workers-total:32}") int maxWorkersTotal,
        @Value("${agentx.workforce.auto-provisioner.max-workers-provisioning:8}") int maxWorkersProvisioning,
        @Value("${agentx.workforce.auto-provisioner.max-creates-per-cycle:2}") int maxCreatesPerCycle,
        @Value("${agentx.workforce.auto-provisioner.request-ticket.agent-id:architect-agent-auto}") String clarificationAgentId,
        @Value("${agentx.workforce.auto-provisioner.request-ticket.lease-seconds:300}") int clarificationLeaseSeconds
    ) {
        this.waitingTaskQueryUseCase = waitingTaskQueryUseCase;
        this.ticketCommandUseCase = ticketCommandUseCase;
        this.ticketQueryUseCase = ticketQueryUseCase;
        this.workerCapabilityUseCase = workerCapabilityUseCase;
        this.runtimeEnvironmentPort = runtimeEnvironmentPort;
        this.objectMapper = objectMapper;
        this.waitThresholdSeconds = Math.max(0, waitThresholdSeconds);
        this.maxWorkersTotal = Math.max(1, maxWorkersTotal);
        this.maxWorkersProvisioning = Math.max(1, maxWorkersProvisioning);
        this.maxCreatesPerCycle = Math.max(1, maxCreatesPerCycle);
        this.clarificationAgentId = (clarificationAgentId == null || clarificationAgentId.isBlank())
            ? "architect-agent-auto"
            : clarificationAgentId.trim();
        this.clarificationLeaseSeconds = Math.max(30, clarificationLeaseSeconds);
    }

    public AutoProvisionResult provisionForWaitingTasks(int maxTasksToScan) {
        int taskLimit = maxTasksToScan <= 0 ? 100 : Math.min(maxTasksToScan, 500);
        List<WorkTask> waitingTasks = waitingTaskQueryUseCase.listWaitingWorkerTasks(taskLimit);
        if (waitingTasks.isEmpty()) {
            return new AutoProvisionResult(0, 0, 0, 0, 0, 0, List.of(), List.of());
        }

        int totalWorkers = workerCapabilityUseCase.countWorkers();
        int provisioningWorkers = workerCapabilityUseCase.countWorkersByStatus(WorkerStatus.PROVISIONING);
        int capacityByTotal = Math.max(0, maxWorkersTotal - totalWorkers);
        int capacityByProvisioning = Math.max(0, maxWorkersProvisioning - provisioningWorkers);
        int remainingBudget = Math.min(maxCreatesPerCycle, Math.min(capacityByTotal, capacityByProvisioning));

        Set<String> knownToolpackIds = workerCapabilityUseCase.listToolpacks()
            .stream()
            .map(Toolpack::toolpackId)
            .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));

        Instant now = Instant.now();
        int skippedTooFresh = 0;
        int skippedMissingToolpacks = 0;
        int skippedByCapacity = 0;
        List<String> createdWorkerIds = new ArrayList<>();
        Set<String> dedupRaisedKeys = new LinkedHashSet<>();
        List<String> createdClarificationTicketIds = new ArrayList<>();

        for (WorkTask task : waitingTasks) {
            if (isTooFresh(task, now)) {
                skippedTooFresh++;
                continue;
            }
            List<String> requiredToolpacks = parseRequiredToolpacks(task.requiredToolpacksJson());
            if (workerCapabilityUseCase.hasEligibleWorker(requiredToolpacks)) {
                continue;
            }
            List<String> missingToolpacks = findMissingToolpacks(requiredToolpacks, knownToolpackIds);
            if (!missingToolpacks.isEmpty()) {
                skippedMissingToolpacks++;
                raiseMissingToolpackClarificationIfNeeded(
                    task,
                    requiredToolpacks,
                    missingToolpacks,
                    dedupRaisedKeys,
                    createdClarificationTicketIds
                );
                continue;
            }
            if (remainingBudget <= 0) {
                skippedByCapacity++;
                continue;
            }
            String sessionId = waitingTaskQueryUseCase.findSessionIdByModuleId(task.moduleId()).orElse("");
            if (sessionId.isBlank()) {
                log.warn("Skip worker auto-provision due to missing session binding, taskId={}, moduleId={}", task.taskId(), task.moduleId());
                continue;
            }
            String workerId = "WRK-AUTO-" + UUID.randomUUID().toString().replace("-", "");
            // Consume cycle budget on each create attempt to prevent retry storms.
            remainingBudget--;
            try {
                runtimeEnvironmentPort.ensureReady(sessionId, workerId, requiredToolpacks);
                Worker created = workerCapabilityUseCase.registerWorker(workerId);
                if (!requiredToolpacks.isEmpty()) {
                    workerCapabilityUseCase.bindToolpacks(created.workerId(), requiredToolpacks);
                }
                workerCapabilityUseCase.updateWorkerStatus(created.workerId(), WorkerStatus.READY);
                createdWorkerIds.add(created.workerId());
            } catch (RuntimeException ex) {
                log.warn("Worker auto-provision failed, workerId={}, taskId={}, cause={}", workerId, task.taskId(), ex.getMessage());
                safeDisable(workerId);
            }
        }

        return new AutoProvisionResult(
            waitingTasks.size(),
            createdWorkerIds.size(),
            skippedTooFresh,
            skippedMissingToolpacks,
            skippedByCapacity,
            createdClarificationTicketIds.size(),
            List.copyOf(createdWorkerIds),
            List.copyOf(createdClarificationTicketIds)
        );
    }

    private boolean isTooFresh(WorkTask task, Instant now) {
        Duration waited = Duration.between(task.updatedAt(), now);
        return waited.getSeconds() < waitThresholdSeconds;
    }

    private List<String> parseRequiredToolpacks(String requiredToolpacksJson) {
        if (requiredToolpacksJson == null || requiredToolpacksJson.isBlank()) {
            return List.of();
        }
        try {
            JsonNode root = objectMapper.readTree(requiredToolpacksJson);
            if (root == null || !root.isArray()) {
                throw new IllegalArgumentException("required_toolpacks_json must be a JSON array");
            }
            LinkedHashSet<String> normalized = new LinkedHashSet<>();
            for (JsonNode node : root) {
                if (!node.isTextual()) {
                    throw new IllegalArgumentException("required_toolpacks_json element must be string");
                }
                String value = node.asText().trim();
                if (!value.isEmpty()) {
                    normalized.add(value);
                }
            }
            return new ArrayList<>(normalized);
        } catch (RuntimeException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalArgumentException("required_toolpacks_json must be valid JSON", ex);
        }
    }

    private void safeDisable(String workerId) {
        try {
            workerCapabilityUseCase.updateWorkerStatus(workerId, WorkerStatus.DISABLED);
        } catch (RuntimeException ignored) {
            // best effort fallback for failed provisioning.
        }
    }

    private void raiseMissingToolpackClarificationIfNeeded(
        WorkTask task,
        List<String> requiredToolpacks,
        List<String> missingToolpacks,
        Set<String> dedupRaisedKeys,
        List<String> createdClarificationTicketIds
    ) {
        if (task == null || missingToolpacks.isEmpty()) {
            return;
        }
        String sessionId = waitingTaskQueryUseCase.findSessionIdByModuleId(task.moduleId()).orElse("");
        if (sessionId.isBlank()) {
            return;
        }
        String dedupKey = buildClarificationDedupKey(sessionId, missingToolpacks);
        if (!dedupRaisedKeys.add(dedupKey)) {
            return;
        }
        if (hasActiveClarificationTicket(sessionId, dedupKey)) {
            return;
        }

        String question = buildMissingToolpackQuestion(task.taskId(), missingToolpacks);
        String payloadJson = buildClarificationPayloadJson(task, requiredToolpacks, missingToolpacks, dedupKey, question);
        try {
            Ticket created = ticketCommandUseCase.createTicket(
                sessionId,
                TicketType.CLARIFICATION,
                "Missing toolpacks block worker provisioning",
                "architect_agent",
                "architect_agent",
                null,
                null,
                payloadJson
            );
            Ticket claimed = ticketCommandUseCase.claimTicket(
                created.ticketId(),
                clarificationAgentId,
                clarificationLeaseSeconds
            );
            ticketCommandUseCase.appendEvent(
                claimed.ticketId(),
                "architect_agent",
                "DECISION_REQUESTED",
                "CLARIFICATION request: " + question,
                buildClarificationDecisionRequestedDataJson(task, missingToolpacks, dedupKey, question)
            );
            createdClarificationTicketIds.add(claimed.ticketId());
        } catch (RuntimeException ignored) {
            // Best effort only: failure to raise clarification must not block auto-provision loop.
        }
    }

    private boolean hasActiveClarificationTicket(String sessionId, String dedupKey) {
        List<Ticket> tickets = ticketQueryUseCase.listBySession(sessionId, null, "architect_agent", "CLARIFICATION");
        for (Ticket ticket : tickets) {
            if (ticket == null || ticket.status() == null) {
                continue;
            }
            if (ticket.status() == TicketStatus.DONE || ticket.status() == TicketStatus.BLOCKED) {
                continue;
            }
            if (!dedupKey.equals(extractDedupKey(ticket.payloadJson()))) {
                continue;
            }
            return true;
        }
        return false;
    }

    private String extractDedupKey(String payloadJson) {
        if (payloadJson == null || payloadJson.isBlank()) {
            return "";
        }
        try {
            JsonNode root = objectMapper.readTree(payloadJson);
            if (root == null || !root.isObject()) {
                return "";
            }
            String kind = root.path("kind").asText("");
            String source = root.path("source").asText("");
            if (!CLARIFICATION_KIND.equals(kind) || !CLARIFICATION_SOURCE.equals(source)) {
                return "";
            }
            return root.path("dedup_key").asText("");
        } catch (Exception ignored) {
            return "";
        }
    }

    private String buildClarificationPayloadJson(
        WorkTask task,
        List<String> requiredToolpacks,
        List<String> missingToolpacks,
        String dedupKey,
        String question
    ) {
        try {
            var root = objectMapper.createObjectNode();
            root.put("kind", CLARIFICATION_KIND);
            root.put("source", CLARIFICATION_SOURCE);
            root.put("dedup_key", dedupKey);
            root.put("task_id", task.taskId());
            root.put("module_id", task.moduleId());
            root.put("question", question);
            var required = root.putArray("required_toolpacks");
            for (String id : requiredToolpacks) {
                required.add(id);
            }
            var missing = root.putArray("missing_toolpacks");
            for (String id : missingToolpacks) {
                missing.add(id);
            }
            return objectMapper.writeValueAsString(root);
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to build missing toolpack clarification payload", ex);
        }
    }

    private String buildClarificationDecisionRequestedDataJson(
        WorkTask task,
        List<String> missingToolpacks,
        String dedupKey,
        String question
    ) {
        try {
            var root = objectMapper.createObjectNode();
            root.put("request_kind", "CLARIFICATION");
            root.put("question", question);
            root.put("source", CLARIFICATION_SOURCE);
            root.put("dedup_key", dedupKey);
            root.put("task_id", task.taskId());
            root.put("module_id", task.moduleId());
            var missing = root.putArray("missing_toolpacks");
            for (String id : missingToolpacks) {
                missing.add(id);
            }
            return objectMapper.writeValueAsString(root);
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to build missing toolpack clarification data", ex);
        }
    }

    private static String buildMissingToolpackQuestion(String taskId, List<String> missingToolpacks) {
        return "Task " + taskId + " requires missing toolpacks: " + String.join(", ", missingToolpacks)
            + ". Please clarify whether to add these toolpacks into the pool or adjust required_toolpacks.";
    }

    private static List<String> findMissingToolpacks(List<String> requiredToolpacks, Set<String> knownToolpackIds) {
        if (requiredToolpacks == null || requiredToolpacks.isEmpty()) {
            return List.of();
        }
        List<String> missing = new ArrayList<>();
        for (String id : requiredToolpacks) {
            if (id == null || id.isBlank()) {
                continue;
            }
            if (!knownToolpackIds.contains(id)) {
                missing.add(id);
            }
        }
        if (missing.isEmpty()) {
            return List.of();
        }
        Collections.sort(missing);
        return List.copyOf(new LinkedHashSet<>(missing));
    }

    private static String buildClarificationDedupKey(String sessionId, List<String> missingToolpacks) {
        String normalizedSession = sessionId == null ? "" : sessionId.trim();
        List<String> normalized = new ArrayList<>();
        for (String id : missingToolpacks) {
            if (id != null && !id.isBlank()) {
                normalized.add(id.trim().toUpperCase(Locale.ROOT));
            }
        }
        Collections.sort(normalized);
        return "missing_toolpack|" + normalizedSession + "|" + String.join(",", normalized);
    }

    public record AutoProvisionResult(
        int scannedWaitingTasks,
        int createdWorkers,
        int skippedTooFresh,
        int skippedMissingToolpacks,
        int skippedByCapacity,
        int createdClarificationTickets,
        List<String> createdWorkerIds,
        List<String> createdClarificationTicketIds
    ) {
    }
}
