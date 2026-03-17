package com.agentx.agentxbackend.process.application;

import com.agentx.agentxbackend.execution.domain.event.RunNeedsClarificationEvent;
import com.agentx.agentxbackend.execution.domain.event.RunNeedsDecisionEvent;
import com.agentx.agentxbackend.planning.application.port.in.WaitingTaskQueryUseCase;
import com.agentx.agentxbackend.requirement.application.port.in.RequirementCurrentDoc;
import com.agentx.agentxbackend.requirement.application.port.in.RequirementDocQueryUseCase;
import com.agentx.agentxbackend.ticket.application.port.in.TicketCommandUseCase;
import com.agentx.agentxbackend.ticket.application.port.in.TicketQueryUseCase;
import com.agentx.agentxbackend.ticket.domain.model.Ticket;
import com.agentx.agentxbackend.ticket.domain.model.TicketEvent;
import com.agentx.agentxbackend.ticket.domain.model.TicketStatus;
import com.agentx.agentxbackend.ticket.domain.model.TicketType;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

@Component
public class RunNeedsInputProcessManager {

    private static final Logger log = LoggerFactory.getLogger(RunNeedsInputProcessManager.class);
    private static final Set<TicketStatus> ACTIVE_STATUSES = Set.of(
        TicketStatus.OPEN,
        TicketStatus.IN_PROGRESS,
        TicketStatus.WAITING_USER
    );
    private static final String RUN_NEED_INPUT_KIND = "run_need_input";
    private static final String HANDOFF_PACKET_KIND = "handoff_packet";
    private static final String PLANNER_NOOP_GUARD_KIND = "PLANNER_NOOP";
    private static final String PLANNER_NOOP_GUARD_TRIGGER = "PLANNER_NOOP_GUARD";
    private static final String PLANNER_NOOP_ZH_MARKER = "规划器连续两次都没有返回会产生实际代码变更的 edits";
    private static final String PLANNER_NOOP_EN_MARKER = "planner failed twice to return edits that change the worktree";

    private final TicketCommandUseCase ticketCommandUseCase;
    private final TicketQueryUseCase ticketQueryUseCase;
    private final WaitingTaskQueryUseCase waitingTaskQueryUseCase;
    private final RequirementDocQueryUseCase requirementDocQueryUseCase;
    private final ObjectMapper objectMapper;
    private final String architectAgentId;
    private final int architectLeaseSeconds;
    private final int noopClarificationMaxBeforeArchReview;

    public RunNeedsInputProcessManager(
        TicketCommandUseCase ticketCommandUseCase,
        TicketQueryUseCase ticketQueryUseCase,
        WaitingTaskQueryUseCase waitingTaskQueryUseCase,
        RequirementDocQueryUseCase requirementDocQueryUseCase,
        ObjectMapper objectMapper,
        @Value("${agentx.architect.auto-processor.agent-id:architect-agent-auto}") String architectAgentId,
        @Value("${agentx.architect.auto-processor.lease-seconds:300}") int architectLeaseSeconds,
        @Value("${agentx.process.run-need-input.noop-clarification-max-before-arch-review:3}")
        int noopClarificationMaxBeforeArchReview
    ) {
        this.ticketCommandUseCase = ticketCommandUseCase;
        this.ticketQueryUseCase = ticketQueryUseCase;
        this.waitingTaskQueryUseCase = waitingTaskQueryUseCase;
        this.requirementDocQueryUseCase = requirementDocQueryUseCase;
        this.objectMapper = objectMapper;
        this.architectAgentId = normalizeAgentId(architectAgentId);
        this.architectLeaseSeconds = Math.max(30, architectLeaseSeconds);
        this.noopClarificationMaxBeforeArchReview = Math.max(1, noopClarificationMaxBeforeArchReview);
    }

