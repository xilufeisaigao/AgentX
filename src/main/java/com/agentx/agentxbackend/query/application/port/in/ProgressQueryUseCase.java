package com.agentx.agentxbackend.query.application.port.in;

import com.agentx.agentxbackend.query.domain.model.RunTimelineView;
import com.agentx.agentxbackend.query.domain.model.SessionProgressView;
import com.agentx.agentxbackend.query.domain.model.TaskBoardView;
import com.agentx.agentxbackend.query.domain.model.TicketInboxView;

public interface ProgressQueryUseCase {

    SessionProgressView getSessionProgress(String sessionId);

    TicketInboxView getTicketInbox(String sessionId, String status);

    TaskBoardView getTaskBoard(String sessionId);

    RunTimelineView getRunTimeline(String sessionId, int limit);
}
