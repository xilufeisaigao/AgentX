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
import com.agentx.agentxbackend.requirement.domain.model.RequirementDocVersion;
import com.agentx.agentxbackend.requirement.domain.policy.RequirementDocContentPolicy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.regex.Pattern;

@Service
public class RequirementAgentDraftService implements RequirementAgentUseCase {

    private static final int MAX_DISCOVERY_HISTORY_MESSAGES = 24;
    private static final Pattern DRAFT_TRIGGER_PATTERN = Pattern.compile(
        "(?iu)(确认需求|提交需求|开始.*需求文档|生成.*需求文档|确认并生成|confirm requirement|start drafting|create requirement doc)"
    );

    private final RequirementDocUseCase requirementDocUseCase;
    private final RequirementDocRepository requirementDocRepository;
    private final RequirementDocVersionRepository requirementDocVersionRepository;
    private final RequirementConversationHistoryRepository conversationHistoryRepository;
    private final RequirementDraftGeneratorPort draftGeneratorPort;
    private final DomainEventPublisher domainEventPublisher;

    public RequirementAgentDraftService(
        RequirementDocUseCase requirementDocUseCase,
        RequirementDocRepository requirementDocRepository,
        RequirementDocVersionRepository requirementDocVersionRepository,
        RequirementConversationHistoryRepository conversationHistoryRepository,
        RequirementDraftGeneratorPort draftGeneratorPort,
        DomainEventPublisher domainEventPublisher
    ) {
        this.requirementDocUseCase = requirementDocUseCase;
        this.requirementDocRepository = requirementDocRepository;
        this.requirementDocVersionRepository = requirementDocVersionRepository;
        this.conversationHistoryRepository = conversationHistoryRepository;
        this.draftGeneratorPort = draftGeneratorPort;
        this.domainEventPublisher = domainEventPublisher;
    }

    @Override
    @Transactional
    public DraftResult generateDraft(GenerateDraftCommand command) {
        if (command == null) {
            throw new IllegalArgumentException("command must not be null");
        }
        requireNotBlank(command.sessionId(), "sessionId");
        requireNotBlank(command.userInput(), "userInput");
        String normalizedUserInput = command.userInput().trim();

        String normalizedDocId = normalizeNullable(command.docId());
        RequirementDoc targetDoc = null;
        String existingContent = null;
        String effectiveTitle = normalizeNullable(command.title());

        if (normalizedDocId != null) {
            targetDoc = requirementDocRepository.findById(normalizedDocId)
                .orElseThrow(() -> new NoSuchElementException("Requirement doc not found: " + normalizedDocId));
            if (!targetDoc.sessionId().equals(command.sessionId())) {
                throw new IllegalArgumentException(
                    "docId does not belong to sessionId: " + normalizedDocId + ", sessionId=" + command.sessionId()
                );
            }
            effectiveTitle = targetDoc.title();
            if (targetDoc.currentVersion() > 0) {
                existingContent = requirementDocVersionRepository
                    .findByDocIdAndVersion(normalizedDocId, targetDoc.currentVersion())
                    .map(RequirementDocVersion::content)
                    .orElse(null);
            }
            return reviseExistingDraft(command, targetDoc, effectiveTitle, existingContent, normalizedUserInput);
        }

        requireNotBlank(effectiveTitle, "title");
        return handleDiscoveryStage(command, effectiveTitle, normalizedUserInput);
    }

