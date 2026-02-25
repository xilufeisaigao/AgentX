package com.agentx.agentxbackend.planning.domain.model;

import java.util.Locale;

public enum TaskTemplateId {
    TMPL_INIT_V0("tmpl.init.v0"),
    TMPL_IMPL_V0("tmpl.impl.v0"),
    TMPL_VERIFY_V0("tmpl.verify.v0"),
    TMPL_BUGFIX_V0("tmpl.bugfix.v0"),
    TMPL_REFACTOR_V0("tmpl.refactor.v0"),
    TMPL_TEST_V0("tmpl.test.v0");

    private final String value;

    TaskTemplateId(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }

    public static TaskTemplateId fromValue(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("taskTemplateId must not be blank");
        }
        String normalized = raw.trim().toLowerCase(Locale.ROOT);
        for (TaskTemplateId candidate : values()) {
            if (candidate.value.equals(normalized)) {
                return candidate;
            }
        }
        throw new IllegalArgumentException("Unsupported taskTemplateId: " + raw);
    }
}
