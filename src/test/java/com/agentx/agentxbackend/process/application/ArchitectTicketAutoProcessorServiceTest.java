package com.agentx.agentxbackend.process.application;

import com.agentx.agentxbackend.process.application.port.out.ArchitectTicketProposalGeneratorPort;
import com.agentx.agentxbackend.contextpack.application.port.in.ContextCompileUseCase;
import com.agentx.agentxbackend.requirement.application.port.in.RequirementCurrentDoc;
import com.agentx.agentxbackend.requirement.application.port.in.RequirementDocQueryUseCase;
import com.agentx.agentxbackend.session.application.port.in.SessionHistoryQueryUseCase;
import com.agentx.agentxbackend.session.application.query.SessionHistoryView;
import com.agentx.agentxbackend.ticket.application.port.in.TicketCommandUseCase;
import com.agentx.agentxbackend.ticket.application.port.in.TicketQueryUseCase;
import com.agentx.agentxbackend.ticket.domain.model.Ticket;
import com.agentx.agentxbackend.ticket.domain.model.TicketEvent;
import com.agentx.agentxbackend.ticket.domain.model.TicketEventType;
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
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ArchitectTicketAutoProcessorServiceTest {

    @Mock
    private SessionHistoryQueryUseCase sessionHistoryQueryUseCase;
    @Mock
    private RequirementDocQueryUseCase requirementDocQueryUseCase;
    @Mock
    private TicketQueryUseCase ticketQueryUseCase;
    @Mock
    private TicketCommandUseCase ticketCommandUseCase;
    @Mock
    private ContextCompileUseCase contextCompileUseCase;
    @Mock
    private ArchitectTicketProposalGeneratorPort proposalGeneratorPort;
    @Mock
    private ArchitectWorkPlanningService architectWorkPlanningService;

    @Test
    void processOpenArchitectTicketsShouldClaimAndAppendDecisionRequested() {
        ArchitectTicketAutoProcessorService service = new ArchitectTicketAutoProcessorService(
            sessionHistoryQueryUseCase,
            requirementDocQueryUseCase,
            ticketQueryUseCase,
            ticketCommandUseCase,
            contextCompileUseCase,
            proposalGeneratorPort,
            architectWorkPlanningService,
            new ObjectMapper(),
            "architect-auto-test",
            300,
            "",
            1,
            0,
            12
        );

        SessionHistoryView session = new SessionHistoryView(
            "SES-1",
            "s",
            "ACTIVE",
            Instant.parse("2026-02-21T00:00:00Z"),
            Instant.parse("2026-02-21T00:00:00Z"),
            null
        );
        Ticket handoff = sampleTicket("TCK-1", TicketType.HANDOFF, TicketStatus.OPEN);

        when(sessionHistoryQueryUseCase.listSessionsWithCurrentRequirementDoc()).thenReturn(List.of(session));
        when(ticketQueryUseCase.listBySession("SES-1", null, "architect_agent", null))
            .thenReturn(List.of(handoff));
        when(ticketQueryUseCase.listEvents("TCK-1"))
            .thenReturn(List.of(
                new TicketEvent(
                    "TEV-BASE-1",
                    "TCK-1",
                    TicketEventType.COMMENT,
                    "requirement_agent",
                    "handoff created",
                    "{}",
                    Instant.parse("2026-02-21T00:00:00Z")
                )
            ));
        when(requirementDocQueryUseCase.findCurrentBySessionId("SES-1"))
            .thenReturn(java.util.Optional.of(
                new RequirementCurrentDoc(
                    "REQ-1",
                    2,
                    2,
                    "CONFIRMED",
                    "title",
                    "requirement markdown content",
                    Instant.parse("2026-02-21T00:00:00Z")
                )
            ));
        when(ticketCommandUseCase.claimTicket("TCK-1", "architect-auto-test", 300)).thenReturn(handoff);
        when(proposalGeneratorPort.generate(any()))
            .thenReturn(
                new ArchitectTicketProposalGeneratorPort.Proposal(
                    "DECISION",
                    "Choose A or B?",
                    List.of("ctx"),
                    List.of(
                        new ArchitectTicketProposalGeneratorPort.DecisionOption(
                            "OPT-A",
                            "Option A",
                            List.of("pro"),
                            List.of("con"),
                            List.of("risk"),
                            List.of("cost")
                        ),
                        new ArchitectTicketProposalGeneratorPort.DecisionOption(
                            "OPT-B",
                            "Option B",
                            List.of("pro2"),
                            List.of("con2"),
                            List.of("risk2"),
                            List.of("cost2")
                        )
                    ),
                    new ArchitectTicketProposalGeneratorPort.Recommendation("OPT-A", "reason"),
                    "summary",
                    "mock",
                    "qwen3.5-plus-2026-02-15"
                )
            );
        when(ticketCommandUseCase.appendEvent(any(), any(), any(), any(), any()))
            .thenReturn(
                new TicketEvent(
                    "TEV-1",
                    "TCK-1",
                    TicketEventType.COMMENT,
                    "architect_agent",
                    "summary",
                    "{}",
                    Instant.parse("2026-02-21T00:00:00Z")
                )
            );

        ArchitectTicketAutoProcessorService.AutoProcessResult result = service.processOpenArchitectTickets(null, 8);

        assertEquals(1, result.processedCount());
        assertEquals(List.of("TCK-1"), result.processedTicketIds());
        verify(ticketCommandUseCase).claimTicket("TCK-1", "architect-auto-test", 300);
        ArgumentCaptor<ArchitectTicketProposalGeneratorPort.GenerateInput> inputCaptor =
            ArgumentCaptor.forClass(ArchitectTicketProposalGeneratorPort.GenerateInput.class);
        verify(proposalGeneratorPort).generate(inputCaptor.capture());
        assertEquals("requirement markdown content", inputCaptor.getValue().requirementDocContent());
        assertEquals(1, inputCaptor.getValue().recentEvents().size());

        ArgumentCaptor<String> eventTypeCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> bodyCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> dataCaptor = ArgumentCaptor.forClass(String.class);
        verify(ticketCommandUseCase, org.mockito.Mockito.times(2)).appendEvent(
            eq("TCK-1"),
            eq("architect_agent"),
            eventTypeCaptor.capture(),
            bodyCaptor.capture(),
            dataCaptor.capture()
        );
        List<String> types = eventTypeCaptor.getAllValues();
        assertTrue(types.contains("COMMENT"));
        assertTrue(types.contains("DECISION_REQUESTED"));
        assertTrue(dataCaptor.getAllValues().stream().anyMatch(v -> v.contains("\"request_kind\":\"DECISION\"")));
    }

    @Test
    void processOpenArchitectTicketsShouldAutoRespondWhenOnlyOneRecommendedOptionExists() {
        ArchitectTicketAutoProcessorService service = new ArchitectTicketAutoProcessorService(
            sessionHistoryQueryUseCase,
            requirementDocQueryUseCase,
            ticketQueryUseCase,
            ticketCommandUseCase,
            contextCompileUseCase,
            proposalGeneratorPort,
            architectWorkPlanningService,
            new ObjectMapper(),
            "architect-auto-test",
            300,
            "",
            1,
            0,
            12
        );

        Ticket handoff = sampleTicket("TCK-AUTO-1", TicketType.HANDOFF, TicketStatus.OPEN);
        when(ticketQueryUseCase.listBySession("SES-1", null, "architect_agent", null))
            .thenReturn(List.of(handoff));
        when(ticketQueryUseCase.listEvents("TCK-AUTO-1")).thenReturn(List.of());
        when(requirementDocQueryUseCase.findCurrentBySessionId("SES-1")).thenReturn(confirmedRequirementDoc());
        when(ticketCommandUseCase.claimTicket("TCK-AUTO-1", "architect-auto-test", 300)).thenReturn(handoff);
        when(proposalGeneratorPort.generate(any()))
            .thenReturn(
                new ArchitectTicketProposalGeneratorPort.Proposal(
                    "DECISION",
                    "Choose the project structure?",
                    List.of("ctx"),
                    List.of(
                        new ArchitectTicketProposalGeneratorPort.DecisionOption(
                            "OPT-A",
                            "Single-module Spring Boot structure",
                            List.of("simple"),
                            List.of("none"),
                            List.of("low"),
                            List.of("low")
                        )
                    ),
                    new ArchitectTicketProposalGeneratorPort.Recommendation("OPT-A", "Only viable option"),
                    "Only one viable option remains.",
                    "mock",
                    "qwen3.5-plus-2026-02-15"
                )
            );
        when(ticketCommandUseCase.appendEvent(any(), any(), any(), any(), any()))
            .thenReturn(
                new TicketEvent(
                    "TEV-AUTO-1",
                    "TCK-AUTO-1",
                    TicketEventType.COMMENT,
                    "architect_agent",
                    "summary",
                    "{}",
                    Instant.parse("2026-02-21T00:00:00Z")
                )
            );

        ArchitectTicketAutoProcessorService.AutoProcessResult result = service.processOpenArchitectTickets("SES-1", 8);

        assertEquals(1, result.processedCount());
        ArgumentCaptor<String> eventTypeCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> bodyCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> dataCaptor = ArgumentCaptor.forClass(String.class);
        verify(ticketCommandUseCase, org.mockito.Mockito.times(3)).appendEvent(
            eq("TCK-AUTO-1"),
            eq("architect_agent"),
            eventTypeCaptor.capture(),
            bodyCaptor.capture(),
            dataCaptor.capture()
        );
        assertEquals(List.of("COMMENT", "DECISION_REQUESTED", "USER_RESPONDED"), eventTypeCaptor.getAllValues());
        assertTrue(bodyCaptor.getAllValues().get(2).contains("Auto-adopted recommendation"));
        assertTrue(dataCaptor.getAllValues().get(2).contains("\"auto_selected\":true"));
        verify(architectWorkPlanningService, never()).planAndPersist(any(), any(), any());
    }

    @Test
    void processOpenArchitectTicketsShouldAutoRespondForArchReviewRecommendationWithMultipleOptions() {
        ArchitectTicketAutoProcessorService service = new ArchitectTicketAutoProcessorService(
            sessionHistoryQueryUseCase,
            requirementDocQueryUseCase,
            ticketQueryUseCase,
            ticketCommandUseCase,
            contextCompileUseCase,
            proposalGeneratorPort,
            architectWorkPlanningService,
            new ObjectMapper(),
            "architect-auto-test",
            300,
            "",
            1,
            0,
            12
        );

        Ticket archReview = sampleTicket("TCK-ARCH-AUTO-1", TicketType.ARCH_REVIEW, TicketStatus.OPEN);
        when(ticketQueryUseCase.listBySession("SES-1", null, "architect_agent", null))
            .thenReturn(List.of(archReview));
        when(ticketQueryUseCase.listEvents("TCK-ARCH-AUTO-1")).thenReturn(List.of());
        when(requirementDocQueryUseCase.findCurrentBySessionId("SES-1")).thenReturn(confirmedRequirementDoc());
        when(ticketCommandUseCase.claimTicket("TCK-ARCH-AUTO-1", "architect-auto-test", 300)).thenReturn(archReview);
        when(proposalGeneratorPort.generate(any()))
            .thenReturn(
                new ArchitectTicketProposalGeneratorPort.Proposal(
                    "DECISION",
                    "Choose the Spring Boot baseline?",
                    List.of("Requirement already mandates Spring Boot 3.x and Maven."),
                    List.of(
                        new ArchitectTicketProposalGeneratorPort.DecisionOption(
                            "OPT-A",
                            "Use spring-boot-starter-parent managed dependencies",
                            List.of("official default"),
                            List.of(),
                            List.of("low"),
                            List.of("low")
                        ),
                        new ArchitectTicketProposalGeneratorPort.DecisionOption(
                            "OPT-B",
                            "Manually pin starter dependency versions",
                            List.of("more control"),
                            List.of("more complexity"),
                            List.of("higher drift risk"),
                            List.of("higher")
                        )
                    ),
                    new ArchitectTicketProposalGeneratorPort.Recommendation("OPT-A", "Prefer the default Spring Boot baseline."),
                    "The requirement is already explicit enough to choose the default safely.",
                    "mock",
                    "qwen3.5-plus-2026-02-15"
                )
            );
        when(ticketCommandUseCase.appendEvent(any(), any(), any(), any(), any()))
            .thenReturn(
                new TicketEvent(
                    "TEV-ARCH-AUTO-1",
                    "TCK-ARCH-AUTO-1",
                    TicketEventType.COMMENT,
                    "architect_agent",
                    "summary",
                    "{}",
                    Instant.parse("2026-02-21T00:00:00Z")
                )
            );

        ArchitectTicketAutoProcessorService.AutoProcessResult result = service.processOpenArchitectTickets("SES-1", 8);

        assertEquals(1, result.processedCount());
        ArgumentCaptor<String> eventTypeCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> dataCaptor = ArgumentCaptor.forClass(String.class);
        verify(ticketCommandUseCase, org.mockito.Mockito.times(3)).appendEvent(
            eq("TCK-ARCH-AUTO-1"),
            eq("architect_agent"),
            eventTypeCaptor.capture(),
            any(),
            dataCaptor.capture()
        );
        assertEquals(List.of("COMMENT", "DECISION_REQUESTED", "USER_RESPONDED"), eventTypeCaptor.getAllValues());
        assertTrue(dataCaptor.getAllValues().get(2).contains("\"auto_selected\":true"));
        assertTrue(dataCaptor.getAllValues().get(2).contains("\"selected_option_id\":\"OPT-A\""));
        verify(architectWorkPlanningService, never()).planAndPersist(any(), any(), any());
    }

    @Test
    void processOpenArchitectTicketsShouldSkipNonArchitectTypes() {
        ArchitectTicketAutoProcessorService service = new ArchitectTicketAutoProcessorService(
            sessionHistoryQueryUseCase,
            requirementDocQueryUseCase,
            ticketQueryUseCase,
            ticketCommandUseCase,
            contextCompileUseCase,
            proposalGeneratorPort,
            architectWorkPlanningService,
            new ObjectMapper(),
            "architect-auto-test",
            300,
            "",
            1,
            0,
            12
        );
        Ticket decisionTicket = sampleTicket("TCK-2", TicketType.DECISION, TicketStatus.OPEN);
        when(ticketQueryUseCase.listBySession("SES-1", null, "architect_agent", null))
            .thenReturn(List.of(decisionTicket));

        ArchitectTicketAutoProcessorService.AutoProcessResult result = service.processOpenArchitectTickets("SES-1", 8);

        assertEquals(0, result.processedCount());
        verify(ticketCommandUseCase, never()).claimTicket(any(), any(), org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    void processOpenArchitectTicketsShouldMarkSkippedWhenClaimFails() {
        ArchitectTicketAutoProcessorService service = new ArchitectTicketAutoProcessorService(
            sessionHistoryQueryUseCase,
            requirementDocQueryUseCase,
            ticketQueryUseCase,
            ticketCommandUseCase,
            contextCompileUseCase,
            proposalGeneratorPort,
            architectWorkPlanningService,
            new ObjectMapper(),
            "architect-auto-test",
            300,
            "",
            1,
            0,
            12
        );
        Ticket archReview = sampleTicket("TCK-3", TicketType.ARCH_REVIEW, TicketStatus.OPEN);
        when(ticketQueryUseCase.listBySession("SES-1", null, "architect_agent", null))
            .thenReturn(List.of(archReview));
        when(ticketQueryUseCase.listEvents("TCK-3")).thenReturn(List.of());
        when(requirementDocQueryUseCase.findCurrentBySessionId("SES-1")).thenReturn(confirmedRequirementDoc());
        when(ticketCommandUseCase.claimTicket("TCK-3", "architect-auto-test", 300))
            .thenThrow(new IllegalStateException("already claimed"));

        ArchitectTicketAutoProcessorService.AutoProcessResult result = service.processOpenArchitectTickets("SES-1", 8);

        assertEquals(0, result.processedCount());
        assertEquals(List.of("TCK-3"), result.skippedTicketIds());
    }

    @Test
    void processOpenArchitectTicketsShouldProcessWithEmptyRequirementContextWhenRequirementNotConfirmed() {
        ArchitectTicketAutoProcessorService service = new ArchitectTicketAutoProcessorService(
            sessionHistoryQueryUseCase,
            requirementDocQueryUseCase,
            ticketQueryUseCase,
            ticketCommandUseCase,
            contextCompileUseCase,
            proposalGeneratorPort,
            architectWorkPlanningService,
            new ObjectMapper(),
            "architect-auto-test",
            300,
            "",
            1,
            0,
            12
        );
        Ticket handoff = sampleTicket("TCK-NOT-CONFIRMED", TicketType.HANDOFF, TicketStatus.OPEN);
        when(ticketQueryUseCase.listBySession("SES-1", null, "architect_agent", null))
            .thenReturn(List.of(handoff));
        when(ticketQueryUseCase.listEvents("TCK-NOT-CONFIRMED")).thenReturn(List.of());
        when(requirementDocQueryUseCase.findCurrentBySessionId("SES-1"))
            .thenReturn(Optional.of(
                new RequirementCurrentDoc(
                    "REQ-1",
                    2,
                    1,
                    "IN_REVIEW",
                    "title",
                    "draft content",
                    Instant.parse("2026-02-21T00:00:00Z")
                )
            ));
        when(ticketCommandUseCase.claimTicket("TCK-NOT-CONFIRMED", "architect-auto-test", 300)).thenReturn(handoff);
        when(proposalGeneratorPort.generate(any()))
            .thenReturn(
                new ArchitectTicketProposalGeneratorPort.Proposal(
                    "CLARIFICATION",
                    "Need clarification",
                    List.of(),
                    List.of(),
                    null,
                    "summary",
                    "mock",
                    "qwen3.5-plus-2026-02-15"
                )
            );
        when(ticketCommandUseCase.appendEvent(any(), any(), any(), any(), any()))
            .thenReturn(
                new TicketEvent(
                    "TEV-NOT-CONFIRMED-1",
                    "TCK-NOT-CONFIRMED",
                    TicketEventType.COMMENT,
                    "architect_agent",
                    "summary",
                    "{}",
                    Instant.parse("2026-02-21T00:00:00Z")
                )
            );

        ArchitectTicketAutoProcessorService.AutoProcessResult result = service.processOpenArchitectTickets("SES-1", 8);

        assertEquals(1, result.processedCount());
        verify(ticketCommandUseCase).claimTicket("TCK-NOT-CONFIRMED", "architect-auto-test", 300);
        ArgumentCaptor<ArchitectTicketProposalGeneratorPort.GenerateInput> inputCaptor =
            ArgumentCaptor.forClass(ArchitectTicketProposalGeneratorPort.GenerateInput.class);
        verify(proposalGeneratorPort).generate(inputCaptor.capture());
        assertEquals("", inputCaptor.getValue().requirementDocContent());
        verify(architectWorkPlanningService, never()).planAndPersist(any(), any(), any());
    }

    @Test
    void processOpenArchitectTicketsShouldRespectOwnedSessionScope() {
        ArchitectTicketAutoProcessorService service = new ArchitectTicketAutoProcessorService(
            sessionHistoryQueryUseCase,
            requirementDocQueryUseCase,
            ticketQueryUseCase,
            ticketCommandUseCase,
            contextCompileUseCase,
            proposalGeneratorPort,
            architectWorkPlanningService,
            new ObjectMapper(),
            "architect-auto-test",
            300,
            "SES-2",
            1,
            0,
            12
        );

        SessionHistoryView session1 = new SessionHistoryView(
            "SES-1",
            "s1",
            "ACTIVE",
            Instant.parse("2026-02-21T00:00:00Z"),
            Instant.parse("2026-02-21T00:00:00Z"),
            null
        );
        SessionHistoryView session2 = new SessionHistoryView(
            "SES-2",
            "s2",
            "ACTIVE",
            Instant.parse("2026-02-21T00:00:00Z"),
            Instant.parse("2026-02-21T00:00:00Z"),
            null
        );
        Instant now = Instant.parse("2026-02-21T00:00:00Z");
        Ticket handoff = new Ticket(
            "TCK-4",
            "SES-2",
            TicketType.HANDOFF,
            TicketStatus.OPEN,
            "Architecture review needed",
            "requirement_agent",
            "architect_agent",
            "REQ-1",
            2,
            "{\"kind\":\"handoff_packet\"}",
            null,
            null,
            now,
            now
        );
        when(sessionHistoryQueryUseCase.listSessionsWithCurrentRequirementDoc()).thenReturn(List.of(session1, session2));
        when(ticketQueryUseCase.listBySession("SES-2", null, "architect_agent", null))
            .thenReturn(List.of(handoff));
        when(ticketQueryUseCase.listEvents("TCK-4")).thenReturn(List.of());
        when(requirementDocQueryUseCase.findCurrentBySessionId("SES-2")).thenReturn(confirmedRequirementDoc());
        when(ticketCommandUseCase.claimTicket("TCK-4", "architect-auto-test", 300)).thenReturn(handoff);
        when(proposalGeneratorPort.generate(any()))
            .thenReturn(
                new ArchitectTicketProposalGeneratorPort.Proposal(
                    "DECISION",
                    "Q?",
                    List.of(),
                    List.of(),
                    null,
                    "summary",
                    "mock",
                    "qwen3.5-plus-2026-02-15"
                )
            );
        when(ticketCommandUseCase.appendEvent(any(), any(), any(), any(), any()))
            .thenReturn(
                new TicketEvent(
                    "TEV-2",
                    "TCK-4",
                    TicketEventType.COMMENT,
                    "architect_agent",
                    "summary",
                    "{}",
                    Instant.parse("2026-02-21T00:00:00Z")
                )
            );

        ArchitectTicketAutoProcessorService.AutoProcessResult result = service.processOpenArchitectTickets(null, 8);

        assertEquals(1, result.processedCount());
        verify(ticketQueryUseCase, never()).listBySession("SES-1", null, "architect_agent", null);
        verify(ticketQueryUseCase).listBySession("SES-2", null, "architect_agent", null);
    }

    @Test
    void processOpenArchitectTicketsShouldResumeInProgressAfterUserResponded() {
        ArchitectTicketAutoProcessorService service = new ArchitectTicketAutoProcessorService(
            sessionHistoryQueryUseCase,
            requirementDocQueryUseCase,
            ticketQueryUseCase,
            ticketCommandUseCase,
            contextCompileUseCase,
            proposalGeneratorPort,
            architectWorkPlanningService,
            new ObjectMapper(),
            "architect-auto-test",
            300,
            "",
            1,
            0,
            12
        );
        Ticket inProgress = sampleTicket("TCK-5", TicketType.HANDOFF, TicketStatus.IN_PROGRESS, "architect-auto-test");
        List<TicketEvent> events = List.of(
            new TicketEvent(
                "TEV-REQ-1",
                "TCK-5",
                TicketEventType.DECISION_REQUESTED,
                "architect_agent",
                "first question",
                "{}",
                Instant.parse("2026-02-21T00:00:00Z")
            ),
            new TicketEvent(
                "TEV-REQ-2",
                "TCK-5",
                TicketEventType.USER_RESPONDED,
                "user",
                "user answer",
                "{\"answer\":\"A\"}",
                Instant.parse("2026-02-21T00:01:00Z")
            )
        );

        when(ticketQueryUseCase.listBySession("SES-1", null, "architect_agent", null)).thenReturn(List.of(inProgress));
        when(ticketQueryUseCase.listEvents("TCK-5")).thenReturn(events);
        when(requirementDocQueryUseCase.findCurrentBySessionId("SES-1")).thenReturn(confirmedRequirementDoc());
        when(ticketCommandUseCase.tryMovePlanningLease(eq("TCK-5"), eq("architect-auto-test"), any(), eq(300)))
            .thenReturn(Optional.of(
                sampleTicket("TCK-5", TicketType.HANDOFF, TicketStatus.IN_PROGRESS, "architect-auto-test#planning#1")
            ));
        when(architectWorkPlanningService.planAndPersist(any(), any(), any()))
            .thenReturn(samplePlanResult());
        when(ticketCommandUseCase.appendEvent(any(), any(), any(), any(), any()))
            .thenReturn(
                new TicketEvent(
                    "TEV-RESP-1",
                    "TCK-5",
                    TicketEventType.COMMENT,
                    "architect_agent",
                    "summary",
                    "{}",
                    Instant.parse("2026-02-21T00:02:00Z")
                )
            );

        ArchitectTicketAutoProcessorService.AutoProcessResult result = service.processOpenArchitectTickets("SES-1", 8);

        assertEquals(1, result.processedCount());
        verify(ticketCommandUseCase, never()).claimTicket(any(), any(), org.mockito.ArgumentMatchers.anyInt());
        ArgumentCaptor<String> eventTypeCaptor = ArgumentCaptor.forClass(String.class);
        verify(ticketCommandUseCase, org.mockito.Mockito.times(3)).appendEvent(
            eq("TCK-5"),
            eq("architect_agent"),
            eventTypeCaptor.capture(),
            any(),
            any()
        );
        assertTrue(eventTypeCaptor.getAllValues().contains("ARTIFACT_LINKED"));
        assertTrue(eventTypeCaptor.getAllValues().contains("STATUS_CHANGED"));
        verify(proposalGeneratorPort, never()).generate(any());
    }

    @Test
    void processOpenArchitectTicketsShouldResumeInProgressWhenEventsShareSameTimestamp() {
        ArchitectTicketAutoProcessorService service = new ArchitectTicketAutoProcessorService(
            sessionHistoryQueryUseCase,
            requirementDocQueryUseCase,
            ticketQueryUseCase,
            ticketCommandUseCase,
            contextCompileUseCase,
            proposalGeneratorPort,
            architectWorkPlanningService,
            new ObjectMapper(),
            "architect-auto-test",
            300,
            "",
            1,
            0,
            12
        );
        Ticket inProgress = sampleTicket("TCK-5B", TicketType.HANDOFF, TicketStatus.IN_PROGRESS, "architect-auto-test");
        Instant ts = Instant.parse("2026-02-21T00:00:00Z");
        List<TicketEvent> events = List.of(
            new TicketEvent(
                "TEV-REQ-1",
                "TCK-5B",
                TicketEventType.DECISION_REQUESTED,
                "architect_agent",
                "first question",
                "{}",
                ts
            ),
            new TicketEvent(
                "TEV-REQ-2",
                "TCK-5B",
                TicketEventType.USER_RESPONDED,
                "user",
                "user answer in same second",
                "{\"answer\":\"A\"}",
                ts
            )
        );

        when(ticketQueryUseCase.listBySession("SES-1", null, "architect_agent", null)).thenReturn(List.of(inProgress));
        when(ticketQueryUseCase.listEvents("TCK-5B")).thenReturn(events);
        when(requirementDocQueryUseCase.findCurrentBySessionId("SES-1")).thenReturn(confirmedRequirementDoc());
        when(ticketCommandUseCase.tryMovePlanningLease(eq("TCK-5B"), eq("architect-auto-test"), any(), eq(300)))
            .thenReturn(Optional.of(
                sampleTicket("TCK-5B", TicketType.HANDOFF, TicketStatus.IN_PROGRESS, "architect-auto-test#planning#2")
            ));
        when(architectWorkPlanningService.planAndPersist(any(), any(), any()))
            .thenReturn(samplePlanResult());
        when(ticketCommandUseCase.appendEvent(any(), any(), any(), any(), any()))
            .thenReturn(
                new TicketEvent(
                    "TEV-RESP-1",
                    "TCK-5B",
                    TicketEventType.COMMENT,
                    "architect_agent",
                    "summary",
                    "{}",
                    Instant.parse("2026-02-21T00:02:00Z")
                )
            );

        ArchitectTicketAutoProcessorService.AutoProcessResult result = service.processOpenArchitectTickets("SES-1", 8);

        assertEquals(1, result.processedCount());
        verify(ticketCommandUseCase, never()).claimTicket(any(), any(), org.mockito.ArgumentMatchers.anyInt());
        verify(ticketCommandUseCase, org.mockito.Mockito.times(3)).appendEvent(
            eq("TCK-5B"),
            eq("architect_agent"),
            any(),
            any(),
            any()
        );
        verify(proposalGeneratorPort, never()).generate(any());
    }

    @Test
    void processOpenArchitectTicketsShouldProcessInProgressOwnedTicket() {
        ArchitectTicketAutoProcessorService service = new ArchitectTicketAutoProcessorService(
            sessionHistoryQueryUseCase,
            requirementDocQueryUseCase,
            ticketQueryUseCase,
            ticketCommandUseCase,
            contextCompileUseCase,
            proposalGeneratorPort,
            architectWorkPlanningService,
            new ObjectMapper(),
            "architect-auto-test",
            300,
            "",
            1,
            0,
            12
        );
        Ticket inProgress = sampleTicket("TCK-6", TicketType.HANDOFF, TicketStatus.IN_PROGRESS, "architect-auto-test");
        List<TicketEvent> events = List.of(
            new TicketEvent(
                "TEV-REQ-1",
                "TCK-6",
                TicketEventType.USER_RESPONDED,
                "user",
                "old answer",
                "{}",
                Instant.parse("2026-02-21T00:00:00Z")
            ),
            new TicketEvent(
                "TEV-REQ-2",
                "TCK-6",
                TicketEventType.DECISION_REQUESTED,
                "architect_agent",
                "newer question",
                "{}",
                Instant.parse("2026-02-21T00:01:00Z")
            )
        );

        when(ticketQueryUseCase.listBySession("SES-1", null, "architect_agent", null)).thenReturn(List.of(inProgress));
        when(ticketQueryUseCase.listEvents("TCK-6")).thenReturn(events);
        when(requirementDocQueryUseCase.findCurrentBySessionId("SES-1")).thenReturn(confirmedRequirementDoc());
        when(ticketCommandUseCase.tryMovePlanningLease(eq("TCK-6"), eq("architect-auto-test"), any(), eq(300)))
            .thenReturn(Optional.of(
                sampleTicket("TCK-6", TicketType.HANDOFF, TicketStatus.IN_PROGRESS, "architect-auto-test#planning#3")
            ));
        when(architectWorkPlanningService.planAndPersist(any(), any(), any()))
            .thenReturn(samplePlanResult());
        when(ticketCommandUseCase.appendEvent(any(), any(), any(), any(), any()))
            .thenReturn(
                new TicketEvent(
                    "TEV-RESP-2",
                    "TCK-6",
                    TicketEventType.COMMENT,
                    "architect_agent",
                    "summary",
                    "{}",
                    Instant.parse("2026-02-21T00:02:00Z")
                )
            );

        ArchitectTicketAutoProcessorService.AutoProcessResult result = service.processOpenArchitectTickets("SES-1", 8);

        assertEquals(1, result.processedCount());
        verify(ticketCommandUseCase, org.mockito.Mockito.times(3)).appendEvent(
            eq("TCK-6"),
            eq("architect_agent"),
            any(),
            any(),
            any()
        );
        verify(proposalGeneratorPort, never()).generate(any());
    }

    @Test
    void processOpenArchitectTicketsShouldSkipInProgressWithoutUserResponse() {
        ArchitectTicketAutoProcessorService service = new ArchitectTicketAutoProcessorService(
            sessionHistoryQueryUseCase,
            requirementDocQueryUseCase,
            ticketQueryUseCase,
            ticketCommandUseCase,
            contextCompileUseCase,
            proposalGeneratorPort,
            architectWorkPlanningService,
            new ObjectMapper(),
            "architect-auto-test",
            300,
            "",
            1,
            0,
            12
        );
        Ticket inProgress = sampleTicket("TCK-7", TicketType.HANDOFF, TicketStatus.IN_PROGRESS, "architect-auto-test");
        List<TicketEvent> events = List.of(
            new TicketEvent(
                "TEV-REQ-1",
                "TCK-7",
                TicketEventType.DECISION_REQUESTED,
                "architect_agent",
                "question",
                "{}",
                Instant.parse("2026-02-21T00:00:00Z")
            )
        );
        when(ticketQueryUseCase.listBySession("SES-1", null, "architect_agent", null)).thenReturn(List.of(inProgress));
        when(ticketQueryUseCase.listEvents("TCK-7")).thenReturn(events);

        ArchitectTicketAutoProcessorService.AutoProcessResult result = service.processOpenArchitectTickets("SES-1", 8);

        assertEquals(0, result.processedCount());
        verify(architectWorkPlanningService, never()).planAndPersist(any(), any(), any());
        verify(proposalGeneratorPort, never()).generate(any());
    }

    @Test
    void processOpenArchitectTicketsShouldSkipWhenPlanningLeaseHeldByOtherInstance() {
        ArchitectTicketAutoProcessorService service = new ArchitectTicketAutoProcessorService(
            sessionHistoryQueryUseCase,
            requirementDocQueryUseCase,
            ticketQueryUseCase,
            ticketCommandUseCase,
            contextCompileUseCase,
            proposalGeneratorPort,
            architectWorkPlanningService,
            new ObjectMapper(),
            "architect-auto-test",
            300,
            "",
            1,
            0,
            12
        );
        Ticket inProgress = sampleTicket(
            "TCK-PLN-HOLD",
            TicketType.HANDOFF,
            TicketStatus.IN_PROGRESS,
            "architect-auto-test#planning#other",
            Instant.parse("2099-01-01T00:00:00Z")
        );
        List<TicketEvent> events = List.of(
            new TicketEvent(
                "TEV-HOLD-1",
                "TCK-PLN-HOLD",
                TicketEventType.DECISION_REQUESTED,
                "architect_agent",
                "question",
                "{}",
                Instant.parse("2026-02-21T00:00:00Z")
            ),
            new TicketEvent(
                "TEV-HOLD-2",
                "TCK-PLN-HOLD",
                TicketEventType.USER_RESPONDED,
                "user",
                "answer",
                "{}",
                Instant.parse("2026-02-21T00:01:00Z")
            )
        );
        when(ticketQueryUseCase.listBySession("SES-1", null, "architect_agent", null)).thenReturn(List.of(inProgress));
        when(ticketQueryUseCase.listEvents("TCK-PLN-HOLD")).thenReturn(events);

        ArchitectTicketAutoProcessorService.AutoProcessResult result = service.processOpenArchitectTickets("SES-1", 8);

        assertEquals(0, result.processedCount());
        verify(ticketCommandUseCase, never()).tryMovePlanningLease(any(), any(), any(), anyInt());
        verify(architectWorkPlanningService, never()).planAndPersist(any(), any(), any());
    }

    @Test
    void processOpenArchitectTicketsShouldRecoverExpiredPlanningLease() {
        ArchitectTicketAutoProcessorService service = new ArchitectTicketAutoProcessorService(
            sessionHistoryQueryUseCase,
            requirementDocQueryUseCase,
            ticketQueryUseCase,
            ticketCommandUseCase,
            contextCompileUseCase,
            proposalGeneratorPort,
            architectWorkPlanningService,
            new ObjectMapper(),
            "architect-auto-test",
            300,
            "",
            1,
            0,
            12
        );
        Ticket expiredPlanning = sampleTicket(
            "TCK-PLN-RECOVER",
            TicketType.HANDOFF,
            TicketStatus.IN_PROGRESS,
            "architect-auto-test#planning#expired",
            Instant.parse("2020-01-01T00:00:00Z")
        );
        List<TicketEvent> events = List.of(
            new TicketEvent(
                "TEV-REC-1",
                "TCK-PLN-RECOVER",
                TicketEventType.DECISION_REQUESTED,
                "architect_agent",
                "question",
                "{}",
                Instant.parse("2026-02-21T00:00:00Z")
            ),
            new TicketEvent(
                "TEV-REC-2",
                "TCK-PLN-RECOVER",
                TicketEventType.USER_RESPONDED,
                "user",
                "answer",
                "{\"answer\":\"A\"}",
                Instant.parse("2026-02-21T00:01:00Z")
            )
        );
        when(ticketQueryUseCase.listBySession("SES-1", null, "architect_agent", null)).thenReturn(List.of(expiredPlanning));
        when(ticketQueryUseCase.listEvents("TCK-PLN-RECOVER")).thenReturn(events);
        when(requirementDocQueryUseCase.findCurrentBySessionId("SES-1")).thenReturn(confirmedRequirementDoc());
        when(ticketCommandUseCase.tryMovePlanningLease(
            eq("TCK-PLN-RECOVER"),
            eq("architect-auto-test#planning#expired"),
            any(),
            eq(300)
        )).thenReturn(Optional.of(
            sampleTicket(
                "TCK-PLN-RECOVER",
                TicketType.HANDOFF,
                TicketStatus.IN_PROGRESS,
                "architect-auto-test#planning#recovered"
            )
        ));
        when(architectWorkPlanningService.planAndPersist(any(), any(), any()))
            .thenReturn(samplePlanResult());
        when(ticketCommandUseCase.appendEvent(any(), any(), any(), any(), any()))
            .thenReturn(
                new TicketEvent(
                    "TEV-REC-3",
                    "TCK-PLN-RECOVER",
                    TicketEventType.COMMENT,
                    "architect_agent",
                    "summary",
                    "{}",
                    Instant.parse("2026-02-21T00:02:00Z")
                )
            );

        ArchitectTicketAutoProcessorService.AutoProcessResult result = service.processOpenArchitectTickets("SES-1", 8);

        assertEquals(1, result.processedCount());
        verify(ticketCommandUseCase).tryMovePlanningLease(
            eq("TCK-PLN-RECOVER"),
            eq("architect-auto-test#planning#expired"),
            any(),
            eq(300)
        );
        verify(architectWorkPlanningService).planAndPersist(any(), any(), any());
    }

    private static Optional<RequirementCurrentDoc> confirmedRequirementDoc() {
        return Optional.of(
            new RequirementCurrentDoc(
                "REQ-1",
                2,
                2,
                "CONFIRMED",
                "title",
                "requirement markdown content",
                Instant.parse("2026-02-21T00:00:00Z")
            )
        );
    }

    private static Ticket sampleTicket(String ticketId, TicketType type, TicketStatus status) {
        Instant now = Instant.parse("2026-02-21T00:00:00Z");
        return new Ticket(
            ticketId,
            "SES-1",
            type,
            status,
            "Architecture review needed",
            "requirement_agent",
            "architect_agent",
            "REQ-1",
            2,
            "{\"kind\":\"handoff_packet\"}",
            null,
            null,
            now,
            now
        );
    }

    private static Ticket sampleTicket(String ticketId, TicketType type, TicketStatus status, String claimedBy) {
        Instant now = Instant.parse("2026-02-21T00:00:00Z");
        return new Ticket(
            ticketId,
            "SES-1",
            type,
            status,
            "Architecture review needed",
            "requirement_agent",
            "architect_agent",
            "REQ-1",
            2,
            "{\"kind\":\"handoff_packet\"}",
            claimedBy,
            Instant.parse("2026-02-21T00:10:00Z"),
            now,
            now
        );
    }

    private static Ticket sampleTicket(
        String ticketId,
        TicketType type,
        TicketStatus status,
        String claimedBy,
        Instant leaseUntil
    ) {
        Instant now = Instant.parse("2026-02-21T00:00:00Z");
        return new Ticket(
            ticketId,
            "SES-1",
            type,
            status,
            "Architecture review needed",
            "requirement_agent",
            "architect_agent",
            "REQ-1",
            2,
            "{\"kind\":\"handoff_packet\"}",
            claimedBy,
            leaseUntil,
            now,
            now
        );
    }

    private static ArchitectWorkPlanningService.PlanResult samplePlanResult() {
        return new ArchitectWorkPlanningService.PlanResult(
            "plan summary",
            "mock",
            "qwen3.5-plus-2026-02-15",
            List.of(
                new ArchitectWorkPlanningService.CreatedModule(
                    "MOD-1",
                    "module-1",
                    "desc",
                    List.of(
                        new ArchitectWorkPlanningService.CreatedTask(
                            "task_1",
                            "TASK-1",
                            "task-1",
                            "tmpl.impl.v0",
                            "WAITING_WORKER",
                            "[\"TP-JAVA-21\",\"TP-MAVEN-3\",\"TP-GIT-2\"]",
                            List.of(),
                            List.of(),
                            "why",
                            "CTXS-1"
                        )
                    )
                )
            )
        );
    }
}

