package com.agentx.platform.support;

import com.agentx.platform.runtime.application.workflow.TaskTemplateCatalog;
import com.agentx.platform.runtime.application.workflow.WorkflowProfileRef;
import com.agentx.platform.runtime.application.workflow.profile.ActiveStackProfileSnapshot;
import com.agentx.platform.runtime.application.workflow.profile.StackProfileRegistry;

public final class TestStackProfiles {

    public static final String DEFAULT_PROFILE_ID = StackProfileRegistry.DEFAULT_PROFILE_ID;
    private static final StackProfileRegistry REGISTRY = new StackProfileRegistry();

    private TestStackProfiles() {
    }

    public static StackProfileRegistry registry() {
        return REGISTRY;
    }

    public static TaskTemplateCatalog taskTemplateCatalog() {
        return new TaskTemplateCatalog(REGISTRY);
    }

    public static ActiveStackProfileSnapshot defaultProfile() {
        return REGISTRY.resolveRequired(DEFAULT_PROFILE_ID);
    }

    public static WorkflowProfileRef defaultProfileRef() {
        return defaultProfile().toProfileRef();
    }
}
