package com.agentx.agentxbackend.process.application;

import com.agentx.agentxbackend.process.application.port.out.ArchitectTicketEventContext;
import com.agentx.agentxbackend.process.application.port.out.ArchitectTicketProposalGeneratorPort;
import com.agentx.agentxbackend.requirement.application.port.in.RequirementCurrentDoc;
import com.agentx.agentxbackend.requirement.application.port.in.RequirementDocQueryUseCase;
import com.agentx.agentxbackend.session.application.port.in.SessionHistoryQueryUseCase;
import com.agentx.agentxbackend.session.application.query.SessionHistoryView;
import com.agentx.agentxbackend.ticket.application.port.in.TicketCommandUseCase;
import com.agentx.agentxbackend.ticket.application.port.in.TicketQueryUseCase;
import com.agentx.agentxbackend.ticket.domain.model.Ticket;
import com.agentx.agentxbackend.ticket.domain.model.TicketEvent;
import com.agentx.agentxbackend.ticket.domain.model.TicketStatus;
import com.agentx.agentxbackend.ticket.domain.model.TicketType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

@Service
public class ArchitectTicketAutoProcessorService {

    private static final Logger log = LoggerFactory.getLogger(ArchitectTicketAutoProcessorService.class);
    private static final String PLANNING_LOCK_DELIMITER = "#planning#";

    private final SessionHistoryQueryUseCase sessionHistoryQueryUseCase;
    private final RequirementDocQueryUseCase requirementDocQueryUseCase;
    private final TicketQueryUseCase ticketQueryUseCase;
    private final TicketCommandUseCase ticketCommandUseCase;
    private final ArchitectTicketProposalGeneratorPort proposalGeneratorPort;
    private final ArchitectWorkPlanningService architectWorkPlanningService;
    private final ObjectMapper objectMapper;
    private final String autoAgentId;
    private final int leaseSeconds;
    private final Set<String> ownedSessionIds;
    private final int shardTotal;
    private final int shardIndex;
    private final int maxRecentEventContext;

    public ArchitectTicketAutoProcessorService(
        SessionHistoryQueryUseCase sessionHistoryQueryUseCase,
        RequirementDocQueryUseCase requirementDocQueryUseCase,
        TicketQueryUseCase ticketQueryUseCase,
        TicketCommandUseCase ticketCommandUseCase,
        ArchitectTicketProposalGeneratorPort proposalGeneratorPort,
        ArchitectWorkPlanningService architectWorkPlanningService,
        ObjectMapper objectMapper,
        @Value("${agentx.architect.auto-processor.agent-id:architect-agent-auto}") String autoAgentId,
        @Value("${agentx.architect.auto-processor.lease-seconds:300}") int leaseSeconds,
        @Value("${agentx.architect.auto-processor.owned-session-ids:}") String ownedSessionIds,
        @Value("${agentx.architect.auto-processor.shard-total:1}") int shardTotal,
        @Value("${agentx.architect.auto-processor.shard-index:0}") int shardIndex,
        @Value("${agentx.architect.auto-processor.max-recent-events:12}") int maxRecentEventContext
    ) {
        this.sessionHistoryQueryUseCase = sessionHistoryQueryUseCase;
        this.requirementDocQueryUseCase = requirementDocQueryUseCase;
        this.ticketQueryUseCase = ticketQueryUseCase;
        this.ticketCommandUseCase = ticketCommandUseCase;
        this.proposalGeneratorPort = proposalGeneratorPort;
        this.architectWorkPlanningService = architectWorkPlanningService;
        this.objectMapper = objectMapper;
        this.autoAgentId = (autoAgentId == null || autoAgentId.isBlank())
            ? "architect-agent-auto"
            : autoAgentId.trim();
        this.leaseSeconds = Math.max(30, leaseSeconds);
        this.ownedSessionIds = parseOwnedSessionIds(ownedSessionIds);
        this.shardTotal = Math.max(1, shardTotal);
        this.shardIndex = Math.floorMod(shardIndex, this.shardTotal);
        this.maxRecentEventContext = Math.max(1, maxRecentEventContext);
    }

