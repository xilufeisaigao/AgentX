package com.agentx.agentxbackend.query.application.port.in;

import com.agentx.agentxbackend.query.domain.model.RunTimelineView;
import com.agentx.agentxbackend.query.domain.model.SessionProgressView;
import com.agentx.agentxbackend.query.domain.model.TaskBoardView;
import com.agentx.agentxbackend.query.domain.model.TicketInboxView;

import java.util.List;

public interface ProgressQueryUseCase {

    SessionProgressView getSessionProgress(String sessionId);

    List<TicketInboxView> getTicketInbox(String sessionId, String status);

    List<TaskBoardView> getTaskBoard(String sessionId);

    List<RunTimelineView> getRunTimeline(String sessionId);
}
