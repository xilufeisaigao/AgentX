package com.agentx.platform.controlplane.application;

import com.agentx.platform.domain.agent.AgentControlPolicy;
import com.agentx.platform.domain.agent.AgentDefinition;
import com.agentx.platform.domain.agent.AgentRuntimeProfile;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;

@Service
public class AgentRegistryService {

    private final Map<String, AgentDefinition> agents = new LinkedHashMap<>();

    public AgentRegistryService() {
        seedBuiltIns();
    }

    public List<AgentDefinition> listAgents() {
        return List.copyOf(agents.values());
    }

    public AgentDefinition getAgent(String agentId) {
        AgentDefinition definition = agents.get(normalize(agentId, "agentId"));
        if (definition == null) {
            throw new NoSuchElementException("Agent not found: " + agentId);
        }
        return definition;
    }

    public AgentDefinition register(RegisterAgentCommand command) {
        if (command == null) {
            throw new IllegalArgumentException("command must not be null");
        }
        AgentDefinition definition = new AgentDefinition(
            normalize(command.agentId(), "agentId"),
            normalize(command.displayName(), "displayName"),
            normalize(command.purpose(), "purpose"),
            command.capabilities(),
            command.allowedTools(),
            command.allowedSkills(),
            new AgentRuntimeProfile(
                normalize(command.runtimeType(), "runtimeType"),
                normalize(command.model(), "model"),
                command.maxParallelRuns()
            ),
            new AgentControlPolicy(
                command.architectSuggested(),
                command.autoPoolEligible(),
                true
            ),
            command.enabled()
        );
        agents.put(definition.agentId(), definition);
        return definition;
    }

    private void seedBuiltIns() {
        register(new RegisterAgentCommand(
            "requirement-agent",
            "需求代理",
            "负责需求澄清、对话维持和需求文档整理。",
            Set.of("discovery-chat", "requirement-drafting"),
            Set.of("chat-model"),
            Set.of("requirement-doc"),
            "in-process",
            "gpt-5-class",
            4,
            true,
            false,
            true
        ));
        register(new RegisterAgentCommand(
            "architect-agent",
            "架构代理",
            "负责架构审查、任务拆分、Agent 建议和人工提请分流。",
            Set.of("architecture-review", "task-planning", "agent-design"),
            Set.of("chat-model", "planning-tools"),
            Set.of("dag-planning", "ticket-routing"),
            "in-process",
            "gpt-5-class",
            2,
            true,
            true,
            true
        ));
        register(new RegisterAgentCommand(
            "coding-agent-java",
            "编码代理",
            "负责在受控 worktree 上完成 Java 实现任务。",
            Set.of("code-implementation", "repo-edit", "test-fix"),
            Set.of("git", "maven", "filesystem"),
            Set.of("java-coding"),
            "docker",
            "gpt-5-class",
            8,
            false,
            true,
            true
        ));
        register(new RegisterAgentCommand(
            "verify-agent-java",
            "验证代理",
            "负责运行测试、验证交付候选并输出证据。",
            Set.of("verification", "test-execution"),
            Set.of("git", "maven", "filesystem"),
            Set.of("java-verify"),
            "docker",
            "gpt-5-class",
            8,
            false,
            true,
            true
        ));
    }

    private static String normalize(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }

    public record RegisterAgentCommand(
        String agentId,
        String displayName,
        String purpose,
        Set<String> capabilities,
        Set<String> allowedTools,
        Set<String> allowedSkills,
        String runtimeType,
        String model,
        int maxParallelRuns,
        boolean architectSuggested,
        boolean autoPoolEligible,
        boolean enabled
    ) {
    }
}

