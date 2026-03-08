package com.agentx.agentxbackend.session.application.query;

import java.util.List;

public record SessionCompletionReadiness(
    String sessionId,
    String sessionStatus,
    boolean canComplete,
    boolean hasUnfinishedTasks,
    boolean hasActiveRuns,
    boolean hasActionableTickets,
    boolean hasDeliveryTagOnMain,
    List<String> blockers
) {
}