    private DraftResult reviseExistingDraft(
        GenerateDraftCommand command,
        RequirementDoc targetDoc,
        String effectiveTitle,
        String existingContent,
        String normalizedUserInput
    ) {
        RequirementDraftGeneratorPort.ConversationAssessment assessment = draftGeneratorPort.assessConversation(
            new RequirementDraftGeneratorPort.AssessConversationInput(
                effectiveTitle,
                normalizedUserInput,
                List.of(),
                false
            )
        );
        if (assessment.needsHandoff()) {
            Integer requirementDocVersion = targetDoc.currentVersion() > 0 ? targetDoc.currentVersion() : null;
            publishHandoffEvent(
                command.sessionId(),
                requirementDocVersion == null ? null : targetDoc.docId(),
                requirementDocVersion,
                normalizedUserInput,
                assessment.handoffReason()
            );
            return buildHandoffResult(assessment);
        }

        RequirementDraftGeneratorPort.GeneratedDraft generated = draftGeneratorPort.generate(
            new RequirementDraftGeneratorPort.GenerateDraftInput(
                effectiveTitle,
                normalizedUserInput,
                existingContent,
                List.of()
            )
        );
        String normalizedContent = normalizeGeneratedContent(generated.content());
        RequirementDocContentPolicy.validateOrThrow(normalizedContent);

        if (!command.persist()) {
            return new DraftResult(
                targetDoc,
                null,
                normalizedContent,
                false,
                generated.provider(),
                generated.model(),
                RequirementAgentPhase.DRAFT_REVISED,
                "已生成修订草稿（未持久化）。",
                true,
                List.of()
            );
        }

        RequirementDocVersion version = requirementDocUseCase.createVersion(
            targetDoc.docId(),
            normalizedContent,
            "requirement_agent"
        );
        RequirementDoc latestDoc = requirementDocRepository.findById(targetDoc.docId())
            .orElseThrow(() -> new IllegalStateException("Requirement doc disappeared after createVersion"));

        return new DraftResult(
            latestDoc,
            version,
            normalizedContent,
            true,
            generated.provider(),
            generated.model(),
            RequirementAgentPhase.DRAFT_REVISED,
            "已根据你的反馈修订需求文档。",
            true,
            List.of()
        );
    }

    private DraftResult handleDiscoveryStage(
        GenerateDraftCommand command,
        String effectiveTitle,
        String normalizedUserInput
    ) {
        boolean userWantsDraft = containsDraftTrigger(normalizedUserInput);
        List<RequirementDraftGeneratorPort.ConversationTurn> history = loadDiscoveryHistory(command.sessionId());

        RequirementDraftGeneratorPort.ConversationAssessment assessment = draftGeneratorPort.assessConversation(
            new RequirementDraftGeneratorPort.AssessConversationInput(
                effectiveTitle,
                normalizedUserInput,
                history,
                userWantsDraft
            )
        );
        String assistantMessage = normalizeNullable(assessment.assistantMessage());
        appendDiscoveryHistory(command.sessionId(), "user", normalizedUserInput);
        if (assistantMessage != null) {
            appendDiscoveryHistory(command.sessionId(), "assistant", assistantMessage);
        }

        if (assessment.needsHandoff()) {
            publishHandoffEvent(command.sessionId(), null, null, normalizedUserInput, assessment.handoffReason());
            conversationHistoryRepository.clear(command.sessionId());
            return buildHandoffResult(assessment);
        }

        if (!userWantsDraft) {
            RequirementAgentPhase phase = assessment.readyForDraft()
                ? RequirementAgentPhase.READY_TO_DRAFT
                : RequirementAgentPhase.DISCOVERY_CHAT;
            return buildConversationOnlyResult(assessment, phase);
        }

        if (!assessment.readyForDraft()) {
            return buildConversationOnlyResult(assessment, RequirementAgentPhase.NEED_MORE_INFO);
        }

        RequirementDraftGeneratorPort.GeneratedDraft generated = draftGeneratorPort.generate(
            new RequirementDraftGeneratorPort.GenerateDraftInput(
                effectiveTitle,
                normalizedUserInput,
                null,
                buildHistoryForGeneration(history, normalizedUserInput)
            )
        );
        String normalizedContent = normalizeGeneratedContent(generated.content());
        RequirementDocContentPolicy.validateOrThrow(normalizedContent);

        if (!command.persist()) {
            return new DraftResult(
                null,
                null,
                normalizedContent,
                false,
                generated.provider(),
                generated.model(),
                RequirementAgentPhase.DRAFT_CREATED,
                "已生成初版需求文档草稿（未持久化）。",
                true,
                List.of()
            );
        }

        RequirementDoc createdDoc = requirementDocUseCase.createRequirementDoc(command.sessionId(), effectiveTitle);
        RequirementDocVersion version = requirementDocUseCase.createVersion(
            createdDoc.docId(),
            normalizedContent,
            "requirement_agent"
        );
        RequirementDoc latestDoc = requirementDocRepository.findById(createdDoc.docId())
            .orElseThrow(() -> new IllegalStateException("Requirement doc disappeared after createVersion"));
        conversationHistoryRepository.clear(command.sessionId());

        return new DraftResult(
            latestDoc,
            version,
            normalizedContent,
            true,
            generated.provider(),
            generated.model(),
            RequirementAgentPhase.DRAFT_CREATED,
            "已生成初版需求文档，请审阅并继续提修改意见。",
            true,
            List.of()
        );
    }

