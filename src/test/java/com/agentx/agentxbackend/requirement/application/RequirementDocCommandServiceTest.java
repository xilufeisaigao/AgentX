package com.agentx.agentxbackend.requirement.application;

import com.agentx.agentxbackend.requirement.application.port.out.DomainEventPublisher;
import com.agentx.agentxbackend.requirement.application.port.out.RequirementDocRepository;
import com.agentx.agentxbackend.requirement.application.port.out.RequirementDocVersionRepository;
import com.agentx.agentxbackend.requirement.domain.event.RequirementConfirmedEvent;
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
import org.springframework.dao.DuplicateKeyException;

import java.time.Instant;
import java.util.NoSuchElementException;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RequirementDocCommandServiceTest {

    @Mock
    private RequirementDocRepository requirementDocRepository;
    @Mock
    private RequirementDocVersionRepository requirementDocVersionRepository;
    @Mock
    private DomainEventPublisher domainEventPublisher;
    @InjectMocks
    private RequirementDocCommandService service;

    @Test
    void createRequirementDocShouldCreateDraftDocWithVersionZero() {
        when(requirementDocRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        RequirementDoc doc = service.createRequirementDoc("SES-1", "MVP Requirement");

        assertTrue(doc.docId().startsWith("REQ-"));
        assertEquals("SES-1", doc.sessionId());
        assertEquals(0, doc.currentVersion());
        assertNull(doc.confirmedVersion());
        assertEquals(RequirementDocStatus.DRAFT, doc.status());
        assertEquals("MVP Requirement", doc.title());
        assertNotNull(doc.createdAt());
        assertEquals(doc.createdAt(), doc.updatedAt());
        verify(requirementDocRepository).save(any());
        verifyNoInteractions(requirementDocVersionRepository, domainEventPublisher);
    }

    @Test
    void createRequirementDocShouldRejectBlankSessionId() {
        IllegalArgumentException ex = assertThrows(
            IllegalArgumentException.class,
            () -> service.createRequirementDoc(" ", "title")
        );
        assertTrue(ex.getMessage().contains("sessionId"));
        verifyNoInteractions(requirementDocRepository, requirementDocVersionRepository, domainEventPublisher);
    }

    @Test
    void createRequirementDocShouldRejectBlankTitle() {
        IllegalArgumentException ex = assertThrows(
            IllegalArgumentException.class,
            () -> service.createRequirementDoc("SES-1", " ")
        );
        assertTrue(ex.getMessage().contains("title"));
        verifyNoInteractions(requirementDocRepository, requirementDocVersionRepository, domainEventPublisher);
    }

    @Test
    void createVersionShouldPersistVersionAndMoveDocToInReviewFromDraft() {
        RequirementDoc existingDoc = new RequirementDoc(
            "REQ-1",
            "SES-1",
            0,
            null,
            RequirementDocStatus.DRAFT,
            "Doc",
            Instant.parse("2026-01-01T00:00:00Z"),
            Instant.parse("2026-01-01T00:00:00Z")
        );
        when(requirementDocRepository.findById("REQ-1")).thenReturn(Optional.of(existingDoc));
        when(requirementDocVersionRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(requirementDocRepository.updateAfterVersionAppend(any(), eq(0))).thenReturn(true);

        String content = validMarkdown("MVP flow", "initial user request", "initial draft");
        RequirementDocVersion version = service.createVersion("REQ-1", content, " USER ");

        assertEquals("REQ-1", version.docId());
        assertEquals(1, version.version());
        assertEquals(content, version.content());
        assertEquals("user", version.createdByRole());
        assertNotNull(version.createdAt());

        ArgumentCaptor<RequirementDoc> docCaptor = ArgumentCaptor.forClass(RequirementDoc.class);
        verify(requirementDocRepository).updateAfterVersionAppend(docCaptor.capture(), eq(0));
        RequirementDoc updatedDoc = docCaptor.getValue();
        assertEquals(1, updatedDoc.currentVersion());
        assertNull(updatedDoc.confirmedVersion());
        assertEquals(RequirementDocStatus.IN_REVIEW, updatedDoc.status());
        assertEquals(existingDoc.createdAt(), updatedDoc.createdAt());
        verifyNoInteractions(domainEventPublisher);
    }

    @Test
    void createVersionShouldPreserveConfirmedVersionWhenEditingConfirmedDoc() {
        RequirementDoc existingDoc = new RequirementDoc(
            "REQ-2",
            "SES-1",
            2,
            2,
            RequirementDocStatus.CONFIRMED,
            "Doc2",
            Instant.parse("2026-01-01T00:00:00Z"),
            Instant.parse("2026-01-02T00:00:00Z")
        );
        when(requirementDocRepository.findById("REQ-2")).thenReturn(Optional.of(existingDoc));
        when(requirementDocVersionRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(requirementDocRepository.updateAfterVersionAppend(any(), eq(2))).thenReturn(true);

        String content = validMarkdown("MVP flow", "updated requirement", "revision draft");
        RequirementDocVersion version = service.createVersion("REQ-2", content, "requirement_agent");

        assertEquals(3, version.version());
        assertEquals("requirement_agent", version.createdByRole());

        ArgumentCaptor<RequirementDoc> docCaptor = ArgumentCaptor.forClass(RequirementDoc.class);
        verify(requirementDocRepository).updateAfterVersionAppend(docCaptor.capture(), eq(2));
        RequirementDoc updatedDoc = docCaptor.getValue();
        assertEquals(3, updatedDoc.currentVersion());
        assertEquals(2, updatedDoc.confirmedVersion());
        assertEquals(RequirementDocStatus.IN_REVIEW, updatedDoc.status());
    }

    @Test
    void createVersionShouldRejectUnknownCreatedByRole() {
        String content = validMarkdown("MVP flow", "role validation", "role validation");
        IllegalArgumentException ex = assertThrows(
            IllegalArgumentException.class,
            () -> service.createVersion("REQ-1", content, "architect_agent")
        );
        assertTrue(ex.getMessage().contains("createdByRole"));
        verifyNoInteractions(requirementDocRepository, requirementDocVersionRepository, domainEventPublisher);
    }

    @Test
    void createVersionShouldRejectBlankContent() {
        IllegalArgumentException ex = assertThrows(
            IllegalArgumentException.class,
            () -> service.createVersion("REQ-1", " ", "user")
        );
        assertTrue(ex.getMessage().contains("content"));
        verifyNoInteractions(requirementDocRepository, requirementDocVersionRepository, domainEventPublisher);
    }

    @Test
    void createVersionShouldFailWhenDocDoesNotExist() {
        when(requirementDocRepository.findById("REQ-MISSING")).thenReturn(Optional.empty());
        String content = validMarkdown("MVP flow", "missing doc", "missing doc");

        assertThrows(NoSuchElementException.class, () -> service.createVersion("REQ-MISSING", content, "user"));

        verify(requirementDocRepository).findById("REQ-MISSING");
        verifyNoInteractions(requirementDocVersionRepository, domainEventPublisher);
    }

    @Test
    void createVersionShouldFailWithConflictOnDuplicateVersionInsert() {
        RequirementDoc existingDoc = new RequirementDoc(
            "REQ-C1",
            "SES-1",
            1,
            null,
            RequirementDocStatus.IN_REVIEW,
            "DocC1",
            Instant.parse("2026-01-01T00:00:00Z"),
            Instant.parse("2026-01-02T00:00:00Z")
        );
        String content = validMarkdown("MVP flow", "duplicate insert", "duplicate insert");
        when(requirementDocRepository.findById("REQ-C1")).thenReturn(Optional.of(existingDoc));
        when(requirementDocVersionRepository.save(any()))
            .thenThrow(new DuplicateKeyException("duplicate key"));

        IllegalStateException ex = assertThrows(
            IllegalStateException.class,
            () -> service.createVersion("REQ-C1", content, "user")
        );

        assertTrue(ex.getMessage().contains("Concurrent version conflict"));
        verify(requirementDocRepository, never()).updateAfterVersionAppend(any(), eq(1));
    }

    @Test
    void createVersionShouldFailWithConflictWhenDocVersionUpdateLosesRace() {
        RequirementDoc existingDoc = new RequirementDoc(
            "REQ-C2",
            "SES-1",
            3,
            2,
            RequirementDocStatus.CONFIRMED,
            "DocC2",
            Instant.parse("2026-01-01T00:00:00Z"),
            Instant.parse("2026-01-02T00:00:00Z")
        );
        String content = validMarkdown("MVP flow", "concurrent race", "concurrent race");
        when(requirementDocRepository.findById("REQ-C2")).thenReturn(Optional.of(existingDoc));
        when(requirementDocVersionRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(requirementDocRepository.updateAfterVersionAppend(any(), eq(3))).thenReturn(false);

        IllegalStateException ex = assertThrows(
            IllegalStateException.class,
            () -> service.createVersion("REQ-C2", content, "requirement_agent")
        );
        assertTrue(ex.getMessage().contains("Concurrent version conflict"));
    }

    @Test
    void createVersionShouldRejectInvalidRequirementDocTemplate() {
        RequirementDoc existingDoc = new RequirementDoc(
            "REQ-V1",
            "SES-1",
            1,
            null,
            RequirementDocStatus.IN_REVIEW,
            "DocV1",
            Instant.parse("2026-01-01T00:00:00Z"),
            Instant.parse("2026-01-02T00:00:00Z")
        );
        when(requirementDocRepository.findById("REQ-V1")).thenReturn(Optional.of(existingDoc));

        IllegalArgumentException ex = assertThrows(
            IllegalArgumentException.class,
            () -> service.createVersion("REQ-V1", "# free markdown without schema", "user")
        );
        assertTrue(ex.getMessage().contains("schema_version"));
        verify(requirementDocVersionRepository, never()).save(any());
    }

    @Test
    void confirmShouldSetConfirmedVersionAndPublishEvent() {
        RequirementDoc existingDoc = new RequirementDoc(
            "REQ-3",
            "SES-1",
            3,
            2,
            RequirementDocStatus.IN_REVIEW,
            "Doc3",
            Instant.parse("2026-01-01T00:00:00Z"),
            Instant.parse("2026-01-02T00:00:00Z")
        );
        when(requirementDocRepository.findById("REQ-3")).thenReturn(Optional.of(existingDoc));
        when(requirementDocRepository.update(any())).thenAnswer(invocation -> invocation.getArgument(0));

        RequirementDoc confirmed = service.confirm("REQ-3");

        assertEquals(RequirementDocStatus.CONFIRMED, confirmed.status());
        assertEquals(3, confirmed.confirmedVersion());

        ArgumentCaptor<RequirementConfirmedEvent> eventCaptor = ArgumentCaptor.forClass(RequirementConfirmedEvent.class);
        verify(domainEventPublisher).publish(eventCaptor.capture());
        RequirementConfirmedEvent event = eventCaptor.getValue();
        assertEquals("SES-1", event.sessionId());
        assertEquals("REQ-3", event.docId());
        assertEquals(3, event.confirmedVersion());
        assertEquals(2, event.previousConfirmedVersion());
    }

    @Test
    void confirmShouldRejectDocsWithoutVersion() {
        RequirementDoc existingDoc = new RequirementDoc(
            "REQ-4",
            "SES-1",
            0,
            null,
            RequirementDocStatus.IN_REVIEW,
            "Doc4",
            Instant.parse("2026-01-01T00:00:00Z"),
            Instant.parse("2026-01-02T00:00:00Z")
        );
        when(requirementDocRepository.findById("REQ-4")).thenReturn(Optional.of(existingDoc));

        assertThrows(IllegalStateException.class, () -> service.confirm("REQ-4"));

        verify(requirementDocRepository, never()).update(any());
        verifyNoInteractions(domainEventPublisher);
    }

    @Test
    void confirmShouldRejectDraftStatus() {
        RequirementDoc existingDoc = new RequirementDoc(
            "REQ-5",
            "SES-1",
            1,
            null,
            RequirementDocStatus.DRAFT,
            "Doc5",
            Instant.parse("2026-01-01T00:00:00Z"),
            Instant.parse("2026-01-02T00:00:00Z")
        );
        when(requirementDocRepository.findById("REQ-5")).thenReturn(Optional.of(existingDoc));

        assertThrows(IllegalStateException.class, () -> service.confirm("REQ-5"));

        verify(requirementDocRepository, never()).update(any());
        verifyNoInteractions(domainEventPublisher);
    }

    @Test
    void confirmShouldBeIdempotentWhenAlreadyConfirmedAtCurrentVersion() {
        RequirementDoc existingDoc = new RequirementDoc(
            "REQ-6",
            "SES-1",
            2,
            2,
            RequirementDocStatus.CONFIRMED,
            "Doc6",
            Instant.parse("2026-01-01T00:00:00Z"),
            Instant.parse("2026-01-02T00:00:00Z")
        );
        when(requirementDocRepository.findById("REQ-6")).thenReturn(Optional.of(existingDoc));

        RequirementDoc result = service.confirm("REQ-6");

        assertSame(existingDoc, result);
        verify(requirementDocRepository, never()).update(any());
        verify(domainEventPublisher, never()).publish(any(RequirementConfirmedEvent.class));
    }

    @Test
    void confirmShouldFailWhenDocDoesNotExist() {
        when(requirementDocRepository.findById("REQ-NOT-FOUND")).thenReturn(Optional.empty());

        assertThrows(NoSuchElementException.class, () -> service.confirm("REQ-NOT-FOUND"));

        verify(requirementDocRepository).findById(eq("REQ-NOT-FOUND"));
        verifyNoInteractions(domainEventPublisher);
    }

    private static String validMarkdown(String title, String userInput, String changeLog) {
        String summary = "Summary for: " + userInput;
        return RequirementDocContentPolicy.buildTemplate(title, summary, userInput, changeLog);
    }
}
