package com.agentx.agentxbackend.process.application;

import com.agentx.agentxbackend.contextpack.application.port.in.ContextCompileUseCase;
import com.agentx.agentxbackend.contextpack.domain.model.TaskContextPack;
import com.agentx.agentxbackend.planning.application.port.in.PlanningCommandUseCase;
import com.agentx.agentxbackend.planning.domain.model.TaskStatus;
import com.agentx.agentxbackend.planning.domain.model.TaskTemplateId;
import com.agentx.agentxbackend.planning.domain.model.WorkModule;
import com.agentx.agentxbackend.planning.domain.model.WorkTask;
import com.agentx.agentxbackend.process.application.port.out.ArchitectTaskBreakdownGeneratorPort;
import com.agentx.agentxbackend.ticket.domain.model.Ticket;
import com.agentx.agentxbackend.ticket.domain.model.TicketStatus;
import com.agentx.agentxbackend.ticket.domain.model.TicketType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ArchitectWorkPlanningServiceTest {

    @Mock
    private PlanningCommandUseCase planningCommandUseCase;
    @Mock
    private ContextCompileUseCase contextCompileUseCase;
    @Mock
    private ArchitectTaskBreakdownGeneratorPort breakdownGeneratorPort;

    @Test
    void planAndPersistShouldCreateModulesAndTasks() {
        ArchitectWorkPlanningService service = new ArchitectWorkPlanningService(
            planningCommandUseCase,
            contextCompileUseCase,
            breakdownGeneratorPort,
            new ObjectMapper()
        );
        Ticket ticket = sampleTicket("TCK-PLAN-1");
        ArchitectTaskBreakdownGeneratorPort.BreakdownPlan plan = new ArchitectTaskBreakdownGeneratorPort.BreakdownPlan(
            "plan summary",
            List.of(
                new ArchitectTaskBreakdownGeneratorPort.ModulePlan(
                    "order-core",
                    "order core module",
                    List.of(
                        new ArchitectTaskBreakdownGeneratorPort.TaskPlan(
                            "order_api_impl",
                            "implement order api",
                            "tmpl.impl.v0",
                            List.of("TP-JAVA-21", "TP-MAVEN-3", "TP-GIT-2"),
                            List.of(),
                            "core api"
                        )
                    )
                )
            ),
            "mock",
            "qwen3.5-plus-2026-02-15"
        );
        when(breakdownGeneratorPort.generate(any())).thenReturn(plan);
        when(planningCommandUseCase.createModule(eq("SES-1"), eq("order-core"), eq("order core module")))
            .thenReturn(
                new WorkModule(
                    "MOD-1",
                    "SES-1",
                    "order-core",
                    "order core module",
                    Instant.parse("2026-02-22T00:00:00Z"),
                    Instant.parse("2026-02-22T00:00:00Z")
                )
            );
        when(planningCommandUseCase.createTask(eq("MOD-1"), eq("implement order api"), eq("tmpl.impl.v0"), any(), anyList()))
            .thenReturn(
                new WorkTask(
                    "TASK-1",
                    "MOD-1",
                    "implement order api",
                    TaskTemplateId.TMPL_IMPL_V0,
                    TaskStatus.READY_FOR_ASSIGN,
                    "[\"TP-JAVA-21\",\"TP-MAVEN-3\",\"TP-GIT-2\"]",
                    null,
                    "architect_agent",
                    Instant.parse("2026-02-22T00:00:00Z"),
                    Instant.parse("2026-02-22T00:00:00Z")
                )
            );
        when(contextCompileUseCase.compileTaskContextPack("TASK-1", "IMPL", "TICKET_DONE"))
            .thenReturn(
                new TaskContextPack(
                    "CTXS-1",
                    "TASK-1",
                    "IMPL",
                    "req:REQ-1@v1",
                    List.of("ticket:TCK-PLAN-1"),
                    "module:MOD-1",
                    List.of(),
                    "git:BASELINE_UNAVAILABLE",
                    List.of()
                )
            );

        ArchitectWorkPlanningService.PlanResult result = service.planAndPersist(
            ticket,
            "requirement markdown",
            List.of()
        );

        assertEquals("plan summary", result.summary());
        assertEquals(1, result.createdModules().size());
        assertEquals("MOD-1", result.createdModules().get(0).moduleId());
        assertEquals(1, result.createdModules().get(0).createdTasks().size());
        assertEquals("TASK-1", result.createdModules().get(0).createdTasks().get(0).taskId());

        ArgumentCaptor<String> requiredToolpacksJsonCaptor = ArgumentCaptor.forClass(String.class);
        verify(planningCommandUseCase, times(1)).createTask(
            eq("MOD-1"),
            eq("implement order api"),
            eq("tmpl.impl.v0"),
            requiredToolpacksJsonCaptor.capture(),
            eq(List.of())
        );
        assertTrue(requiredToolpacksJsonCaptor.getValue().contains("TP-JAVA-21"));
        verify(planningCommandUseCase, times(0)).addTaskDependency(any(), any(), any());
    }

    @Test
    void planAndPersistShouldCreateTaskDependenciesFromTaskKeys() {
        ArchitectWorkPlanningService service = new ArchitectWorkPlanningService(
            planningCommandUseCase,
            contextCompileUseCase,
            breakdownGeneratorPort,
            new ObjectMapper()
        );
        Ticket ticket = sampleTicket("TCK-PLAN-DEP");
        when(breakdownGeneratorPort.generate(any())).thenReturn(
            new ArchitectTaskBreakdownGeneratorPort.BreakdownPlan(
                "dependency plan",
                List.of(
                    new ArchitectTaskBreakdownGeneratorPort.ModulePlan(
                        "order-core",
                        "module with dependency",
                        List.of(
                            new ArchitectTaskBreakdownGeneratorPort.TaskPlan(
                                "order_impl",
                                "implement order API",
                                "tmpl.impl.v0",
                                List.of("TP-JAVA-21"),
                                List.of(),
                                "impl"
                            ),
                            new ArchitectTaskBreakdownGeneratorPort.TaskPlan(
                                "order_test",
                                "test order API",
                                "tmpl.test.v0",
                                List.of("TP-JAVA-21"),
                                List.of("order_impl"),
                                "test"
                            )
                        )
                    )
                ),
                "mock",
                "qwen3.5-plus-2026-02-15"
            )
        );
        when(planningCommandUseCase.createModule(eq("SES-1"), eq("order-core"), eq("module with dependency")))
            .thenReturn(
                new WorkModule(
                    "MOD-DEP",
                    "SES-1",
                    "order-core",
                    "module with dependency",
                    Instant.parse("2026-02-22T00:00:00Z"),
                    Instant.parse("2026-02-22T00:00:00Z")
                )
            );
        when(planningCommandUseCase.createTask(eq("MOD-DEP"), eq("implement order API"), eq("tmpl.impl.v0"), any(), anyList()))
            .thenReturn(
                new WorkTask(
                    "TASK-IMPL",
                    "MOD-DEP",
                    "implement order API",
                    TaskTemplateId.TMPL_IMPL_V0,
                    TaskStatus.WAITING_WORKER,
                    "[\"TP-JAVA-21\"]",
                    null,
                    "architect_agent",
                    Instant.parse("2026-02-22T00:00:00Z"),
                    Instant.parse("2026-02-22T00:00:00Z")
                )
            );
        when(planningCommandUseCase.createTask(eq("MOD-DEP"), eq("test order API"), eq("tmpl.test.v0"), any(), anyList()))
            .thenReturn(
                new WorkTask(
                    "TASK-TEST",
                    "MOD-DEP",
                    "test order API",
                    TaskTemplateId.TMPL_TEST_V0,
                    TaskStatus.WAITING_WORKER,
                    "[\"TP-JAVA-21\"]",
                    null,
                    "architect_agent",
                    Instant.parse("2026-02-22T00:00:00Z"),
                    Instant.parse("2026-02-22T00:00:00Z")
                )
            );
        when(contextCompileUseCase.compileTaskContextPack(eq("TASK-IMPL"), eq("IMPL"), eq("TICKET_DONE")))
            .thenReturn(new TaskContextPack(
                "CTXS-IMPL",
                "TASK-IMPL",
                "IMPL",
                "req:REQ-1@v1",
                List.of(),
                "module:MOD-DEP",
                List.of(),
                "git:BASELINE_UNAVAILABLE",
                List.of()
            ));
        when(contextCompileUseCase.compileTaskContextPack(eq("TASK-TEST"), eq("IMPL"), eq("TICKET_DONE")))
            .thenReturn(new TaskContextPack(
                "CTXS-TEST",
                "TASK-TEST",
                "IMPL",
                "req:REQ-1@v1",
                List.of(),
                "module:MOD-DEP",
                List.of(),
                "git:BASELINE_UNAVAILABLE",
                List.of()
            ));

        ArchitectWorkPlanningService.PlanResult result = service.planAndPersist(ticket, "", List.of());

        assertEquals(1, result.createdModules().size());
        assertEquals(2, result.createdModules().get(0).createdTasks().size());
        assertEquals("order_impl", result.createdModules().get(0).createdTasks().get(0).taskKey());
        assertEquals("order_test", result.createdModules().get(0).createdTasks().get(1).taskKey());
        assertEquals(
            List.of("TASK-IMPL"),
            result.createdModules().get(0).createdTasks().get(1).dependsOnTaskIds()
        );

        verify(planningCommandUseCase, times(1)).createModule(eq("SES-1"), eq("order-core"), eq("module with dependency"));
        verify(planningCommandUseCase, times(2)).createTask(eq("MOD-DEP"), any(), any(), any(), anyList());
        verify(planningCommandUseCase, times(1)).addTaskDependency("TASK-TEST", "TASK-IMPL", "DONE");
        verify(contextCompileUseCase, times(2)).compileTaskContextPack(any(), eq("IMPL"), eq("TICKET_DONE"));
    }

    @Test
    void planAndPersistShouldKeepPlanningWhenDependsOnKeyCannotBeResolved() {
        ArchitectWorkPlanningService service = new ArchitectWorkPlanningService(
            planningCommandUseCase,
            contextCompileUseCase,
            breakdownGeneratorPort,
            new ObjectMapper()
        );
        Ticket ticket = sampleTicket("TCK-PLAN-UNRESOLVED");
        when(breakdownGeneratorPort.generate(any())).thenReturn(
            new ArchitectTaskBreakdownGeneratorPort.BreakdownPlan(
                "partial dependency plan",
                List.of(
                    new ArchitectTaskBreakdownGeneratorPort.ModulePlan(
                        "order-core",
                        "module with unresolved dependency key",
                        List.of(
                            new ArchitectTaskBreakdownGeneratorPort.TaskPlan(
                                "order_impl",
                                "implement order API",
                                "tmpl.impl.v0",
                                List.of("TP-JAVA-21"),
                                List.of(),
                                "impl"
                            ),
                            new ArchitectTaskBreakdownGeneratorPort.TaskPlan(
                                "order_test",
                                "test order API",
                                "tmpl.test.v0",
                                List.of("TP-JAVA-21"),
                                List.of("order_impl", "missing_task"),
                                "test"
                            )
                        )
                    )
                ),
                "bailian",
                "qwen3.5-plus-2026-02-15"
            )
        );
        when(planningCommandUseCase.createModule(eq("SES-1"), eq("order-core"), eq("module with unresolved dependency key")))
            .thenReturn(
                new WorkModule(
                    "MOD-UNRESOLVED",
                    "SES-1",
                    "order-core",
                    "module with unresolved dependency key",
                    Instant.parse("2026-02-22T00:00:00Z"),
                    Instant.parse("2026-02-22T00:00:00Z")
                )
            );
        when(planningCommandUseCase.createTask(eq("MOD-UNRESOLVED"), eq("implement order API"), eq("tmpl.impl.v0"), any(), anyList()))
            .thenReturn(
                new WorkTask(
                    "TASK-IMPL-U",
                    "MOD-UNRESOLVED",
                    "implement order API",
                    TaskTemplateId.TMPL_IMPL_V0,
                    TaskStatus.WAITING_WORKER,
                    "[\"TP-JAVA-21\"]",
                    null,
                    "architect_agent",
                    Instant.parse("2026-02-22T00:00:00Z"),
                    Instant.parse("2026-02-22T00:00:00Z")
                )
            );
        when(planningCommandUseCase.createTask(eq("MOD-UNRESOLVED"), eq("test order API"), eq("tmpl.test.v0"), any(), anyList()))
            .thenReturn(
                new WorkTask(
                    "TASK-TEST-U",
                    "MOD-UNRESOLVED",
                    "test order API",
                    TaskTemplateId.TMPL_TEST_V0,
                    TaskStatus.WAITING_WORKER,
                    "[\"TP-JAVA-21\"]",
                    null,
                    "architect_agent",
                    Instant.parse("2026-02-22T00:00:00Z"),
                    Instant.parse("2026-02-22T00:00:00Z")
                )
            );
        when(contextCompileUseCase.compileTaskContextPack(eq("TASK-IMPL-U"), eq("IMPL"), eq("TICKET_DONE")))
            .thenReturn(new TaskContextPack(
                "CTXS-IMPL-U",
                "TASK-IMPL-U",
                "IMPL",
                "req:REQ-1@v1",
                List.of(),
                "module:MOD-UNRESOLVED",
                List.of(),
                "git:BASELINE_UNAVAILABLE",
                List.of()
            ));
        when(contextCompileUseCase.compileTaskContextPack(eq("TASK-TEST-U"), eq("IMPL"), eq("TICKET_DONE")))
            .thenReturn(new TaskContextPack(
                "CTXS-TEST-U",
                "TASK-TEST-U",
                "IMPL",
                "req:REQ-1@v1",
                List.of(),
                "module:MOD-UNRESOLVED",
                List.of(),
                "git:BASELINE_UNAVAILABLE",
                List.of()
            ));

        ArchitectWorkPlanningService.PlanResult result = service.planAndPersist(ticket, "", List.of());

        assertEquals(1, result.createdModules().size());
        assertEquals(2, result.createdModules().get(0).createdTasks().size());
        ArchitectWorkPlanningService.CreatedTask createdTestTask = result.createdModules().get(0).createdTasks().get(1);
        assertEquals("order_test", createdTestTask.taskKey());
        assertEquals(List.of("TASK-IMPL-U"), createdTestTask.dependsOnTaskIds());
        assertEquals(List.of("missing_task"), createdTestTask.unresolvedDependsOnKeys());
        assertEquals("bailian", result.provider());

        verify(planningCommandUseCase, times(1)).addTaskDependency("TASK-TEST-U", "TASK-IMPL-U", "DONE");
        verify(contextCompileUseCase, times(2)).compileTaskContextPack(any(), eq("IMPL"), eq("TICKET_DONE"));
    }

    private static Ticket sampleTicket(String ticketId) {
        Instant now = Instant.parse("2026-02-22T00:00:00Z");
        return new Ticket(
            ticketId,
            "SES-1",
            TicketType.HANDOFF,
            TicketStatus.IN_PROGRESS,
            "Architecture review needed",
            "requirement_agent",
            "architect_agent",
            "REQ-1",
            1,
            "{\"kind\":\"handoff_packet\"}",
            "architect-agent-auto",
            now.plusSeconds(300),
            now,
            now
        );
    }
}