    public void handle(RunNeedsDecisionEvent event) {
        Objects.requireNonNull(event, "event must not be null");
        handleNeedInput(
            event.runId(),
            event.taskId(),
            event.body(),
            event.dataJson(),
            TicketType.DECISION,
            "DECISION required for run " + event.runId()
        );
    }

    public void handle(RunNeedsClarificationEvent event) {
        Objects.requireNonNull(event, "event must not be null");
        handleNeedInput(
            event.runId(),
            event.taskId(),
            event.body(),
            event.dataJson(),
            TicketType.CLARIFICATION,
            "CLARIFICATION required for run " + event.runId()
        );
    }

    private void handleNeedInput(
        String runId,
        String taskId,
        String body,
        String dataJson,
        TicketType type,
        String title
    ) {
        String normalizedRunId = requireNotBlank(runId, "runId");
        String normalizedTaskId = requireNotBlank(taskId, "taskId");
        String normalizedBody = requireNotBlank(body, "body");
        String sessionId = waitingTaskQueryUseCase.findSessionIdByTaskId(normalizedTaskId)
            .orElseThrow(() -> new NoSuchElementException(
                "Session not found for task: " + normalizedTaskId
            ));
        RequirementRef requirementRef = resolveRequirementRef(sessionId);
        Optional<Ticket> escalatedTicket = maybeEscalatePlannerNoopClarification(
            sessionId,
            normalizedRunId,
            normalizedTaskId,
            type,
            normalizedBody,
            dataJson,
            requirementRef
        );
        if (escalatedTicket.isPresent()) {
            return;
        }
        Optional<Ticket> reusableTicket = reuseOrSupersedeRunNeedInputTickets(
            sessionId,
            normalizedRunId,
            normalizedTaskId,
            type,
            normalizedBody,
            dataJson
        );
        if (reusableTicket.isPresent()) {
            return;
        }

        Ticket created = ticketCommandUseCase.createTicket(
            sessionId,
            type,
            title,
            "architect_agent",
            "architect_agent",
            requirementRef.docId(),
            requirementRef.docVersion(),
            buildPayloadJson(normalizedRunId, normalizedTaskId, type, normalizedBody, dataJson)
        );
        Ticket claimed = claimByArchitect(created.ticketId(), normalizedRunId, normalizedTaskId);
        if (claimed == null) {
            return;
        }
        ticketCommandUseCase.appendEvent(
            claimed.ticketId(),
            "architect_agent",
            "COMMENT",
            "Worker requested " + type.name() + " triage for run " + normalizedRunId + ".",
            buildCommentDataJson(normalizedRunId, normalizedTaskId, type, dataJson)
        );
        ticketCommandUseCase.appendEvent(
            claimed.ticketId(),
            "architect_agent",
            "DECISION_REQUESTED",
            normalizedBody,
            buildEventDataJson(normalizedRunId, normalizedTaskId, type, normalizedBody, dataJson)
        );
    }

    private Optional<Ticket> reuseOrSupersedeRunNeedInputTickets(
        String sessionId,
        String runId,
        String taskId,
        TicketType ticketType,
        String body,
        String dataJson
    ) {
        for (Ticket ticket : ticketQueryUseCase.listBySession(sessionId, null, "architect_agent", ticketType.name())) {
            if (ticket == null || !ACTIVE_STATUSES.contains(ticket.status())) {
                continue;
            }
            RunNeedInputTicketRef ref = parseRunNeedInputTicketRef(ticket);
            if (ref == null || !taskId.equals(ref.taskId()) || ticketType != ref.ticketType()) {
                continue;
            }
            if (!runId.equals(ref.runId())) {
                supersedeTicket(ticket, ref, runId);
                continue;
            }
            return Optional.of(promoteExistingTicket(ticket, ref, runId, taskId, ticketType, body, dataJson));
        }
        return Optional.empty();
    }

