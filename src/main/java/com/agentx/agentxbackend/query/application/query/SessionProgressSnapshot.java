package com.agentx.agentxbackend.query.application.query;

import com.agentx.agentxbackend.query.domain.model.SessionProgressView;

public record SessionProgressSnapshot(
    SessionProgressView.TaskCounts taskCounts,
    SessionProgressView.TicketCounts ticketCounts,
    SessionProgressView.RunCounts runCounts,
    SessionProgressView.LatestRun latestRun,
    SessionProgressView.DeliverySummary delivery
) {
}
