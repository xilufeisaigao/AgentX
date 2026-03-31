package com.agentx.platform.runtime.application.workflow.profile;

import com.agentx.platform.domain.catalog.port.CatalogStore;
import com.agentx.platform.runtime.application.workflow.WorkflowProfileRef;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class StackProfileRegistry {

    public static final String DEFAULT_PROFILE_ID = "java-backend-maven";
    private static final String RESOURCE_PATTERN = "classpath*:stack-profiles/*.json";

    private final ObjectMapper objectMapper;
    private final ObjectMapper digestObjectMapper;
    private final CatalogStore catalogStore;
    private final Map<String, StackProfileManifest> manifestsById;
    private final Map<String, ActiveStackProfileSnapshot> activeProfiles = new ConcurrentHashMap<>();

    public StackProfileRegistry() {
        this(new ObjectMapper().findAndRegisterModules(), null, true);
    }

    @Autowired
    public StackProfileRegistry(ObjectMapper objectMapper, CatalogStore catalogStore) {
        this(objectMapper, catalogStore, true);
    }

    private StackProfileRegistry(ObjectMapper objectMapper, CatalogStore catalogStore, boolean ignored) {
        this.objectMapper = objectMapper.copy().findAndRegisterModules();
        this.digestObjectMapper = this.objectMapper.copy()
                .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true)
                .configure(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY, true);
        this.catalogStore = catalogStore;
        this.manifestsById = Map.copyOf(loadManifests());
    }

    public ActiveStackProfileSnapshot resolveRequired(String profileId) {
        String resolvedProfileId = profileId == null || profileId.isBlank() ? DEFAULT_PROFILE_ID : profileId;
        if (!manifestsById.containsKey(resolvedProfileId)) {
            throw new IllegalArgumentException("stack profile not found: " + resolvedProfileId);
        }
        return activeProfiles.computeIfAbsent(resolvedProfileId, this::activate);
    }

    public WorkflowProfileRef defaultProfileRef() {
        return resolveRequired(DEFAULT_PROFILE_ID).toProfileRef();
    }

    public List<String> listProfileIds() {
        return manifestsById.keySet().stream().sorted().toList();
    }

    private ActiveStackProfileSnapshot activate(String profileId) {
        StackProfileManifest manifest = manifestsById.get(profileId);
        validate(manifest);
        return new ActiveStackProfileSnapshot(manifest, digest(manifest));
    }

    private Map<String, StackProfileManifest> loadManifests() {
        try {
            Map<String, StackProfileManifest> manifests = new LinkedHashMap<>();
            Resource[] resources = new PathMatchingResourcePatternResolver().getResources(RESOURCE_PATTERN);
            for (Resource resource : resources) {
                try (InputStream inputStream = resource.getInputStream()) {
                    StackProfileManifest manifest = objectMapper.readValue(inputStream, StackProfileManifest.class);
                    manifests.put(manifest.identity().profileId(), manifest);
                }
            }
            if (manifests.isEmpty()) {
                throw new IllegalStateException("no stack profile manifests found under stack-profiles/");
            }
            return manifests;
        } catch (IOException exception) {
            throw new IllegalStateException("failed to load stack profile manifests", exception);
        }
    }

    private void validate(StackProfileManifest manifest) {
        requireNonBlank(manifest.identity().profileId(), "profileId");
        requireNonBlank(manifest.identity().displayName(), "displayName");
        requireNonBlank(manifest.identity().version(), "version");

        Set<String> taskTemplateIds = new LinkedHashSet<>();
        for (StackProfileManifest.TaskTemplateSpec template : manifest.taskTemplates()) {
            requireNonBlank(template.taskTemplateId(), "taskTemplateId");
            requireNonBlank(template.capabilityPackId(), "capabilityPackId");
            if (!taskTemplateIds.add(template.taskTemplateId())) {
                throw new IllegalStateException("duplicate taskTemplateId in profile " + manifest.identity().profileId() + ": " + template.taskTemplateId());
            }
            if (catalogStore != null && catalogStore.findCapabilityPack(template.capabilityPackId()).isEmpty()) {
                throw new IllegalStateException(
                        "profile " + manifest.identity().profileId() + " references missing capability pack " + template.capabilityPackId()
                );
            }
        }

        for (String allowedTemplateId : manifest.planner().allowedTaskTemplateIds()) {
            if (!taskTemplateIds.contains(allowedTemplateId)) {
                throw new IllegalStateException(
                        "profile " + manifest.identity().profileId() + " planner references unknown template " + allowedTemplateId
                );
            }
        }

        Set<String> commandIds = new LinkedHashSet<>();
        for (StackProfileManifest.CapabilityRuntimeSpec runtime : manifest.capabilityRuntime()) {
            requireNonBlank(runtime.capabilityPackId(), "capabilityRuntime.capabilityPackId");
            requireNonBlank(runtime.runtimeImage(), "capabilityRuntime.runtimeImage");
            if (catalogStore != null && catalogStore.findCapabilityPack(runtime.capabilityPackId()).isEmpty()) {
                throw new IllegalStateException(
                        "profile " + manifest.identity().profileId() + " runtime references missing capability pack " + runtime.capabilityPackId()
                );
            }
            commandIds.addAll(runtime.allowedCommands().keySet());
            for (String commandId : runtime.postDeliveryCommandIds()) {
                if (!runtime.allowedCommands().containsKey(commandId)) {
                    throw new IllegalStateException(
                            "profile " + manifest.identity().profileId() + " references unknown post-delivery commandId " + commandId
                    );
                }
            }
            for (String commandId : runtime.verifyCommandIds()) {
                if (!runtime.allowedCommands().containsKey(commandId)) {
                    throw new IllegalStateException(
                            "profile " + manifest.identity().profileId() + " references unknown verify commandId " + commandId
                    );
                }
            }
        }

        for (Map.Entry<String, String> nodeAgent : manifest.nodeAgents().entrySet()) {
            requireNonBlank(nodeAgent.getKey(), "nodeAgents.key");
            requireNonBlank(nodeAgent.getValue(), "nodeAgents.value");
            if (catalogStore != null && catalogStore.findAgent(nodeAgent.getValue()).isEmpty()) {
                throw new IllegalStateException(
                        "profile " + manifest.identity().profileId() + " references missing agent " + nodeAgent.getValue()
                );
            }
        }

        for (String requiredRole : manifest.eval().requiredArtifactRoles()) {
            if (!manifest.eval().roleGlobs().containsKey(requiredRole)) {
                throw new IllegalStateException(
                        "profile " + manifest.identity().profileId() + " requires unknown artifact role " + requiredRole
                );
            }
        }
        validateEvalCommandIds(manifest, commandIds);
    }

    private void validateEvalCommandIds(StackProfileManifest manifest, Set<String> commandIds) {
        validateEvalCommandIds(manifest, "buildCommandIds", manifest.eval().buildCommandIds(), commandIds);
        validateEvalCommandIds(manifest, "apiCommandIds", manifest.eval().apiCommandIds(), commandIds);
        validateEvalCommandIds(manifest, "integrationCommandIds", manifest.eval().integrationCommandIds(), commandIds);
    }

    private void validateEvalCommandIds(
            StackProfileManifest manifest,
            String fieldName,
            List<String> requiredCommandIds,
            Set<String> availableCommandIds
    ) {
        for (String commandId : requiredCommandIds) {
            if (!availableCommandIds.contains(commandId)) {
                throw new IllegalStateException(
                        "profile " + manifest.identity().profileId() + " " + fieldName + " references unknown commandId " + commandId
                );
            }
        }
    }

    private String digest(StackProfileManifest manifest) {
        try {
            MessageDigest messageDigest = MessageDigest.getInstance("SHA-256");
            messageDigest.update(digestObjectMapper.writeValueAsString(manifest).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(messageDigest.digest());
        } catch (NoSuchAlgorithmException | JsonProcessingException exception) {
            throw new IllegalStateException("failed to compute stack profile digest for " + manifest.identity().profileId(), exception);
        }
    }

    private void requireNonBlank(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("stack profile field must not be blank: " + fieldName);
        }
    }
}
