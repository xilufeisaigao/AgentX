package com.agentx.agentxbackend.query.application.port.out;

import com.agentx.agentxbackend.query.application.query.SessionProgressSnapshot;
import com.agentx.agentxbackend.query.domain.model.RunTimelineView;
import com.agentx.agentxbackend.query.domain.model.TaskBoardView;
import com.agentx.agentxbackend.query.domain.model.TicketInboxView;

public interface ProgressReadRepository {

    SessionProgressSnapshot getSessionProgressSnapshot(String sessionId);

    TicketInboxView getTicketInbox(String sessionId, String status);

    TaskBoardView getTaskBoard(String sessionId);

    RunTimelineView getRunTimeline(String sessionId, int limit);
}
