package com.agentx.agentxbackend.process.application.port.out;

import java.util.List;

public interface ArchitectTaskBreakdownGeneratorPort {

    BreakdownPlan generate(GenerateInput input);

    record GenerateInput(
        String ticketId,
        String sessionId,
        String ticketType,
        String title,
        String requirementDocId,
        Integer requirementDocVer,
        String payloadJson,
        String requirementDocContent,
        List<ArchitectTicketEventContext> recentEvents
    ) {
    }

    record BreakdownPlan(
        String summary,
        List<ModulePlan> modules,
        String provider,
        String model
    ) {
    }

    record ModulePlan(
        String name,
        String description,
        List<TaskPlan> tasks
    ) {
    }

    record TaskPlan(
        String taskKey,
        String title,
        String taskTemplateId,
        List<String> requiredToolpackIds,
        List<String> dependsOnKeys,
        String rationale
    ) {
    }
}
