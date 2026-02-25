package com.agentx.agentxbackend.requirement.application;

import com.agentx.agentxbackend.requirement.application.port.in.RequirementCurrentDoc;
import com.agentx.agentxbackend.requirement.application.port.out.RequirementDocRepository;
import com.agentx.agentxbackend.requirement.application.port.out.RequirementDocVersionRepository;
import com.agentx.agentxbackend.requirement.application.query.RequirementDocQueryService;
import com.agentx.agentxbackend.requirement.domain.model.RequirementDoc;
import com.agentx.agentxbackend.requirement.domain.model.RequirementDocStatus;
import com.agentx.agentxbackend.requirement.domain.model.RequirementDocVersion;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RequirementDocQueryServiceTest {

    @Mock
    private RequirementDocRepository requirementDocRepository;
    @Mock
    private RequirementDocVersionRepository requirementDocVersionRepository;
    @InjectMocks
    private RequirementDocQueryService service;

    @Test
    void findCurrentBySessionIdShouldReturnEmptyWhenNoDoc() {
        when(requirementDocRepository.findLatestBySessionId("SES-1")).thenReturn(Optional.empty());

        Optional<RequirementCurrentDoc> result = service.findCurrentBySessionId("SES-1");

        assertTrue(result.isEmpty());
        verifyNoInteractions(requirementDocVersionRepository);
    }

    @Test
    void findCurrentBySessionIdShouldReturnCurrentDocWithContent() {
        RequirementDoc doc = new RequirementDoc(
            "REQ-1",
            "SES-1",
            3,
            2,
            RequirementDocStatus.IN_REVIEW,
            "Doc title",
            Instant.parse("2026-02-20T00:00:00Z"),
            Instant.parse("2026-02-21T00:00:00Z")
        );
        RequirementDocVersion version = new RequirementDocVersion(
            "REQ-1",
            3,
            "markdown content",
            "user",
            Instant.parse("2026-02-21T00:00:00Z")
        );
        when(requirementDocRepository.findLatestBySessionId("SES-1")).thenReturn(Optional.of(doc));
        when(requirementDocVersionRepository.findByDocIdAndVersion("REQ-1", 3)).thenReturn(Optional.of(version));

        RequirementCurrentDoc result = service.findCurrentBySessionId("SES-1").orElseThrow();

        assertEquals("REQ-1", result.docId());
        assertEquals(3, result.currentVersion());
        assertEquals(2, result.confirmedVersion());
        assertEquals("IN_REVIEW", result.status());
        assertEquals("Doc title", result.title());
        assertEquals("markdown content", result.content());
        assertNotNull(result.updatedAt());
    }

    @Test
    void findCurrentBySessionIdShouldAllowDraftWithoutVersions() {
        RequirementDoc doc = new RequirementDoc(
            "REQ-2",
            "SES-2",
            0,
            null,
            RequirementDocStatus.DRAFT,
            "Draft doc",
            Instant.parse("2026-02-20T00:00:00Z"),
            Instant.parse("2026-02-21T00:00:00Z")
        );
        when(requirementDocRepository.findLatestBySessionId("SES-2")).thenReturn(Optional.of(doc));

        RequirementCurrentDoc result = service.findCurrentBySessionId("SES-2").orElseThrow();

        assertEquals("REQ-2", result.docId());
        assertEquals(0, result.currentVersion());
        assertNull(result.content());
        verifyNoInteractions(requirementDocVersionRepository);
    }

    @Test
    void findCurrentBySessionIdShouldFailWhenCurrentVersionRowMissing() {
        RequirementDoc doc = new RequirementDoc(
            "REQ-3",
            "SES-3",
            4,
            3,
            RequirementDocStatus.CONFIRMED,
            "Doc 3",
            Instant.parse("2026-02-20T00:00:00Z"),
            Instant.parse("2026-02-21T00:00:00Z")
        );
        when(requirementDocRepository.findLatestBySessionId("SES-3")).thenReturn(Optional.of(doc));
        when(requirementDocVersionRepository.findByDocIdAndVersion("REQ-3", 4)).thenReturn(Optional.empty());

        IllegalStateException ex = assertThrows(
            IllegalStateException.class,
            () -> service.findCurrentBySessionId("SES-3")
        );

        assertTrue(ex.getMessage().contains("Current requirement version not found"));
        verify(requirementDocVersionRepository).findByDocIdAndVersion("REQ-3", 4);
    }

    @Test
    void findCurrentBySessionIdShouldRejectBlankSessionId() {
        IllegalArgumentException ex = assertThrows(
            IllegalArgumentException.class,
            () -> service.findCurrentBySessionId(" ")
        );
        assertTrue(ex.getMessage().contains("sessionId"));
        verifyNoInteractions(requirementDocRepository, requirementDocVersionRepository);
    }
}
