package com.agentx.agentxbackend.requirement.application;

import com.agentx.agentxbackend.requirement.application.port.in.RequirementAgentUseCase;
import com.agentx.agentxbackend.requirement.application.port.in.RequirementDocUseCase;
import com.agentx.agentxbackend.requirement.application.port.out.DomainEventPublisher;
import com.agentx.agentxbackend.requirement.application.port.out.RequirementConversationHistoryRepository;
import com.agentx.agentxbackend.requirement.application.port.out.RequirementDocRepository;
import com.agentx.agentxbackend.requirement.application.port.out.RequirementDocVersionRepository;
import com.agentx.agentxbackend.requirement.application.port.out.RequirementDraftGeneratorPort;
import com.agentx.agentxbackend.requirement.domain.event.RequirementHandoffRequestedEvent;
import com.agentx.agentxbackend.requirement.domain.model.RequirementAgentPhase;
import com.agentx.agentxbackend.requirement.domain.model.RequirementDoc;
import com.agentx.agentxbackend.requirement.domain.model.RequirementDocStatus;
import com.agentx.agentxbackend.requirement.domain.model.RequirementDocVersion;
import com.agentx.agentxbackend.requirement.domain.policy.RequirementDocContentPolicy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RequirementAgentDraftServiceTest {

    @Mock
    private RequirementDocUseCase requirementDocUseCase;
    @Mock
    private RequirementDocRepository requirementDocRepository;
    @Mock
    private RequirementDocVersionRepository requirementDocVersionRepository;
    @Mock
    private RequirementConversationHistoryRepository conversationHistoryRepository;
    @Mock
    private RequirementDraftGeneratorPort draftGeneratorPort;
    @Mock
    private DomainEventPublisher domainEventPublisher;
    @InjectMocks
    private RequirementAgentDraftService service;

    @Test
    void generateDraftShouldStayInDiscoveryWhenUserHasNotTriggeredDrafting() {
        when(draftGeneratorPort.assessConversation(any())).thenReturn(
            new RequirementDraftGeneratorPort.ConversationAssessment(
                "请补充业务目标、范围和验收标准。",
                false,
                List.of("业务目标", "范围边界", "验收标准"),
                false,
                null,
                "mock",
                "qwen3.5-plus-2026-02-15"
            )
        );

        RequirementAgentUseCase.DraftResult result = service.generateDraft(
            new RequirementAgentUseCase.GenerateDraftCommand(
                "SES-1",
                "Task Board",
                "我们想做一个任务系统",
                null,
                true
            )
        );

        assertEquals(RequirementAgentPhase.DISCOVERY_CHAT, result.phase());
        assertFalse(result.persisted());
        assertNull(result.doc());
        assertNull(result.version());
        assertNull(result.content());
        assertEquals("mock", result.provider());
        assertFalse(result.readyToDraft());
        assertEquals(3, result.missingInformation().size());
        verify(draftGeneratorPort).assessConversation(any());
        verify(draftGeneratorPort, never()).generate(any());
        verifyNoInteractions(
            requirementDocUseCase,
            requirementDocRepository,
            requirementDocVersionRepository,
            domainEventPublisher
        );
    }

    @Test
    void generateDraftShouldRejectDraftTriggerWhenInformationInsufficient() {
        when(draftGeneratorPort.assessConversation(any())).thenReturn(
            new RequirementDraftGeneratorPort.ConversationAssessment(
                "信息不足，先补充验收标准。",
                false,
                List.of("验收标准"),
                false,
                null,
                "mock",
                "qwen3.5-plus-2026-02-15"
            )
        );

        RequirementAgentUseCase.DraftResult result = service.generateDraft(
            new RequirementAgentUseCase.GenerateDraftCommand(
                "SES-1",
                "Task Board",
                "确认需求",
                null,
                true
            )
        );

        assertEquals(RequirementAgentPhase.NEED_MORE_INFO, result.phase());
        assertFalse(result.persisted());
        assertFalse(result.readyToDraft());
        assertEquals(List.of("验收标准"), result.missingInformation());
        verify(draftGeneratorPort).assessConversation(any());
        verify(draftGeneratorPort, never()).generate(any());
        verifyNoInteractions(
            requirementDocUseCase,
            requirementDocRepository,
            requirementDocVersionRepository,
            domainEventPublisher
        );
    }

    @Test
    void generateDraftShouldCreateHandoffTicketWhenArchitectureRequestDetectedInDiscovery() {
        when(draftGeneratorPort.assessConversation(any())).thenReturn(
            new RequirementDraftGeneratorPort.ConversationAssessment(
                "该请求涉及架构层内容，已转交架构师。",
                false,
                List.of(),
                true,
                "User asked database schema strategy",
                "mock",
                "qwen3.5-plus-2026-02-15"
            )
        );

        RequirementAgentUseCase.DraftResult result = service.generateDraft(
            new RequirementAgentUseCase.GenerateDraftCommand(
                "SES-2",
                "Task Board",
                "请给我数据库分库分表方案",
                null,
                true
            )
        );

        assertEquals(RequirementAgentPhase.HANDOFF_CREATED, result.phase());
        assertFalse(result.persisted());
        assertNull(result.doc());
        verify(draftGeneratorPort).assessConversation(any());
        verify(draftGeneratorPort, never()).generate(any());

        ArgumentCaptor<RequirementHandoffRequestedEvent> eventCaptor =
            ArgumentCaptor.forClass(RequirementHandoffRequestedEvent.class);
        verify(domainEventPublisher).publish(eventCaptor.capture());
        RequirementHandoffRequestedEvent event = eventCaptor.getValue();
        assertEquals("SES-2", event.sessionId());
        assertNull(event.requirementDocId());
        assertNull(event.requirementDocVersion());
        assertTrue(event.userInput().contains("分库分表"));
        assertEquals("User asked database schema strategy", event.reason());
        verifyNoInteractions(requirementDocUseCase, requirementDocRepository, requirementDocVersionRepository);
    }

    @Test
    void generateDraftShouldCreateDocAndVersionWhenUserTriggersAndReady() {
        when(draftGeneratorPort.assessConversation(any())).thenReturn(
            new RequirementDraftGeneratorPort.ConversationAssessment(
                "信息足够，可以开始生成。",
                true,
                List.of(),
                false,
                null,
                "mock",
                "qwen3.5-plus-2026-02-15"
            )
        );

        String generatedContent = validMarkdown("Payments", "user wants checkout", "initial draft");
        when(draftGeneratorPort.generate(any())).thenReturn(
            new RequirementDraftGeneratorPort.GeneratedDraft(generatedContent, "mock", "qwen3.5-plus-2026-02-15")
        );

        RequirementDoc createdDoc = new RequirementDoc(
            "REQ-NEW-1",
            "SES-1",
            0,
            null,
            RequirementDocStatus.DRAFT,
            "Payments",
            Instant.parse("2026-02-21T00:00:00Z"),
            Instant.parse("2026-02-21T00:00:00Z")
        );
        RequirementDocVersion createdVersion = new RequirementDocVersion(
            "REQ-NEW-1",
            1,
            generatedContent,
            "requirement_agent",
            Instant.parse("2026-02-21T00:01:00Z")
        );
        RequirementDoc latestDoc = new RequirementDoc(
            "REQ-NEW-1",
            "SES-1",
            1,
            null,
            RequirementDocStatus.IN_REVIEW,
            "Payments",
            Instant.parse("2026-02-21T00:00:00Z"),
            Instant.parse("2026-02-21T00:01:00Z")
        );

        when(requirementDocUseCase.createRequirementDoc("SES-1", "Payments")).thenReturn(createdDoc);
        when(requirementDocUseCase.createVersion(eq("REQ-NEW-1"), anyString(), eq("requirement_agent")))
            .thenReturn(createdVersion);
        when(requirementDocRepository.findById("REQ-NEW-1")).thenReturn(Optional.of(latestDoc));

        RequirementAgentUseCase.DraftResult result = service.generateDraft(
            new RequirementAgentUseCase.GenerateDraftCommand(
                "SES-1",
                "Payments",
                "确认需求，开始需求文档",
                null,
                true
            )
        );

        assertEquals(RequirementAgentPhase.DRAFT_CREATED, result.phase());
        assertTrue(result.persisted());
        assertEquals("REQ-NEW-1", result.doc().docId());
        assertEquals(1, result.version().version());
        assertEquals("mock", result.provider());
        assertEquals("qwen3.5-plus-2026-02-15", result.model());
        assertNotNull(result.content());
    }

    @Test
    void generateDraftShouldIncludeDiscoveryHistoryWhenCreatingFirstDraft() {
        when(conversationHistoryRepository.load("SES-H")).thenReturn(
            List.of(),
            List.of(new RequirementDraftGeneratorPort.ConversationTurn(
                "user",
                "功能包含学籍、成绩、考勤，学生和老师会使用，只要Java后端"
            ))
        );
        when(draftGeneratorPort.assessConversation(any())).thenReturn(
            new RequirementDraftGeneratorPort.ConversationAssessment(
                "请补充验收标准。",
                false,
                List.of("验收标准"),
                false,
                null,
                "mock",
                "qwen3.5-plus-2026-02-15"
            ),
            new RequirementDraftGeneratorPort.ConversationAssessment(
                "信息足够，可以开始生成。",
                true,
                List.of(),
                false,
                null,
                "mock",
                "qwen3.5-plus-2026-02-15"
            )
        );

        String generatedContent = validMarkdown("Student System", "student management", "initial draft");
        when(draftGeneratorPort.generate(any())).thenReturn(
            new RequirementDraftGeneratorPort.GeneratedDraft(generatedContent, "mock", "qwen3.5-plus-2026-02-15")
        );

        RequirementDoc createdDoc = new RequirementDoc(
            "REQ-H-1",
            "SES-H",
            0,
            null,
            RequirementDocStatus.DRAFT,
            "Student System",
            Instant.parse("2026-02-21T00:00:00Z"),
            Instant.parse("2026-02-21T00:00:00Z")
        );
        RequirementDocVersion createdVersion = new RequirementDocVersion(
            "REQ-H-1",
            1,
            generatedContent,
            "requirement_agent",
            Instant.parse("2026-02-21T00:01:00Z")
        );
        RequirementDoc latestDoc = new RequirementDoc(
            "REQ-H-1",
            "SES-H",
            1,
            null,
            RequirementDocStatus.IN_REVIEW,
            "Student System",
            Instant.parse("2026-02-21T00:00:00Z"),
            Instant.parse("2026-02-21T00:01:00Z")
        );

        when(requirementDocUseCase.createRequirementDoc("SES-H", "Student System")).thenReturn(createdDoc);
        when(requirementDocUseCase.createVersion(eq("REQ-H-1"), anyString(), eq("requirement_agent")))
            .thenReturn(createdVersion);
        when(requirementDocRepository.findById("REQ-H-1")).thenReturn(Optional.of(latestDoc));

        RequirementAgentUseCase.DraftResult discovery = service.generateDraft(
            new RequirementAgentUseCase.GenerateDraftCommand(
                "SES-H",
                "Student System",
                "功能包含学籍、成绩、考勤，学生和老师会使用，只要Java后端",
                null,
                true
            )
        );
        assertEquals(RequirementAgentPhase.DISCOVERY_CHAT, discovery.phase());

        RequirementAgentUseCase.DraftResult created = service.generateDraft(
            new RequirementAgentUseCase.GenerateDraftCommand(
                "SES-H",
                "Student System",
                "确认需求",
                null,
                true
            )
        );

        ArgumentCaptor<RequirementDraftGeneratorPort.GenerateDraftInput> inputCaptor =
            ArgumentCaptor.forClass(RequirementDraftGeneratorPort.GenerateDraftInput.class);
        verify(draftGeneratorPort).generate(inputCaptor.capture());

        List<RequirementDraftGeneratorPort.ConversationTurn> history = inputCaptor.getValue().history();
        assertNotNull(history);
        assertTrue(history.stream().anyMatch(turn -> turn.content().contains("学籍")));
        assertTrue(history.stream().anyMatch(turn -> "确认需求".equals(turn.content())));
        assertEquals(RequirementAgentPhase.DRAFT_CREATED, created.phase());
        assertTrue(created.persisted());
        verify(conversationHistoryRepository).clear("SES-H");
    }

    @Test
    void generateDraftShouldSupportDryRunWhenTriggeredAndReady() {
        when(draftGeneratorPort.assessConversation(any())).thenReturn(
            new RequirementDraftGeneratorPort.ConversationAssessment(
                "信息足够，可以生成草稿。",
                true,
                List.of(),
                false,
                null,
                "mock",
                "qwen3.5-plus-2026-02-15"
            )
        );

        String generatedContent = validMarkdown("Billing", "draft only", "dry run");
        when(draftGeneratorPort.generate(any())).thenReturn(
            new RequirementDraftGeneratorPort.GeneratedDraft(generatedContent, "mock", "qwen3.5-plus-2026-02-15")
        );

        RequirementAgentUseCase.DraftResult result = service.generateDraft(
            new RequirementAgentUseCase.GenerateDraftCommand(
                "SES-2",
                "Billing",
                "确认需求，生成文档",
                null,
                false
            )
        );

        assertEquals(RequirementAgentPhase.DRAFT_CREATED, result.phase());
        assertEquals(generatedContent.trim(), result.content());
        assertFalse(result.persisted());
        assertEquals("mock", result.provider());
        assertEquals("qwen3.5-plus-2026-02-15", result.model());
        verify(draftGeneratorPort).assessConversation(any());
        verify(draftGeneratorPort).generate(any());
        verifyNoInteractions(
            requirementDocUseCase,
            requirementDocRepository,
            requirementDocVersionRepository,
            domainEventPublisher
        );
    }

    @Test
    void generateDraftShouldReviseExistingDocUsingLatestVersionContent() {
        RequirementDoc existing = new RequirementDoc(
            "REQ-2",
            "SES-9",
            2,
            2,
            RequirementDocStatus.CONFIRMED,
            "Inventory",
            Instant.parse("2026-01-01T00:00:00Z"),
            Instant.parse("2026-01-03T00:00:00Z")
        );
        RequirementDocVersion latest = new RequirementDocVersion(
            "REQ-2",
            2,
            validMarkdown("Inventory", "old input", "old version"),
            "user",
            Instant.parse("2026-01-03T00:00:00Z")
        );
        String revised = validMarkdown("Inventory", "new user input", "revision");
        RequirementDocVersion createdVersion = new RequirementDocVersion(
            "REQ-2",
            3,
            revised,
            "requirement_agent",
            Instant.parse("2026-01-04T00:00:00Z")
        );
        RequirementDoc updated = new RequirementDoc(
            "REQ-2",
            "SES-9",
            3,
            2,
            RequirementDocStatus.IN_REVIEW,
            "Inventory",
            Instant.parse("2026-01-01T00:00:00Z"),
            Instant.parse("2026-01-04T00:00:00Z")
        );

        when(requirementDocRepository.findById("REQ-2")).thenReturn(Optional.of(existing), Optional.of(updated));
        when(requirementDocVersionRepository.findByDocIdAndVersion("REQ-2", 2)).thenReturn(Optional.of(latest));
        when(draftGeneratorPort.assessConversation(any())).thenReturn(
            new RequirementDraftGeneratorPort.ConversationAssessment(
                "继续修订需求文档。",
                true,
                List.of(),
                false,
                null,
                "mock",
                "qwen3.5-plus-2026-02-15"
            )
        );
        when(draftGeneratorPort.generate(any())).thenReturn(
            new RequirementDraftGeneratorPort.GeneratedDraft(revised, "mock", "qwen3.5-plus-2026-02-15")
        );
        when(requirementDocUseCase.createVersion(eq("REQ-2"), anyString(), eq("requirement_agent")))
            .thenReturn(createdVersion);

        RequirementAgentUseCase.DraftResult result = service.generateDraft(
            new RequirementAgentUseCase.GenerateDraftCommand(
                "SES-9",
                null,
                "Add cross-warehouse reservation support",
                "REQ-2",
                true
            )
        );

        ArgumentCaptor<RequirementDraftGeneratorPort.GenerateDraftInput> inputCaptor =
            ArgumentCaptor.forClass(RequirementDraftGeneratorPort.GenerateDraftInput.class);
        verify(draftGeneratorPort).generate(inputCaptor.capture());
        assertNotNull(inputCaptor.getValue().existingContent());
        assertEquals(latest.content(), inputCaptor.getValue().existingContent());

        assertEquals(RequirementAgentPhase.DRAFT_REVISED, result.phase());
        assertEquals("REQ-2", result.doc().docId());
        assertEquals(3, result.version().version());
        verify(draftGeneratorPort).assessConversation(any());
        verify(requirementDocUseCase, never()).createRequirementDoc(any(), any());
    }

    @Test
    void generateDraftShouldCreateHandoffForArchitectureChangeOnExistingDoc() {
        RequirementDoc existing = new RequirementDoc(
            "REQ-ARCH",
            "SES-9",
            5,
            5,
            RequirementDocStatus.CONFIRMED,
            "Inventory",
            Instant.parse("2026-01-01T00:00:00Z"),
            Instant.parse("2026-01-03T00:00:00Z")
        );
        RequirementDocVersion latest = new RequirementDocVersion(
            "REQ-ARCH",
            5,
            validMarkdown("Inventory", "old input", "old version"),
            "user",
            Instant.parse("2026-01-03T00:00:00Z")
        );

        when(requirementDocRepository.findById("REQ-ARCH")).thenReturn(Optional.of(existing));
        when(requirementDocVersionRepository.findByDocIdAndVersion("REQ-ARCH", 5)).thenReturn(Optional.of(latest));
        when(draftGeneratorPort.assessConversation(any())).thenReturn(
            new RequirementDraftGeneratorPort.ConversationAssessment(
                "这是架构层变化，已转交架构师。",
                false,
                List.of(),
                true,
                "Architecture-level request",
                "mock",
                "qwen3.5-plus-2026-02-15"
            )
        );

        RequirementAgentUseCase.DraftResult result = service.generateDraft(
            new RequirementAgentUseCase.GenerateDraftCommand(
                "SES-9",
                null,
                "Please redesign microservice topology and DB schema",
                "REQ-ARCH",
                true
            )
        );

        assertEquals(RequirementAgentPhase.HANDOFF_CREATED, result.phase());
        assertFalse(result.persisted());
        verify(draftGeneratorPort).assessConversation(any());
        verify(draftGeneratorPort, never()).generate(any());
        verify(requirementDocUseCase, never()).createVersion(any(), any(), any());

        ArgumentCaptor<RequirementHandoffRequestedEvent> eventCaptor =
            ArgumentCaptor.forClass(RequirementHandoffRequestedEvent.class);
        verify(domainEventPublisher).publish(eventCaptor.capture());
        RequirementHandoffRequestedEvent event = eventCaptor.getValue();
        assertEquals("SES-9", event.sessionId());
        assertEquals("REQ-ARCH", event.requirementDocId());
        assertEquals(5, event.requirementDocVersion());
        assertTrue(event.userInput().contains("microservice"));
    }

    @Test
    void generateDraftShouldPublishHandoffWithoutRequirementRefWhenExistingDocHasNoVersion() {
        RequirementDoc existing = new RequirementDoc(
            "REQ-NOV",
            "SES-9",
            0,
            null,
            RequirementDocStatus.DRAFT,
            "Inventory",
            Instant.parse("2026-01-01T00:00:00Z"),
            Instant.parse("2026-01-03T00:00:00Z")
        );

        when(requirementDocRepository.findById("REQ-NOV")).thenReturn(Optional.of(existing));
        when(draftGeneratorPort.assessConversation(any())).thenReturn(
            new RequirementDraftGeneratorPort.ConversationAssessment(
                "这是架构层变化，已转交架构师。",
                false,
                List.of(),
                true,
                "Architecture-level request",
                "mock",
                "qwen3.5-plus-2026-02-15"
            )
        );

        RequirementAgentUseCase.DraftResult result = service.generateDraft(
            new RequirementAgentUseCase.GenerateDraftCommand(
                "SES-9",
                null,
                "Please design DB schema",
                "REQ-NOV",
                true
            )
        );

        assertEquals(RequirementAgentPhase.HANDOFF_CREATED, result.phase());
        ArgumentCaptor<RequirementHandoffRequestedEvent> eventCaptor =
            ArgumentCaptor.forClass(RequirementHandoffRequestedEvent.class);
        verify(domainEventPublisher).publish(eventCaptor.capture());
        RequirementHandoffRequestedEvent event = eventCaptor.getValue();
        assertNull(event.requirementDocId());
        assertNull(event.requirementDocVersion());
    }

    @Test
    void generateDraftShouldRejectDocThatBelongsToAnotherSession() {
        RequirementDoc existing = new RequirementDoc(
            "REQ-3",
            "SES-OTHER",
            1,
            null,
            RequirementDocStatus.IN_REVIEW,
            "Doc",
            Instant.parse("2026-01-01T00:00:00Z"),
            Instant.parse("2026-01-01T00:00:00Z")
        );
        when(requirementDocRepository.findById("REQ-3")).thenReturn(Optional.of(existing));

        IllegalArgumentException ex = assertThrows(
            IllegalArgumentException.class,
            () -> service.generateDraft(
                new RequirementAgentUseCase.GenerateDraftCommand(
                    "SES-1",
                    null,
                    "input",
                    "REQ-3",
                    true
                )
            )
        );
        assertTrue(ex.getMessage().contains("does not belong"));
    }

    @Test
    void generateDraftShouldFailWhenGeneratorReturnsInvalidTemplate() {
        when(draftGeneratorPort.assessConversation(any())).thenReturn(
            new RequirementDraftGeneratorPort.ConversationAssessment(
                "ok",
                true,
                List.of(),
                false,
                null,
                "mock",
                "qwen3.5-plus-2026-02-15"
            )
        );
        when(draftGeneratorPort.generate(any())).thenReturn(
            new RequirementDraftGeneratorPort.GeneratedDraft("# invalid markdown", "mock", "qwen3.5-plus-2026-02-15")
        );

        IllegalArgumentException ex = assertThrows(
            IllegalArgumentException.class,
            () -> service.generateDraft(
                new RequirementAgentUseCase.GenerateDraftCommand(
                    "SES-2",
                    "Title",
                    "确认需求，开始需求文档",
                    null,
                    true
                )
            )
        );
        assertTrue(ex.getMessage().contains("schema_version"));
        verifyNoInteractions(
            requirementDocUseCase,
            requirementDocRepository,
            requirementDocVersionRepository,
            domainEventPublisher
        );
    }

    @Test
    void generateDraftShouldFailWhenExistingDocMissing() {
        when(requirementDocRepository.findById("REQ-MISSING")).thenReturn(Optional.empty());

        assertThrows(
            NoSuchElementException.class,
            () -> service.generateDraft(
                new RequirementAgentUseCase.GenerateDraftCommand(
                    "SES-1",
                    null,
                    "input",
                    "REQ-MISSING",
                    true
                )
            )
        );
        verifyNoInteractions(requirementDocUseCase, requirementDocVersionRepository, draftGeneratorPort, domainEventPublisher);
    }

    private static String validMarkdown(String title, String userInput, String changeLog) {
        return RequirementDocContentPolicy.buildTemplate(
            title,
            "Summary for: " + userInput,
            userInput,
            changeLog
        );
    }
}

