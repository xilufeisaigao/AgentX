package com.agentx.agentxbackend.query.application;

import com.agentx.agentxbackend.query.application.port.in.ProgressQueryUseCase;
import com.agentx.agentxbackend.query.application.port.out.ProgressReadRepository;
import com.agentx.agentxbackend.query.domain.model.RunTimelineView;
import com.agentx.agentxbackend.query.domain.model.SessionProgressView;
import com.agentx.agentxbackend.query.domain.model.TaskBoardView;
import com.agentx.agentxbackend.query.domain.model.TicketInboxView;

import java.util.List;

public class ProgressQueryService implements ProgressQueryUseCase {

    private final ProgressReadRepository progressReadRepository;

    public ProgressQueryService(ProgressReadRepository progressReadRepository) {
        this.progressReadRepository = progressReadRepository;
    }

    @Override
    public SessionProgressView getSessionProgress(String sessionId) {
        return progressReadRepository.getSessionProgress(sessionId);
    }

    @Override
    public List<TicketInboxView> getTicketInbox(String sessionId, String status) {
        return progressReadRepository.getTicketInbox(sessionId, status);
    }

    @Override
    public List<TaskBoardView> getTaskBoard(String sessionId) {
        return progressReadRepository.getTaskBoard(sessionId);
    }

    @Override
    public List<RunTimelineView> getRunTimeline(String sessionId) {
        return progressReadRepository.getRunTimeline(sessionId);
    }
}