    private Ticket promoteExistingTicket(
        Ticket ticket,
        RunNeedInputTicketRef ref,
        String runId,
        String taskId,
        TicketType ticketType,
        String body,
        String dataJson
    ) {
        Ticket working = ticket;
        if (working.status() == TicketStatus.OPEN) {
            Ticket claimed = claimByArchitect(working.ticketId(), runId, taskId);
            if (claimed == null) {
                return working;
            }
            working = claimed;
        }
        ticketCommandUseCase.appendEvent(
            working.ticketId(),
            "architect_agent",
            "COMMENT",
            "Merged duplicated worker " + ticketType.name() + " request for run " + runId + ".",
            buildCommentDataJson(runId, taskId, ticketType, dataJson)
        );
        if (working.status() != TicketStatus.WAITING_USER || shouldRefreshWaitingUserRequest(working.ticketId(), ref, body, dataJson)) {
            ticketCommandUseCase.appendEvent(
                working.ticketId(),
                "architect_agent",
                "DECISION_REQUESTED",
                body,
                buildEventDataJson(runId, taskId, ticketType, body, dataJson)
            );
        }
        return working;
    }

    private boolean shouldRefreshWaitingUserRequest(
        String ticketId,
        RunNeedInputTicketRef ref,
        String body,
        String dataJson
    ) {
        if (!ref.needsRequestRefresh(body, dataJson)) {
            return false;
        }
        List<TicketEvent> events = ticketQueryUseCase.listEvents(ticketId);
        if (events == null || events.isEmpty()) {
            return true;
        }
        for (int i = events.size() - 1; i >= 0; i--) {
            TicketEvent event = events.get(i);
            if (event == null || event.eventType() == null) {
                continue;
            }
            if (!"DECISION_REQUESTED".equals(event.eventType().name())) {
                continue;
            }
            return !matchesNeedInputRequest(event, body, dataJson);
        }
        return true;
    }

    private boolean matchesNeedInputRequest(TicketEvent event, String body, String dataJson) {
        if (event == null) {
            return false;
        }
        String eventRunDataJson = null;
        if (event.dataJson() != null && !event.dataJson().isBlank()) {
            try {
                JsonNode root = objectMapper.readTree(event.dataJson());
                eventRunDataJson = readJsonString(root, "run_data_json");
            } catch (Exception ignored) {
                eventRunDataJson = null;
            }
        }
        return Objects.equals(normalizeNullable(event.body()), normalizeNullable(body))
            && Objects.equals(normalizeNullable(eventRunDataJson), normalizeNullable(dataJson));
    }

    private Optional<Ticket> maybeEscalatePlannerNoopClarification(
        String sessionId,
        String runId,
        String taskId,
        TicketType ticketType,
        String body,
        String dataJson,
        RequirementRef requirementRef
    ) {
        if (ticketType != TicketType.CLARIFICATION || !isPlannerNoopClarification(body, dataJson)) {
            return Optional.empty();
        }

        List<Ticket> clarificationTickets = ticketQueryUseCase.listBySession(
            sessionId,
            null,
            "architect_agent",
            TicketType.CLARIFICATION.name()
        );
        int historicalAttempts = 0;
        List<ActiveRunNeedInputTicket> activeClarifications = new ArrayList<>();
        for (Ticket ticket : clarificationTickets) {
            RunNeedInputTicketRef ref = parseRunNeedInputTicketRef(ticket);
            if (ref == null || !taskId.equals(ref.taskId()) || !ref.isPlannerNoopGuard()) {
                continue;
            }
            historicalAttempts++;
            if (ticket.status() != null && ACTIVE_STATUSES.contains(ticket.status())) {
                activeClarifications.add(new ActiveRunNeedInputTicket(ticket, ref));
            }
        }

        Optional<Ticket> existingArchReview = findActivePlannerNoopArchReview(sessionId, taskId);
        int currentAttempt = historicalAttempts + 1;
        if (existingArchReview.isEmpty() && currentAttempt <= noopClarificationMaxBeforeArchReview) {
            return Optional.empty();
        }

        for (ActiveRunNeedInputTicket activeClarification : activeClarifications) {
            if (!runId.equals(activeClarification.ref().runId())) {
                supersedeTicket(activeClarification.ticket(), activeClarification.ref(), runId);
                continue;
            }
            blockForPlannerNoopEscalation(activeClarification.ticket(), activeClarification.ref(), currentAttempt);
        }

        Ticket archReviewTicket = existingArchReview.orElseGet(() -> createPlannerNoopArchReview(
            sessionId,
            taskId,
            runId,
            body,
            dataJson,
            currentAttempt,
            requirementRef
        ));
        appendPlannerNoopArchReviewComment(archReviewTicket.ticketId(), taskId, runId, currentAttempt, existingArchReview.isPresent());
        return Optional.of(archReviewTicket);
    }

