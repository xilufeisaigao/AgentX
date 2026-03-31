package com.agentx.platform.runtime.application.workflow.profile;

import com.agentx.platform.domain.shared.model.WriteScope;
import com.agentx.platform.runtime.application.workflow.WorkflowProfileRef;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

public record ActiveStackProfileSnapshot(
        StackProfileManifest manifest,
        String digest
) {

    public ActiveStackProfileSnapshot {
        Objects.requireNonNull(manifest, "manifest must not be null");
        Objects.requireNonNull(digest, "digest must not be null");
    }

    public String profileId() {
        return manifest.identity().profileId();
    }

    public String displayName() {
        return manifest.identity().displayName();
    }

    public String version() {
        return manifest.identity().version();
    }

    public WorkflowProfileRef toProfileRef() {
        return new WorkflowProfileRef(profileId(), displayName(), version(), digest);
    }

    public List<StackProfileManifest.TaskTemplateSpec> taskTemplates() {
        return manifest.taskTemplates();
    }

    public Optional<StackProfileManifest.TaskTemplateSpec> findTaskTemplate(String taskTemplateId) {
        return manifest.taskTemplates().stream()
                .filter(template -> template.taskTemplateId().equals(taskTemplateId))
                .findFirst();
    }

    public Optional<StackProfileManifest.CapabilityRuntimeSpec> findCapabilityRuntime(String capabilityPackId) {
        return manifest.capabilityRuntime().stream()
                .filter(runtime -> runtime.capabilityPackId().equals(capabilityPackId))
                .findFirst();
    }

    public String nodeAgentId(String nodeId) {
        return manifest.nodeAgents().get(nodeId);
    }

    public List<String> architectRules() {
        return manifest.prompts().architectRules();
    }

    public List<String> codingRules() {
        return manifest.prompts().codingRules();
    }

    public List<String> verifyRules() {
        return manifest.prompts().verifyRules();
    }

    public Map<String, Long> inspectReviewBundle(Path reviewBundleRoot) {
        if (reviewBundleRoot == null || Files.notExists(reviewBundleRoot)) {
            return Map.of();
        }
        Map<String, Long> counts = new LinkedHashMap<>();
        try (var stream = Files.walk(reviewBundleRoot)) {
            for (Path candidate : stream.filter(Files::isRegularFile).toList()) {
                String relativePath = reviewBundleRoot.relativize(candidate).toString().replace('\\', '/');
                classifyPath(relativePath).ifPresent(role -> counts.merge(role, 1L, Long::sum));
            }
        } catch (IOException exception) {
            return Map.of();
        }
        return Map.copyOf(counts);
    }

    public Optional<String> classifyPath(String relativePath) {
        String normalized = relativePath.replace('\\', '/');
        for (Map.Entry<String, List<String>> roleEntry : manifest.eval().roleGlobs().entrySet()) {
            for (String glob : roleEntry.getValue()) {
                if (matchesGlob(glob, normalized)) {
                    return Optional.of(roleEntry.getKey());
                }
            }
        }
        return Optional.empty();
    }

    public List<WriteScope> writeScopes(StackProfileManifest.TaskTemplateSpec templateSpec) {
        return templateSpec.defaultWriteScopes().stream()
                .map(WriteScope::new)
                .toList();
    }

    public boolean matchesRequiredArtifactRoles(Map<String, Long> roleCounts) {
        return manifest.eval().requiredArtifactRoles().stream()
                .allMatch(role -> roleCounts.getOrDefault(role, 0L) > 0);
    }

    public String workspaceShapeSummary() {
        if (!manifest.identity().packageManager().isBlank() && !manifest.identity().workspaceShape().isBlank()) {
            return manifest.identity().packageManager() + " / " + manifest.identity().workspaceShape();
        }
        if (!manifest.identity().workspaceShape().isBlank()) {
            return manifest.identity().workspaceShape();
        }
        return manifest.identity().packageManager();
    }

    private boolean matchesGlob(String globPattern, String normalizedPath) {
        String regex = globToRegex(globPattern.toLowerCase(Locale.ROOT));
        return Pattern.compile(regex).matcher(normalizedPath.toLowerCase(Locale.ROOT)).matches();
    }

    private String globToRegex(String globPattern) {
        StringBuilder regex = new StringBuilder("^");
        for (int index = 0; index < globPattern.length(); index++) {
            char current = globPattern.charAt(index);
            if (current == '*') {
                boolean doubleStar = index + 1 < globPattern.length() && globPattern.charAt(index + 1) == '*';
                regex.append(doubleStar ? ".*" : "[^/]*");
                if (doubleStar) {
                    index++;
                }
                continue;
            }
            if ("\\.[]{}()+-^$|".indexOf(current) >= 0) {
                regex.append('\\');
            }
            regex.append(current);
        }
        regex.append('$');
        return regex.toString();
    }
}