    public AutoProcessResult processOpenArchitectTickets(String sessionId, int maxTickets) {
        int cappedMax = maxTickets <= 0 ? 8 : Math.min(maxTickets, 100);
        Set<String> targetSessions = resolveTargetSessions(sessionId);

        List<String> processed = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        int consumed = 0;

        for (String sid : targetSessions) {
            if (consumed >= cappedMax) {
                break;
            }
            List<Ticket> candidates = ticketQueryUseCase.listBySession(sid, null, "architect_agent", null);
            for (Ticket ticket : candidates) {
                if (consumed >= cappedMax) {
                    break;
                }
                if (!isArchitectConsumable(ticket)) {
                    continue;
                }
                List<TicketEvent> events = ticketQueryUseCase.listEvents(ticket.ticketId());
                RequirementCurrentDoc currentDoc = loadCurrentRequirementDoc(ticket.sessionId());
                ProcessingStage stage = resolveProcessingStage(ticket, events, currentDoc);
                if (stage == ProcessingStage.NONE) {
                    continue;
                }
                boolean ok = processOne(ticket, events, stage, currentDoc);
                if (ok) {
                    processed.add(ticket.ticketId());
                    consumed++;
                } else {
                    skipped.add(ticket.ticketId());
                }
            }
        }

        return new AutoProcessResult(processed.size(), processed, skipped);
    }

    private boolean processOne(
        Ticket ticket,
        List<TicketEvent> events,
        ProcessingStage stage,
        RequirementCurrentDoc currentDoc
    ) {
        Ticket workingTicket = ticket;
        if (stage == ProcessingStage.REQUEST_USER) {
            try {
                workingTicket = ticketCommandUseCase.claimTicket(ticket.ticketId(), autoAgentId, leaseSeconds);
            } catch (RuntimeException ex) {
                log.debug("Skip architect ticket claim, ticketId={}, cause={}", ticket.ticketId(), ex.getMessage());
                return false;
            }
        }
        if (stage == ProcessingStage.PLAN_WORK || stage == ProcessingStage.PLAN_WORK_RECOVER) {
            Ticket planningLocked = tryAcquirePlanningLease(ticket, stage);
            if (planningLocked == null) {
                return false;
            }
            workingTicket = planningLocked;
        }

        String requirementDocContent = loadRequirementDocContent(workingTicket, currentDoc);
        List<ArchitectTicketEventContext> recentEvents = toRecentEventContext(events);
        if (stage == ProcessingStage.PLAN_WORK || stage == ProcessingStage.PLAN_WORK_RECOVER) {
            return processPlanningStage(workingTicket, requirementDocContent, recentEvents);
        }

        return processRequestStage(workingTicket, requirementDocContent, recentEvents);
    }

    private boolean processRequestStage(
        Ticket ticket,
        String requirementDocContent,
        List<ArchitectTicketEventContext> recentEvents
    ) {
        ArchitectTicketProposalGeneratorPort.Proposal proposal;
        try {
            proposal = proposalGeneratorPort.generate(
                new ArchitectTicketProposalGeneratorPort.GenerateInput(
                    ticket.ticketId(),
                    ticket.sessionId(),
                    ticket.type().name(),
                    ticket.title(),
                    ticket.requirementDocId(),
                    ticket.requirementDocVer(),
                    ticket.payloadJson(),
                    requirementDocContent,
                    recentEvents
                )
            );
        } catch (RuntimeException ex) {
            log.warn(
                "Architect proposal generation failed, fallback to CLARIFICATION. ticketId={}",
                ticket.ticketId(),
                ex
            );
            proposal = fallbackProposal(ticket);
        }

        try {
            String commentData = buildCommentDataJson(ticket, proposal);
            ticketCommandUseCase.appendEvent(
                ticket.ticketId(),
                "architect_agent",
                "COMMENT",
                proposal.analysisSummary(),
                commentData
            );

            String requestData = buildDecisionRequestedDataJson(ticket, proposal);
            ticketCommandUseCase.appendEvent(
                ticket.ticketId(),
                "architect_agent",
                "DECISION_REQUESTED",
                proposal.requestKind() + " request: " + proposal.question(),
                requestData
            );
            if (shouldAutoAdoptRecommendation(ticket, proposal)) {
                ticketCommandUseCase.appendEvent(
                    ticket.ticketId(),
                    "architect_agent",
                    "USER_RESPONDED",
                    buildAutoResponseBody(proposal),
                    buildAutoResponseDataJson(ticket, proposal)
                );
            }
            return true;
        } catch (RuntimeException ex) {
            log.error("Architect request-stage processing failed, ticketId={}", ticket.ticketId(), ex);
            tryBlockTicket(ticket, ex.getMessage());
            return false;
        }
    }