    private Optional<Ticket> findActivePlannerNoopArchReview(String sessionId, String taskId) {
        for (Ticket ticket : ticketQueryUseCase.listBySession(sessionId, null, "architect_agent", TicketType.ARCH_REVIEW.name())) {
            if (ticket == null || ticket.status() == null || !ACTIVE_STATUSES.contains(ticket.status())) {
                continue;
            }
            PlannerNoopArchReviewRef ref = parsePlannerNoopArchReviewRef(ticket);
            if (ref != null && taskId.equals(ref.taskId())) {
                return Optional.of(ticket);
            }
        }
        return Optional.empty();
    }

    private Ticket createPlannerNoopArchReview(
        String sessionId,
        String taskId,
        String runId,
        String body,
        String dataJson,
        int attemptCount,
        RequirementRef requirementRef
    ) {
        return ticketCommandUseCase.createTicket(
            sessionId,
            TicketType.ARCH_REVIEW,
            "ARCH_REVIEW: replan " + taskId + " after repeated no-op runs",
            "architect_agent",
            "architect_agent",
            requirementRef.docId(),
            requirementRef.docVersion(),
            buildPlannerNoopArchReviewPayloadJson(taskId, runId, body, dataJson, attemptCount)
        );
    }

    private void appendPlannerNoopArchReviewComment(
        String ticketId,
        String taskId,
        String runId,
        int attemptCount,
        boolean reusedExistingTicket
    ) {
        ObjectNode data = objectMapper.createObjectNode();
        data.put("source", "run_need_input_guard");
        data.put("trigger", PLANNER_NOOP_GUARD_TRIGGER);
        data.put("task_id", taskId);
        data.put("run_id", runId);
        data.put("attempt_count", attemptCount);
        data.put("reused_existing_ticket", reusedExistingTicket);
        ticketCommandUseCase.appendEvent(
            ticketId,
            "architect_agent",
            "COMMENT",
            "Escalated planner no-op clarification loop for task " + taskId + " after attempt " + attemptCount + ".",
            data.toString()
        );
    }

    private void blockForPlannerNoopEscalation(Ticket ticket, RunNeedInputTicketRef ref, int attemptCount) {
        try {
            ObjectNode data = objectMapper.createObjectNode();
            data.put("to_status", "BLOCKED");
            data.put("reason", "Escalated to ARCH_REVIEW after repeated planner no-op clarifications.");
            data.put("source", "run_need_input_guard");
            data.put("trigger", PLANNER_NOOP_GUARD_TRIGGER);
            data.put("task_id", ref.taskId());
            data.put("run_id", ref.runId());
            data.put("attempt_count", attemptCount);
            ticketCommandUseCase.appendEvent(
                ticket.ticketId(),
                "architect_agent",
                "STATUS_CHANGED",
                "Need-input ticket blocked because planner no-op protection escalated to ARCH_REVIEW.",
                data.toString()
            );
        } catch (RuntimeException ex) {
            log.warn(
                "Failed to block planner no-op clarification ticket, ticketId={}, taskId={}, cause={}",
                ticket.ticketId(),
                ref.taskId(),
                ex.getMessage()
            );
        }
    }

