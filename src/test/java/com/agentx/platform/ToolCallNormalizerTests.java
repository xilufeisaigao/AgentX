package com.agentx.platform;

import com.agentx.platform.runtime.tooling.ToolCall;
import com.agentx.platform.runtime.tooling.ToolCallNormalizer;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ToolCallNormalizerTests {

    private final ToolCallNormalizer normalizer = new ToolCallNormalizer(new ObjectMapper());

    @Test
    void shouldGenerateStableCallIdForEquivalentToolCalls() {
        ToolCall first = normalizer.normalize("run-1", new ToolCall(
                "tool-shell",
                "run_command",
                Map.of("commandId", "git-status"),
                "show git status"
        ));
        ToolCall second = normalizer.normalize("run-1", new ToolCall(
                "tool-shell",
                "run_command",
                Map.of("commandId", "git-status"),
                "another summary"
        ));

        assertThat(first.callId()).isEqualTo(second.callId());
    }

    @Test
    void shouldPromoteEmptyReadFileIntoListDirectory() {
        ToolCall normalized = normalizer.normalize("run-1", new ToolCall(
                "tool-filesystem",
                "read_file",
                Map.of("path", " "),
                "browse workspace"
        ));

        assertThat(normalized.operation()).isEqualTo("list_directory");
        assertThat(normalized.arguments()).containsEntry("path", ".");
        assertThat(normalized.callId()).isNotBlank();
    }

    @Test
    void shouldPreserveExplicitCallId() {
        ToolCall normalized = normalizer.normalize("run-1", new ToolCall(
                "call-explicit-1",
                "tool-git",
                "git_head",
                Map.of(),
                "read head"
        ));

        assertThat(normalized.callId()).isEqualTo("call-explicit-1");
    }

    @Test
    void shouldNormalizeSearchTextAliasToGrepText() {
        ToolCall normalized = normalizer.normalize("run-1", new ToolCall(
                "tool-filesystem",
                "search_text",
                Map.of("query", "StudentService"),
                "search student service"
        ));

        assertThat(normalized.operation()).isEqualTo("grep_text");
        assertThat(normalized.arguments()).containsEntry("query", "StudentService");
    }

    @Test
    void shouldFailFastOnMissingRunId() {
        assertThatThrownBy(() -> normalizer.normalize(null, new ToolCall(
                "tool-git",
                "git_head",
                Map.of(),
                "read head"
        ))).isInstanceOf(NullPointerException.class)
                .hasMessageContaining("runId");
    }
}