    private boolean processPlanningStage(
        Ticket ticket,
        String requirementDocContent,
        List<ArchitectTicketEventContext> recentEvents
    ) {
        try {
            ArchitectWorkPlanningService.PlanResult planResult = architectWorkPlanningService.planAndPersist(
                ticket,
                requirementDocContent,
                recentEvents
            );

            ticketCommandUseCase.appendEvent(
                ticket.ticketId(),
                "architect_agent",
                "COMMENT",
                nullSafe(planResult.summary()),
                buildPlanningCommentDataJson(ticket, planResult)
            );
            ticketCommandUseCase.appendEvent(
                ticket.ticketId(),
                "architect_agent",
                "ARTIFACT_LINKED",
                "Architecture work plan persisted as modules/tasks.",
                buildPlanningArtifactDataJson(ticket, planResult)
            );
            ticketCommandUseCase.appendEvent(
                ticket.ticketId(),
                "architect_agent",
                "STATUS_CHANGED",
                "Architecture planning completed, ticket closed for execution phase.",
                buildPlanningDoneDataJson(ticket, planResult)
            );
            return true;
        } catch (RuntimeException ex) {
            log.error("Architect planning-stage processing failed, ticketId={}", ticket.ticketId(), ex);
            tryBlockTicket(ticket, ex.getMessage());
            return false;
        }
    }

    private boolean isAutoOwnedTicket(Ticket ticket) {
        return ticket != null
            && ticket.claimedBy() != null
            && autoAgentId.equals(ticket.claimedBy());
    }

    private ProcessingStage resolveProcessingStage(
        Ticket ticket,
        List<TicketEvent> events,
        RequirementCurrentDoc currentDoc
    ) {
        if (ticket == null || ticket.status() == null) {
            return ProcessingStage.NONE;
        }
        if (ticket.status() == TicketStatus.OPEN) {
            return ProcessingStage.REQUEST_USER;
        }
        if (ticket.status() != TicketStatus.IN_PROGRESS) {
            return ProcessingStage.NONE;
        }
        if (!hasEventType(events, "DECISION_REQUESTED")) {
            return ProcessingStage.NONE;
        }
        if (!hasEventType(events, "USER_RESPONDED")) {
            return ProcessingStage.NONE;
        }
        if (hasPlannerDoneMarker(events)) {
            return ProcessingStage.NONE;
        }
        if (isAutoOwnedTicket(ticket)) {
            return ProcessingStage.PLAN_WORK;
        }
        if (isPlanningLeaseHolder(ticket)) {
            if (isPlanningLeaseExpired(ticket)) {
                return ProcessingStage.PLAN_WORK_RECOVER;
            }
            return ProcessingStage.NONE;
        }
        return ProcessingStage.NONE;
    }