    private void supersedeTicket(Ticket staleTicket, RunNeedInputTicketRef staleRef, String replacementRunId) {
        try {
            ObjectNode data = objectMapper.createObjectNode();
            data.put("to_status", "BLOCKED");
            data.put("reason", "Superseded by run " + replacementRunId + " for the same task need-input flow.");
            data.put("source", "run_need_input_dedup");
            data.put("task_id", staleRef.taskId());
            data.put("run_id", staleRef.runId());
            data.put("superseded_by_run_id", replacementRunId);
            ticketCommandUseCase.appendEvent(
                staleTicket.ticketId(),
                "architect_agent",
                "STATUS_CHANGED",
                "Ticket superseded by a newer run-level need-input request.",
                data.toString()
            );
        } catch (RuntimeException ex) {
            log.warn(
                "Failed to supersede stale run need-input ticket, ticketId={}, runId={}, replacementRunId={}, cause={}",
                staleTicket.ticketId(),
                staleRef.runId(),
                replacementRunId,
                ex.getMessage()
            );
        }
    }

    private RunNeedInputTicketRef parseRunNeedInputTicketRef(Ticket ticket) {
        if (ticket == null || ticket.payloadJson() == null || ticket.payloadJson().isBlank()) {
            return null;
        }
        try {
            JsonNode root = objectMapper.readTree(ticket.payloadJson());
            if (!RUN_NEED_INPUT_KIND.equalsIgnoreCase(root.path("kind").asText())) {
                return null;
            }
            String runId = normalizeNullable(root.path("run_id").asText(null));
            String taskId = normalizeNullable(root.path("task_id").asText(null));
            String payloadType = normalizeNullable(root.path("ticket_type").asText(null));
            String summary = normalizeNullable(root.path("summary").asText(null));
            String guardKind = normalizeNullable(root.path("guard_kind").asText(null));
            String runDataJson = readJsonString(root, "run_data_json");
            if (runId == null || taskId == null) {
                return null;
            }
            TicketType ticketType = payloadType == null
                ? ticket.type()
                : TicketType.valueOf(payloadType.toUpperCase(Locale.ROOT));
            if (ticketType == null) {
                return null;
            }
            return new RunNeedInputTicketRef(runId, taskId, ticketType, summary, guardKind, runDataJson);
        } catch (Exception ex) {
            return null;
        }
    }

    private PlannerNoopArchReviewRef parsePlannerNoopArchReviewRef(Ticket ticket) {
        if (ticket == null || ticket.payloadJson() == null || ticket.payloadJson().isBlank()) {
            return null;
        }
        try {
            JsonNode root = objectMapper.readTree(ticket.payloadJson());
            if (!HANDOFF_PACKET_KIND.equalsIgnoreCase(root.path("kind").asText())) {
                return null;
            }
            if (!PLANNER_NOOP_GUARD_TRIGGER.equalsIgnoreCase(root.path("trigger").asText())) {
                return null;
            }
            String taskId = normalizeNullable(root.path("task_id").asText(null));
            String runId = normalizeNullable(root.path("run_id").asText(null));
            if (taskId == null) {
                return null;
            }
            return new PlannerNoopArchReviewRef(taskId, runId);
        } catch (Exception ex) {
            return null;
        }
    }

    private String buildPayloadJson(
        String runId,
        String taskId,
        TicketType type,
        String body,
        String dataJson
    ) {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("kind", RUN_NEED_INPUT_KIND);
        root.put("source", "worker_run_event");
        root.put("run_id", runId);
        root.put("task_id", taskId);
        root.put("ticket_type", type.name());
        root.put("summary", body);
        String guardKind = resolveRunNeedInputGuardKind(type, body, dataJson);
        if (guardKind != null) {
            root.put("guard_kind", guardKind);
        }
        if (dataJson != null) {
            root.put("run_data_json", dataJson);
        } else {
            root.putNull("run_data_json");
        }
        return root.toString();
    }

