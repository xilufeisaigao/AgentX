package com.agentx.agentxbackend.process.application;

import com.agentx.agentxbackend.planning.application.port.in.PlanningCommandUseCase;
import com.agentx.agentxbackend.planning.domain.model.TaskStatus;
import com.agentx.agentxbackend.planning.domain.model.TaskTemplateId;
import com.agentx.agentxbackend.planning.domain.model.WorkModule;
import com.agentx.agentxbackend.planning.domain.model.WorkTask;
import com.agentx.agentxbackend.session.domain.event.SessionCreatedEvent;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class SessionBootstrapInitProcessManagerTest {

    @Test
    void shouldCreateBootstrapInitTaskWithoutCompilingContextsBeforeRequirementConfirmed() {
        PlanningCommandUseCase planning = mock(PlanningCommandUseCase.class);

        SessionBootstrapInitProcessManager manager = new SessionBootstrapInitProcessManager(
            planning,
            new ObjectMapper(),
            "bootstrap",
            "bootstrap module",
            "init baseline",
            "tmpl.init.v0",
            "[\"TP-GIT-2\",\"TP-JAVA-21\"]"
        );

        WorkModule module = new WorkModule(
            "MOD-1",
            "SES-1",
            "bootstrap",
            "bootstrap module",
            Instant.now(),
            Instant.now()
        );
        WorkTask task = new WorkTask(
            "TASK-1",
            "MOD-1",
            "init baseline",
            TaskTemplateId.TMPL_INIT_V0,
            TaskStatus.WAITING_WORKER,
            "[\"TP-GIT-2\",\"TP-JAVA-21\"]",
            null,
            "architect_agent",
            Instant.now(),
            Instant.now()
        );
        when(planning.createModule("SES-1", "bootstrap", "bootstrap module")).thenReturn(module);
        when(planning.createTask(
            eq("MOD-1"),
            eq("init baseline"),
            eq("tmpl.init.v0"),
            eq("[\"TP-GIT-2\",\"TP-JAVA-21\"]"),
            eq(List.of())
        )).thenReturn(task);

        manager.handle(new SessionCreatedEvent("SES-1", "session title"));

        verify(planning).createModule("SES-1", "bootstrap", "bootstrap module");
        verify(planning).createTask("MOD-1", "init baseline", "tmpl.init.v0", "[\"TP-GIT-2\",\"TP-JAVA-21\"]", List.of());
        verifyNoMoreInteractions(planning);
    }

    @Test
    void shouldRejectNonInitTemplateConfiguration() {
        PlanningCommandUseCase planning = mock(PlanningCommandUseCase.class);
        assertThrows(IllegalArgumentException.class, () -> new SessionBootstrapInitProcessManager(
            planning,
            new ObjectMapper(),
            "bootstrap",
            "bootstrap module",
            "init baseline",
            "tmpl.impl.v0",
            "[\"TP-GIT-2\"]"
        ));
    }
}
