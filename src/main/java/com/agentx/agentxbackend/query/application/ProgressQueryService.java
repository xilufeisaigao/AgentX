package com.agentx.agentxbackend.query.application;

import com.agentx.agentxbackend.query.application.port.in.ProgressQueryUseCase;
import com.agentx.agentxbackend.query.application.port.out.ProgressReadRepository;
import com.agentx.agentxbackend.query.application.query.SessionProgressSnapshot;
import com.agentx.agentxbackend.query.domain.model.RunTimelineView;
import com.agentx.agentxbackend.query.domain.model.SessionProgressView;
import com.agentx.agentxbackend.query.domain.model.TaskBoardView;
import com.agentx.agentxbackend.query.domain.model.TicketInboxView;
import com.agentx.agentxbackend.session.application.port.in.SessionCompletionReadinessUseCase;
import com.agentx.agentxbackend.session.application.port.in.SessionHistoryQueryUseCase;
import com.agentx.agentxbackend.session.application.query.SessionCompletionReadiness;
import com.agentx.agentxbackend.session.application.query.SessionHistoryView;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;
import java.util.NoSuchElementException;

@Service
public class ProgressQueryService implements ProgressQueryUseCase {

    private final ProgressReadRepository progressReadRepository;
    private final SessionHistoryQueryUseCase sessionHistoryQueryUseCase;
    private final SessionCompletionReadinessUseCase sessionCompletionReadinessUseCase;

    public ProgressQueryService(
        ProgressReadRepository progressReadRepository,
        SessionHistoryQueryUseCase sessionHistoryQueryUseCase,
        SessionCompletionReadinessUseCase sessionCompletionReadinessUseCase
    ) {
        this.progressReadRepository = progressReadRepository;
        this.sessionHistoryQueryUseCase = sessionHistoryQueryUseCase;
        this.sessionCompletionReadinessUseCase = sessionCompletionReadinessUseCase;
    }

    @Override
    @Transactional(readOnly = true)
    public SessionProgressView getSessionProgress(String sessionId) {
        String normalizedSessionId = requireSessionId(sessionId);
        SessionHistoryView sessionHistory = loadSessionHistory(normalizedSessionId);
        SessionProgressSnapshot snapshot = progressReadRepository.getSessionProgressSnapshot(normalizedSessionId);
        SessionCompletionReadiness readiness = sessionCompletionReadinessUseCase.getCompletionReadiness(normalizedSessionId);

        SessionProgressView.RequirementSummary requirement = toRequirementSummary(sessionHistory.currentRequirementDoc());
        SessionProgressView.DeliverySummary delivery = new SessionProgressView.DeliverySummary(
            readiness.hasDeliveryTagOnMain(),
            snapshot.delivery().deliveredTaskCount(),
            snapshot.delivery().doneTaskCount(),
            snapshot.delivery().latestDeliveryTaskId(),
            snapshot.delivery().latestDeliveryCommit(),
            snapshot.delivery().latestVerifyRunId(),
            snapshot.delivery().latestVerifyStatus()
        );

        String phase = derivePhase(sessionHistory, requirement, snapshot, delivery, readiness);
        String blockerSummary = deriveBlockerSummary(requirement, snapshot, delivery, readiness, phase);
        String primaryAction = derivePrimaryAction(requirement, snapshot, delivery, readiness, phase);

        return new SessionProgressView(
            sessionHistory.sessionId(),
            sessionHistory.title(),
            sessionHistory.status(),
            phase,
            blockerSummary,
            primaryAction,
            requirement,
            snapshot.taskCounts(),
            snapshot.ticketCounts(),
            snapshot.runCounts(),
            snapshot.latestRun(),
            delivery,
            readiness.canComplete(),
            readiness.blockers(),
            sessionHistory.createdAt(),
            sessionHistory.updatedAt()
        );
    }

    @Override
    @Transactional(readOnly = true)
    public TicketInboxView getTicketInbox(String sessionId, String status) {
        String normalizedSessionId = requireSessionId(sessionId);
        loadSessionHistory(normalizedSessionId);
        return progressReadRepository.getTicketInbox(normalizedSessionId, normalizeTicketStatus(status));
    }

    @Override
    @Transactional(readOnly = true)
    public TaskBoardView getTaskBoard(String sessionId) {
        String normalizedSessionId = requireSessionId(sessionId);
        loadSessionHistory(normalizedSessionId);
        return progressReadRepository.getTaskBoard(normalizedSessionId);
    }

    @Override
    @Transactional(readOnly = true)
    public RunTimelineView getRunTimeline(String sessionId, int limit) {
        String normalizedSessionId = requireSessionId(sessionId);
        loadSessionHistory(normalizedSessionId);
        int cappedLimit = limit <= 0 ? 40 : Math.min(limit, 200);
        return progressReadRepository.getRunTimeline(normalizedSessionId, cappedLimit);
    }

    private SessionHistoryView loadSessionHistory(String sessionId) {
        return sessionHistoryQueryUseCase.findSessionWithCurrentRequirementDoc(sessionId)
            .orElseThrow(() -> new NoSuchElementException("Session not found: " + sessionId));
    }