    private Ticket tryAcquirePlanningLease(Ticket ticket, ProcessingStage stage) {
        if (ticket == null || ticket.ticketId() == null || ticket.ticketId().isBlank()) {
            return null;
        }
        String currentOwner = ticket.claimedBy();
        if (currentOwner == null || currentOwner.isBlank()) {
            return null;
        }
        String nextOwner = planningLockPrefix() + UUID.randomUUID().toString().replace("-", "");
        try {
            return ticketCommandUseCase.tryMovePlanningLease(
                ticket.ticketId(),
                currentOwner,
                nextOwner,
                leaseSeconds
            ).orElseGet(() -> {
                log.debug(
                    "Skip architect planning lock acquire due to CAS miss, ticketId={}, stage={}",
                    ticket.ticketId(),
                    stage
                );
                return null;
            });
        } catch (RuntimeException ex) {
            log.debug(
                "Skip architect planning lock acquire failed, ticketId={}, stage={}, cause={}",
                ticket.ticketId(),
                stage,
                ex.getMessage()
            );
            return null;
        }
    }

    private boolean isPlanningLeaseHolder(Ticket ticket) {
        return ticket != null
            && ticket.claimedBy() != null
            && ticket.claimedBy().startsWith(planningLockPrefix());
    }

    private boolean isPlanningLeaseExpired(Ticket ticket) {
        if (ticket == null || ticket.leaseUntil() == null) {
            return true;
        }
        return !ticket.leaseUntil().isAfter(Instant.now());
    }

    private String planningLockPrefix() {
        return autoAgentId + PLANNING_LOCK_DELIMITER;
    }

    private String loadRequirementDocContent(Ticket ticket, RequirementCurrentDoc docView) {
        if (docView == null) {
            return "";
        }
        if (!hasConfirmedRequirementBaseline(ticket, docView)) {
            return "";
        }
        return nullSafe(docView.content());
    }