    private String buildEventDataJson(String runId, String taskId, TicketType type, String body, String dataJson) {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("source", "worker_run_event");
        root.put("run_id", runId);
        root.put("task_id", taskId);
        root.put("ticket_type", type.name());
        root.put("request_kind", type == TicketType.DECISION ? "DECISION" : "CLARIFICATION");
        root.put("question", body == null ? "" : body);
        String guardKind = resolveRunNeedInputGuardKind(type, body, dataJson);
        if (guardKind != null) {
            root.put("guard_kind", guardKind);
        }
        if (dataJson != null) {
            root.put("run_data_json", dataJson);
        } else {
            root.putNull("run_data_json");
        }
        mergeStructuredRunData(root, dataJson);
        if (root.path("question").asText("").isBlank()) {
            root.put("question", "Worker requires additional user input to proceed.");
        }
        return root.toString();
    }

    private String buildCommentDataJson(String runId, String taskId, TicketType type, String dataJson) {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("source", "worker_run_event");
        root.put("run_id", runId);
        root.put("task_id", taskId);
        root.put("ticket_type", type.name());
        String guardKind = resolveRunNeedInputGuardKind(type, null, dataJson);
        if (guardKind != null) {
            root.put("guard_kind", guardKind);
        }
        if (dataJson != null) {
            root.put("run_data_json", dataJson);
        } else {
            root.putNull("run_data_json");
        }
        return root.toString();
    }

    private String buildPlannerNoopArchReviewPayloadJson(
        String taskId,
        String runId,
        String body,
        String dataJson,
        int attemptCount
    ) {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("kind", HANDOFF_PACKET_KIND);
        root.put("trigger", PLANNER_NOOP_GUARD_TRIGGER);
        root.put("task_id", taskId);
        root.put("run_id", runId);
        root.put("attempt_count", attemptCount);

        ObjectNode userChangeNode = root.putObject("user_change");
        userChangeNode.put("summary", "Repeated planner no-op clarification loop requires task re-planning.");
        userChangeNode.put("raw_user_text", defaultText(body, "Planner repeatedly failed to produce executable edits."));

        ArrayNode questionNode = root.putArray("question_for_architect");
        questionNode.add(
            "Re-evaluate completed versus pending work for task " + taskId
                + ", split the remaining scope into executable tasks, and avoid no-op planner loops."
        );
        if (dataJson != null && !dataJson.isBlank()) {
            root.put("run_data_json", dataJson);
        } else {
            root.putNull("run_data_json");
        }
        return root.toString();
    }

    private void mergeStructuredRunData(ObjectNode root, String dataJson) {
        if (dataJson == null || dataJson.isBlank()) {
            return;
        }
        JsonNode runData;
        try {
            runData = objectMapper.readTree(dataJson);
        } catch (Exception ex) {
            return;
        }
        if (runData == null || !runData.isObject()) {
            return;
        }

        JsonNode questionNode = runData.path("question");
        if (questionNode.isTextual() && !questionNode.asText().isBlank()) {
            root.put("question", questionNode.asText());
        }
        JsonNode contextNode = runData.path("context");
        if (contextNode.isArray()) {
            root.set("context", contextNode);
        }
        JsonNode optionsNode = runData.path("options");
        if (optionsNode.isArray()) {
            root.set("options", optionsNode);
        }
        JsonNode recommendationNode = runData.path("recommendation");
        if (recommendationNode.isObject()) {
            root.set("recommendation", recommendationNode);
        }
    }

    private Ticket claimByArchitect(String ticketId, String runId, String taskId) {
        try {
            return ticketCommandUseCase.claimTicket(
                ticketId,
                architectAgentId,
                architectLeaseSeconds
            );
        } catch (RuntimeException ex) {
            log.warn(
                "Run need-input ticket claim failed, ticketId={}, runId={}, taskId={}, cause={}",
                ticketId,
                runId,
                taskId,
                ex.getMessage()
            );
            return null;
        }
    }