    private static SessionProgressView.RequirementSummary toRequirementSummary(
        SessionHistoryView.CurrentRequirementDoc currentRequirementDoc
    ) {
        if (currentRequirementDoc == null) {
            return null;
        }
        return new SessionProgressView.RequirementSummary(
            currentRequirementDoc.docId(),
            currentRequirementDoc.currentVersion(),
            currentRequirementDoc.confirmedVersion(),
            currentRequirementDoc.status(),
            currentRequirementDoc.title(),
            currentRequirementDoc.updatedAt()
        );
    }

    private static String derivePhase(
        SessionHistoryView sessionHistory,
        SessionProgressView.RequirementSummary requirement,
        SessionProgressSnapshot snapshot,
        SessionProgressView.DeliverySummary delivery,
        SessionCompletionReadiness readiness
    ) {
        if ("COMPLETED".equalsIgnoreCase(sessionHistory.status())) {
            return "COMPLETED";
        }
        if (snapshot.ticketCounts().waitingUser() > 0) {
            return "WAITING_USER";
        }
        if (requirement == null || requirement.currentVersion() <= 0) {
            return "DRAFTING";
        }
        if (!"CONFIRMED".equalsIgnoreCase(requirement.status()) || requirement.confirmedVersion() == null) {
            return "REVIEWING";
        }
        if (readiness.canComplete()
            || delivery.deliveryTagPresent()
            || (snapshot.taskCounts().total() > 0
                && snapshot.taskCounts().done() == snapshot.taskCounts().total()
                && snapshot.runCounts().running() == 0
                && snapshot.runCounts().waitingForeman() == 0)) {
            return "DELIVERED";
        }
        return "EXECUTING";
    }

    private static String deriveBlockerSummary(
        SessionProgressView.RequirementSummary requirement,
        SessionProgressSnapshot snapshot,
        SessionProgressView.DeliverySummary delivery,
        SessionCompletionReadiness readiness,
        String phase
    ) {
        if ("COMPLETED".equals(phase)) {
            return "Session completed.";
        }
        if (snapshot.ticketCounts().waitingUser() > 0) {
            return "Waiting for user response on %d ticket(s).".formatted(snapshot.ticketCounts().waitingUser());
        }
        if (requirement == null) {
            return "Requirement draft has not been created.";
        }
        if (!"CONFIRMED".equalsIgnoreCase(requirement.status()) || requirement.confirmedVersion() == null) {
            return "Requirement is waiting for confirmation.";
        }
        if (snapshot.runCounts().running() > 0) {
            return "Worker execution is in progress.";
        }
        if (snapshot.runCounts().waitingForeman() > 0) {
            return "Run is waiting for foreman triage.";
        }
        if (snapshot.taskCounts().waitingDependency() > 0) {
            return "Tasks are waiting for upstream dependencies.";
        }
        if (snapshot.taskCounts().waitingWorker() > 0) {
            return "Tasks are waiting for eligible workers.";
        }
        if (snapshot.taskCounts().readyForAssign() > 0) {
            return "Tasks are ready for assignment.";
        }
        if (snapshot.taskCounts().delivered() > 0 && !delivery.deliveryTagPresent()) {
            return "Delivered tasks are waiting for merge gate completion.";
        }
        if (readiness.canComplete()) {
            return "Session is ready to complete.";
        }
        if (!readiness.blockers().isEmpty()) {
            return readiness.blockers().get(0);
        }
        return "No active blockers.";
    }

    private static String derivePrimaryAction(
        SessionProgressView.RequirementSummary requirement,
        SessionProgressSnapshot snapshot,
        SessionProgressView.DeliverySummary delivery,
        SessionCompletionReadiness readiness,
        String phase
    ) {
        if ("COMPLETED".equals(phase)) {
            return "View delivery";
        }
        if (requirement == null) {
            return "Generate requirement draft";
        }
        if (!"CONFIRMED".equalsIgnoreCase(requirement.status()) || requirement.confirmedVersion() == null) {
            return "Confirm requirement";
        }
        if (snapshot.ticketCounts().waitingUser() > 0) {
            return "Respond to ticket";
        }
        if (snapshot.runCounts().running() > 0
            || snapshot.runCounts().waitingForeman() > 0
            || snapshot.taskCounts().assigned() > 0) {
            return "Review task progress";
        }
        if (delivery.deliveryTagPresent()
            || snapshot.taskCounts().delivered() > 0
            || snapshot.taskCounts().done() > 0
            || readiness.canComplete()) {
            return "Review delivery";
        }
        return "Review overview";
    }

    private static String normalizeTicketStatus(String status) {
        if (status == null || status.isBlank()) {
            return null;
        }
        return switch (status.trim().toUpperCase(Locale.ROOT)) {
            case "OPEN", "IN_PROGRESS", "WAITING_USER", "DONE", "BLOCKED" -> status.trim().toUpperCase(Locale.ROOT);
            default -> throw new IllegalArgumentException("Unsupported ticket status: " + status);
        };
    }

    private static String requireSessionId(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("sessionId must not be blank");
        }
        return sessionId.trim();
    }
}