    private RequirementCurrentDoc loadCurrentRequirementDoc(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return null;
        }
        var optional = requirementDocQueryUseCase.findCurrentBySessionId(sessionId);
        return optional == null ? null : optional.orElse(null);
    }

    private boolean hasConfirmedRequirementBaseline(Ticket ticket, RequirementCurrentDoc currentDoc) {
        if (ticket == null || currentDoc == null) {
            return false;
        }
        if (!"CONFIRMED".equalsIgnoreCase(nullSafe(currentDoc.status()))) {
            return false;
        }
        Integer confirmedVersion = currentDoc.confirmedVersion();
        if (confirmedVersion == null || confirmedVersion <= 0) {
            return false;
        }
        if (ticket.requirementDocId() == null || ticket.requirementDocId().isBlank()) {
            return false;
        }
        if (ticket.requirementDocVer() == null || ticket.requirementDocVer() <= 0) {
            return false;
        }
        if (!ticket.requirementDocId().equals(currentDoc.docId())) {
            return false;
        }
        return ticket.requirementDocVer() <= confirmedVersion;
    }

    private List<ArchitectTicketEventContext> toRecentEventContext(List<TicketEvent> events) {
        if (events == null || events.isEmpty()) {
            return List.of();
        }
        int from = Math.max(0, events.size() - maxRecentEventContext);
        List<ArchitectTicketEventContext> contexts = new ArrayList<>(events.size() - from);
        for (int i = from; i < events.size(); i++) {
            TicketEvent event = events.get(i);
            if (event == null) {
                continue;
            }
            contexts.add(
                new ArchitectTicketEventContext(
                    event.eventType() == null ? "" : event.eventType().name(),
                    nullSafe(event.actorRole()),
                    nullSafe(event.body()),
                    nullSafe(event.dataJson()),
                    event.createdAt() == null ? "" : event.createdAt().toString()
                )
            );
        }
        return contexts;
    }

    private static boolean hasEventType(List<TicketEvent> events, String expectedType) {
        if (events == null || events.isEmpty() || expectedType == null || expectedType.isBlank()) {
            return false;
        }
        String target = expectedType.trim().toUpperCase(Locale.ROOT);
        for (TicketEvent event : events) {
            if (event == null || event.eventType() == null) {
                continue;
            }
            if (target.equals(event.eventType().name())) {
                return true;
            }
        }
        return false;
    }

    private boolean hasPlannerDoneMarker(List<TicketEvent> events) {
        if (events == null || events.isEmpty()) {
            return false;
        }
        for (TicketEvent event : events) {
            if (event == null || event.eventType() == null) {
                continue;
            }
            if (event.eventType().name().equals("ARTIFACT_LINKED") && isPlannerSourceData(event.dataJson())) {
                return true;
            }
            if (event.eventType().name().equals("STATUS_CHANGED") && isPlannerSourceData(event.dataJson())) {
                return true;
            }
        }
        return false;
    }

    private boolean isPlannerSourceData(String dataJson) {
        if (dataJson == null || dataJson.isBlank()) {
            return false;
        }
        try {
            return "architect_auto_planner".equals(
                objectMapper.readTree(dataJson).path("source").asText("")
            );
        } catch (Exception ignored) {
            return false;
        }
    }

    private void tryBlockTicket(Ticket ticket, String reason) {
        try {
            ObjectNode data = objectMapper.createObjectNode();
            data.put("to_status", "BLOCKED");
            data.put("reason", "Architect auto-processing failed: " + nullSafe(reason));
            data.put("source", "architect_auto_processor");
            ticketCommandUseCase.appendEvent(
                ticket.ticketId(),
                "architect_agent",
                "STATUS_CHANGED",
                "Architect auto-processing failed and blocked for manual triage.",
                objectMapper.writeValueAsString(data)
            );
        } catch (Exception ignored) {
            log.warn("Failed to block ticket after auto-processing failure, ticketId={}", ticket.ticketId());
        }
    }

    private String buildCommentDataJson(Ticket ticket, ArchitectTicketProposalGeneratorPort.Proposal proposal) {
        try {
            ObjectNode data = objectMapper.createObjectNode();
            data.put("source", "architect_auto_processor");
            data.put("source_ticket_id", ticket.ticketId());
            data.put("request_kind", proposal.requestKind());
            data.put("provider", nullSafe(proposal.provider()));
            data.put("model", nullSafe(proposal.model()));
            data.put("summary", nullSafe(proposal.analysisSummary()));
            return objectMapper.writeValueAsString(data);
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to build comment data json", ex);
        }
    }

    private String buildDecisionRequestedDataJson(Ticket ticket, ArchitectTicketProposalGeneratorPort.Proposal proposal) {
        try {
            ObjectNode data = objectMapper.createObjectNode();
            data.put("request_kind", proposal.requestKind());
            data.put("question", proposal.question());
            data.put("source_ticket_id", ticket.ticketId());
            data.put("source", "architect_auto_processor");
            data.put("provider", nullSafe(proposal.provider()));
            data.put("model", nullSafe(proposal.model()));
            data.put("analysis_summary", nullSafe(proposal.analysisSummary()));

            ArrayNode context = data.putArray("context");
            for (String line : proposal.context()) {
                if (line != null && !line.isBlank()) {
                    context.add(line);
                }
            }

            if ("DECISION".equalsIgnoreCase(proposal.requestKind())) {
                ArrayNode options = data.putArray("options");
                for (ArchitectTicketProposalGeneratorPort.DecisionOption option : proposal.options()) {
                    if (option == null) {
                        continue;
                    }
                    ObjectNode optionNode = options.addObject();
                    optionNode.put("option_id", nullSafe(option.optionId()));
                    optionNode.put("title", nullSafe(option.title()));
                    writeArray(optionNode.putArray("pros"), option.pros());
                    writeArray(optionNode.putArray("cons"), option.cons());
                    writeArray(optionNode.putArray("risks"), option.risks());
                    writeArray(optionNode.putArray("cost_notes"), option.costNotes());
                }
                ArchitectTicketProposalGeneratorPort.Recommendation recommendation = proposal.recommendation();
                if (recommendation != null) {
                    ObjectNode recommendationNode = data.putObject("recommendation");
                    recommendationNode.put("option_id", nullSafe(recommendation.optionId()));
                    recommendationNode.put("reason", nullSafe(recommendation.reason()));
                }
            }
            return objectMapper.writeValueAsString(data);
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to build decision requested data json", ex);
        }
    }

    private boolean shouldAutoAdoptRecommendation(
        Ticket ticket,
        ArchitectTicketProposalGeneratorPort.Proposal proposal
    ) {
        if (proposal == null || !"DECISION".equalsIgnoreCase(nullSafe(proposal.requestKind()))) {
            return false;
        }
        ArchitectTicketProposalGeneratorPort.Recommendation recommendation = proposal.recommendation();
        if (recommendation == null || recommendation.optionId() == null || recommendation.optionId().isBlank()) {
            return false;
        }
        // Requirement-confirmation architecture review should converge automatically when
        // the architect has already produced a recommended path.
        if (ticket != null && ticket.type() == TicketType.ARCH_REVIEW) {
            return true;
        }
        List<ArchitectTicketProposalGeneratorPort.DecisionOption> options = proposal.options();
        if (options == null || options.size() != 1) {
            return false;
        }
        ArchitectTicketProposalGeneratorPort.DecisionOption onlyOption = options.get(0);
        if (onlyOption == null || onlyOption.optionId() == null || onlyOption.optionId().isBlank()) {
            return false;
        }
        return recommendation.optionId().trim().equalsIgnoreCase(onlyOption.optionId().trim());
    }

    private String buildAutoResponseBody(ArchitectTicketProposalGeneratorPort.Proposal proposal) {
        ArchitectTicketProposalGeneratorPort.Recommendation recommendation = proposal.recommendation();
        String optionId = recommendation == null ? "" : nullSafe(recommendation.optionId());
        String optionTitle = findRecommendedOptionTitle(proposal);
        if (!optionTitle.isBlank() && !optionId.isBlank()) {
            return "Auto-adopted recommendation " + optionId + ": " + optionTitle;
        }
        if (!optionTitle.isBlank()) {
            return "Auto-adopted recommendation: " + optionTitle;
        }
        if (!optionId.isBlank()) {
            return "Auto-adopted recommendation " + optionId + ".";
        }
        return "Auto-adopted the sole recommended option.";
    }

    private String buildAutoResponseDataJson(Ticket ticket, ArchitectTicketProposalGeneratorPort.Proposal proposal) {
        try {
            ObjectNode data = objectMapper.createObjectNode();
            data.put("source", "architect_auto_processor");
            data.put("source_ticket_id", ticket.ticketId());
            data.put("request_kind", nullSafe(proposal.requestKind()));
            data.put("auto_selected", true);
            data.put("provider", nullSafe(proposal.provider()));
            data.put("model", nullSafe(proposal.model()));
            ArchitectTicketProposalGeneratorPort.Recommendation recommendation = proposal.recommendation();
            if (recommendation != null) {
                data.put("selected_option_id", nullSafe(recommendation.optionId()));
                data.put("reason", nullSafe(recommendation.reason()));
            }
            String optionTitle = findRecommendedOptionTitle(proposal);
            if (!optionTitle.isBlank()) {
                data.put("selected_option_title", optionTitle);
            }
            data.put("question", nullSafe(proposal.question()));
            data.put("analysis_summary", nullSafe(proposal.analysisSummary()));
            return objectMapper.writeValueAsString(data);
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to build auto response data json", ex);
        }
    }

    private String findRecommendedOptionTitle(ArchitectTicketProposalGeneratorPort.Proposal proposal) {
        if (proposal == null || proposal.recommendation() == null) {
            return "";
        }
        String optionId = nullSafe(proposal.recommendation().optionId());
        if (optionId.isBlank() || proposal.options() == null) {
            return "";
        }
        for (ArchitectTicketProposalGeneratorPort.DecisionOption option : proposal.options()) {
            if (option == null || option.optionId() == null) {
                continue;
            }
            if (optionId.equalsIgnoreCase(option.optionId().trim())) {
                return nullSafe(option.title());
            }
        }
        return "";
    }

    private String buildPlanningCommentDataJson(
        Ticket ticket,
        ArchitectWorkPlanningService.PlanResult planResult
    ) {
        try {
            ObjectNode data = objectMapper.createObjectNode();
            data.put("source", "architect_auto_planner");
            data.put("source_ticket_id", ticket.ticketId());
            data.put("provider", nullSafe(planResult.provider()));
            data.put("model", nullSafe(planResult.model()));
            data.put("summary", nullSafe(planResult.summary()));
            data.put("module_count", planResult.createdModules().size());
            return objectMapper.writeValueAsString(data);
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to build planning comment data json", ex);
        }
    }

    private String buildPlanningArtifactDataJson(
        Ticket ticket,
        ArchitectWorkPlanningService.PlanResult planResult
    ) {
        try {
            ObjectNode data = objectMapper.createObjectNode();
            data.put("source", "architect_auto_planner");
            data.put("source_ticket_id", ticket.ticketId());
            data.put("provider", nullSafe(planResult.provider()));
            data.put("model", nullSafe(planResult.model()));
            ArrayNode modulesNode = data.putArray("created_modules");
            for (ArchitectWorkPlanningService.CreatedModule module : planResult.createdModules()) {
                ObjectNode moduleNode = modulesNode.addObject();
                moduleNode.put("module_id", module.moduleId());
                moduleNode.put("name", module.name());
                moduleNode.put("description", nullSafe(module.description()));
                ArrayNode tasksNode = moduleNode.putArray("created_tasks");
                for (ArchitectWorkPlanningService.CreatedTask task : module.createdTasks()) {
                    ObjectNode taskNode = tasksNode.addObject();
                    taskNode.put("task_key", nullSafe(task.taskKey()));
                    taskNode.put("task_id", task.taskId());
                    taskNode.put("title", task.title());
                    taskNode.put("task_template_id", task.taskTemplateId());
                    taskNode.put("status", task.status());
                    taskNode.put("required_toolpacks_json", task.requiredToolpacksJson());
                    ArrayNode dependsOnTaskIds = taskNode.putArray("depends_on_task_ids");
                    if (task.dependsOnTaskIds() != null) {
                        for (String dependsOnTaskId : task.dependsOnTaskIds()) {
                            if (dependsOnTaskId != null && !dependsOnTaskId.isBlank()) {
                                dependsOnTaskIds.add(dependsOnTaskId);
                            }
                        }
                    }
                    ArrayNode unresolvedDependsOnKeys = taskNode.putArray("unresolved_depends_on_keys");
                    if (task.unresolvedDependsOnKeys() != null) {
                        for (String unresolvedDependsOnKey : task.unresolvedDependsOnKeys()) {
                            if (unresolvedDependsOnKey != null && !unresolvedDependsOnKey.isBlank()) {
                                unresolvedDependsOnKeys.add(unresolvedDependsOnKey);
                            }
                        }
                    }
                    if (task.rationale() != null && !task.rationale().isBlank()) {
                        taskNode.put("rationale", task.rationale());
                    }
                    if (task.contextSnapshotId() != null && !task.contextSnapshotId().isBlank()) {
                        taskNode.put("context_snapshot_id", task.contextSnapshotId());
                    }
                }
            }
            return objectMapper.writeValueAsString(data);
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to build planning artifact data json", ex);
        }
    }

    private String buildPlanningDoneDataJson(
        Ticket ticket,
        ArchitectWorkPlanningService.PlanResult planResult
    ) {
        try {
            ObjectNode data = objectMapper.createObjectNode();
            data.put("to_status", "DONE");
            data.put("reason", "Task planning completed and execution work items created.");
            data.put("source", "architect_auto_planner");
            data.put("source_ticket_id", ticket.ticketId());
            data.put("provider", nullSafe(planResult.provider()));
            data.put("model", nullSafe(planResult.model()));
            data.put("module_count", planResult.createdModules().size());
            int taskCount = 0;
            for (ArchitectWorkPlanningService.CreatedModule module : planResult.createdModules()) {
                taskCount += module.createdTasks().size();
            }
            data.put("task_count", taskCount);
            return objectMapper.writeValueAsString(data);
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to build planning done data json", ex);
        }
    }

    private static void writeArray(ArrayNode node, List<String> values) {
        if (values == null) {
            return;
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                node.add(value);
            }
        }
    }

    private Set<String> resolveTargetSessions(String sessionId) {
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        if (sessionId != null && !sessionId.isBlank()) {
            String normalized = sessionId.trim();
            if (isSessionInScope(normalized)) {
                ids.add(normalized);
            } else {
                log.debug(
                    "Skip architect auto-process for out-of-scope session, sessionId={}, agentId={}",
                    normalized,
                    autoAgentId
                );
            }
            return ids;
        }
        List<SessionHistoryView> sessions = sessionHistoryQueryUseCase.listSessionsWithCurrentRequirementDoc();
        for (SessionHistoryView session : sessions) {
            if (session != null && session.sessionId() != null && !session.sessionId().isBlank()) {
                String sid = session.sessionId();
                if (isSessionInScope(sid)) {
                    ids.add(sid);
                }
            }
        }
        return ids;
    }

    private boolean isSessionInScope(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return false;
        }
        if (!ownedSessionIds.isEmpty() && !ownedSessionIds.contains(sessionId)) {
            return false;
        }
        if (shardTotal <= 1) {
            return true;
        }
        int bucket = Math.floorMod(sessionId.hashCode(), shardTotal);
        return bucket == shardIndex;
    }

    private static Set<String> parseOwnedSessionIds(String raw) {
        if (raw == null || raw.isBlank()) {
            return Collections.emptySet();
        }
        String[] parts = raw.split(",");
        Set<String> ids = new HashSet<>();
        for (String part : parts) {
            if (part == null) {
                continue;
            }
            String trimmed = part.trim();
            if (!trimmed.isBlank()) {
                ids.add(trimmed);
            }
        }
        if (ids.isEmpty()) {
            return Collections.emptySet();
        }
        return Set.copyOf(ids);
    }

    private static boolean isArchitectConsumable(Ticket ticket) {
        if (ticket == null || ticket.type() == null) {
            return false;
        }
        return ticket.type() == TicketType.HANDOFF || ticket.type() == TicketType.ARCH_REVIEW;
    }

    private ArchitectTicketProposalGeneratorPort.Proposal fallbackProposal(Ticket ticket) {
        String question = "Please clarify missing architecture constraints for " + nullSafe(ticket.title()) + ".";
        List<String> context = new ArrayList<>();
        if (ticket.requirementDocId() != null && !ticket.requirementDocId().isBlank()) {
            context.add("Requirement reference: " + ticket.requirementDocId() + "@" + ticket.requirementDocVer());
        }
        context.add("Fallback generated because LLM call failed.");
        return new ArchitectTicketProposalGeneratorPort.Proposal(
            "CLARIFICATION",
            question,
            context,
            List.of(),
            null,
            "Auto fallback: unable to produce architecture decision options without additional confirmed facts.",
            "fallback",
            "fallback"
        );
    }

    private static String nullSafe(String value) {
        return value == null ? "" : value;
    }

    public record AutoProcessResult(
        int processedCount,
        List<String> processedTicketIds,
        List<String> skippedTicketIds
    ) {
    }

    private enum ProcessingStage {
        NONE,
        REQUEST_USER,
        PLAN_WORK,
        PLAN_WORK_RECOVER
    }
}
