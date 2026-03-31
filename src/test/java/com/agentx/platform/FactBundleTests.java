package com.agentx.platform;

import com.agentx.platform.runtime.context.FactBundle;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FactBundleTests {

    @Test
    void shouldPreserveNullFactValuesWhileRemainingImmutable() {
        Map<String, Object> sections = new LinkedHashMap<>();
        sections.put("requirementDoc", null);
        sections.put("workflowRun", "workflow-1");

        FactBundle factBundle = new FactBundle(sections);

        assertThat(factBundle.sections())
                .containsEntry("workflowRun", "workflow-1")
                .containsKey("requirementDoc");
        assertThat(factBundle.sections().get("requirementDoc")).isNull();
        assertThatThrownBy(() -> factBundle.sections().put("newKey", "value"))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