    private RequirementRef resolveRequirementRef(String sessionId) {
        Optional<RequirementCurrentDoc> currentDocOptional = requirementDocQueryUseCase.findCurrentBySessionId(sessionId);
        if (currentDocOptional == null || currentDocOptional.isEmpty()) {
            return new RequirementRef(null, null);
        }
        RequirementCurrentDoc currentDoc = currentDocOptional.get();
        if (currentDoc.docId() == null || currentDoc.docId().isBlank()) {
            return new RequirementRef(null, null);
        }
        if (currentDoc.confirmedVersion() == null || currentDoc.confirmedVersion() <= 0) {
            return new RequirementRef(null, null);
        }
        if (currentDoc.status() == null || !currentDoc.status().trim().equalsIgnoreCase("CONFIRMED")) {
            return new RequirementRef(null, null);
        }
        return new RequirementRef(currentDoc.docId(), currentDoc.confirmedVersion());
    }

    private String resolveRunNeedInputGuardKind(TicketType ticketType, String body, String dataJson) {
        if (ticketType == TicketType.CLARIFICATION && isPlannerNoopClarification(body, dataJson)) {
            return PLANNER_NOOP_GUARD_KIND;
        }
        return null;
    }

    private boolean isPlannerNoopClarification(String body, String dataJson) {
        if (dataJson != null && !dataJson.isBlank()) {
            try {
                JsonNode runData = objectMapper.readTree(dataJson);
                String explicitGuard = normalizeNullable(runData.path("guard_kind").asText(null));
                if (PLANNER_NOOP_GUARD_KIND.equalsIgnoreCase(defaultText(explicitGuard, ""))) {
                    return true;
                }
            } catch (Exception ignored) {
                // Fall through to summary-text matching.
            }
        }
        String normalizedBody = normalizeNullable(body);
        if (normalizedBody == null) {
            return false;
        }
        String lower = normalizedBody.toLowerCase(Locale.ROOT);
        return lower.contains(PLANNER_NOOP_EN_MARKER) || normalizedBody.contains(PLANNER_NOOP_ZH_MARKER);
    }

    private static String normalizeAgentId(String rawAgentId) {
        if (rawAgentId == null || rawAgentId.isBlank()) {
            return "architect-agent-auto";
        }
        return rawAgentId.trim();
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

    private static String defaultText(String value, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return value.trim();
    }

    private static String readJsonString(JsonNode root, String fieldName) {
        if (root == null || fieldName == null || fieldName.isBlank()) {
            return null;
        }
        JsonNode node = root.path(fieldName);
        if (node.isMissingNode() || node.isNull()) {
            return null;
        }
        if (node.isTextual()) {
            return normalizeNullable(node.asText(null));
        }
        return normalizeNullable(node.toString());
    }

    private record RunNeedInputTicketRef(
        String runId,
        String taskId,
        TicketType ticketType,
        String summary,
        String guardKind,
        String runDataJson
    ) {
        private boolean isPlannerNoopGuard() {
            if (PLANNER_NOOP_GUARD_KIND.equalsIgnoreCase(defaultText(guardKind, ""))) {
                return true;
            }
            String normalizedSummary = defaultText(summary, "");
            String lower = normalizedSummary.toLowerCase(Locale.ROOT);
            return lower.contains(PLANNER_NOOP_EN_MARKER) || normalizedSummary.contains(PLANNER_NOOP_ZH_MARKER);
        }

        private boolean needsRequestRefresh(String nextSummary, String nextRunDataJson) {
            return !Objects.equals(normalizeNullable(summary), normalizeNullable(nextSummary))
                || !Objects.equals(normalizeNullable(runDataJson), normalizeNullable(nextRunDataJson));
        }
    }

    private record ActiveRunNeedInputTicket(Ticket ticket, RunNeedInputTicketRef ref) {
    }

    private record PlannerNoopArchReviewRef(String taskId, String runId) {
    }

    private record RequirementRef(String docId, Integer docVersion) {
    }
}
