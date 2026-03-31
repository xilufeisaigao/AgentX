package com.agentx.platform.runtime.application.workflow;

import com.agentx.platform.domain.shared.model.WriteScope;
import com.agentx.platform.runtime.application.workflow.profile.ActiveStackProfileSnapshot;
import com.agentx.platform.runtime.application.workflow.profile.StackProfileManifest;
import com.agentx.platform.runtime.application.workflow.profile.StackProfileRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Component
public class TaskTemplateCatalog {

    private final StackProfileRegistry stackProfileRegistry;

    public TaskTemplateCatalog() {
        this(new StackProfileRegistry());
    }

    @Autowired
    public TaskTemplateCatalog(StackProfileRegistry stackProfileRegistry) {
        this.stackProfileRegistry = stackProfileRegistry;
    }

    public ActiveStackProfileSnapshot defaultProfile() {
        return stackProfileRegistry.resolveRequired(StackProfileRegistry.DEFAULT_PROFILE_ID);
    }

    public ActiveStackProfileSnapshot resolveProfile(String profileId) {
        return stackProfileRegistry.resolveRequired(profileId);
    }

    public Optional<TaskTemplateDefinition> find(String profileId, String templateId) {
        return resolveProfile(profileId).findTaskTemplate(templateId)
                .map(this::toDefinition);
    }

    public Optional<TaskTemplateDefinition> find(String templateId) {
        return find(StackProfileRegistry.DEFAULT_PROFILE_ID, templateId);
    }

    public Collection<TaskTemplateDefinition> listTemplates(String profileId) {
        return resolveProfile(profileId).taskTemplates().stream()
                .map(this::toDefinition)
                .toList();
    }

    public Collection<TaskTemplateDefinition> listTemplates() {
        return listTemplates(StackProfileRegistry.DEFAULT_PROFILE_ID);
    }

    public boolean allowsWriteScope(TaskTemplateDefinition templateDefinition, WriteScope candidate) {
        return templateDefinition.defaultWriteScopes().stream()
                .map(WriteScope::path)
                .anyMatch(scope -> candidate.path().equals(scope) || candidate.path().startsWith(scope + "/"));
    }

    public StackProfileRegistry stackProfileRegistry() {
        return stackProfileRegistry;
    }

    private TaskTemplateDefinition toDefinition(StackProfileManifest.TaskTemplateSpec templateSpec) {
        return new TaskTemplateDefinition(
                templateSpec.taskTemplateId(),
                templateSpec.capabilityPackId(),
                templateSpec.defaultWriteScopes().stream().map(WriteScope::new).toList(),
                templateSpec.deliveryKind(),
                templateSpec.verifyExpectations()
        );
    }

    public record TaskTemplateDefinition(
            String templateId,
            String capabilityPackId,
            List<WriteScope> defaultWriteScopes,
            String deliveryKind,
            List<String> verifyExpectations
    ) {
    }
}