    private static String normalizeGeneratedContent(String content) {
        requireNotBlank(content, "generatedContent");
        String trimmed = content.trim();
        if (trimmed.startsWith("```")) {
            int firstLineEnd = trimmed.indexOf('\n');
            if (firstLineEnd > 0) {
                trimmed = trimmed.substring(firstLineEnd + 1);
            }
            if (trimmed.endsWith("```")) {
                trimmed = trimmed.substring(0, trimmed.length() - 3);
            }
        }
        return trimmed.trim();
    }

    private static String normalizeNullable(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static void requireNotBlank(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }

    private static boolean containsDraftTrigger(String userInput) {
        return DRAFT_TRIGGER_PATTERN.matcher(userInput).find();
    }

    private DraftResult buildConversationOnlyResult(
        RequirementDraftGeneratorPort.ConversationAssessment assessment,
        RequirementAgentPhase phase
    ) {
        List<String> missingInformation = assessment.missingInformation() == null
            ? List.of()
            : List.copyOf(assessment.missingInformation());
        return new DraftResult(
            null,
            null,
            null,
            false,
            assessment.provider(),
            assessment.model(),
            phase,
            assessment.assistantMessage(),
            assessment.readyForDraft(),
            missingInformation
        );
    }

    private DraftResult buildHandoffResult(RequirementDraftGeneratorPort.ConversationAssessment assessment) {
        StringBuilder messageBuilder = new StringBuilder(
            "该请求属于架构/实现层变更，已创建 HANDOFF 工单并转交架构师处理。"
        );
        messageBuilder.append("后续若需要你决策，架构师会发起 DECISION/CLARIFICATION 工单。");
        return new DraftResult(
            null,
            null,
            null,
            false,
            assessment.provider(),
            assessment.model(),
            RequirementAgentPhase.HANDOFF_CREATED,
            messageBuilder.toString(),
            false,
            List.of()
        );
    }

    private void publishHandoffEvent(
        String sessionId,
        String requirementDocId,
        Integer requirementDocVersion,
        String userInput,
        String reason
    ) {
        domainEventPublisher.publish(new RequirementHandoffRequestedEvent(
            sessionId,
            requirementDocId,
            requirementDocVersion,
            userInput,
            normalizeNullable(reason)
        ));
    }

    private List<RequirementDraftGeneratorPort.ConversationTurn> loadDiscoveryHistory(String sessionId) {
        List<RequirementDraftGeneratorPort.ConversationTurn> raw = conversationHistoryRepository.load(sessionId);
        if (raw == null || raw.isEmpty()) {
            return List.of();
        }
        ArrayList<RequirementDraftGeneratorPort.ConversationTurn> normalized = new ArrayList<>(raw.size());
        for (RequirementDraftGeneratorPort.ConversationTurn turn : raw) {
            if (turn == null) {
                continue;
            }
            String role = normalizeNullable(turn.role());
            String content = normalizeNullable(turn.content());
            if (role == null || content == null) {
                continue;
            }
            normalized.add(new RequirementDraftGeneratorPort.ConversationTurn(role, content));
        }
        return normalized.isEmpty() ? List.of() : List.copyOf(normalized);
    }

    private void appendDiscoveryHistory(String sessionId, String role, String content) {
        String normalizedRole = normalizeNullable(role);
        String normalizedContent = normalizeNullable(content);
        if (normalizedRole == null || normalizedContent == null) {
            return;
        }
        conversationHistoryRepository.append(
            sessionId,
            new RequirementDraftGeneratorPort.ConversationTurn(normalizedRole, normalizedContent)
        );
    }

    private static List<RequirementDraftGeneratorPort.ConversationTurn> buildHistoryForGeneration(
        List<RequirementDraftGeneratorPort.ConversationTurn> history,
        String normalizedUserInput
    ) {
        ArrayDeque<RequirementDraftGeneratorPort.ConversationTurn> turns = new ArrayDeque<>(MAX_DISCOVERY_HISTORY_MESSAGES);
        if (history != null) {
            for (RequirementDraftGeneratorPort.ConversationTurn turn : history) {
                if (turn == null) {
                    continue;
                }
                turns.addLast(turn);
                while (turns.size() > MAX_DISCOVERY_HISTORY_MESSAGES - 1) {
                    turns.removeFirst();
                }
            }
        }
        turns.addLast(new RequirementDraftGeneratorPort.ConversationTurn("user", normalizedUserInput));
        return List.copyOf(turns);
    }
}
