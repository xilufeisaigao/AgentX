package com.agentx.agentxbackend.query.application.port.out;

import com.agentx.agentxbackend.query.domain.model.RunTimelineView;
import com.agentx.agentxbackend.query.domain.model.SessionProgressView;
import com.agentx.agentxbackend.query.domain.model.TaskBoardView;
import com.agentx.agentxbackend.query.domain.model.TicketInboxView;

import java.util.List;

public interface ProgressReadRepository {

    SessionProgressView getSessionProgress(String sessionId);

    List<TicketInboxView> getTicketInbox(String sessionId, String status);

    List<TaskBoardView> getTaskBoard(String sessionId);

    List<RunTimelineView> getRunTimeline(String sessionId);
}
