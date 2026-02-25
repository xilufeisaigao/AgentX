package com.agentx.agentxbackend.execution.domain.model;

import java.util.List;

public record TaskPackage(
    String runId,
    String taskId,
    String taskTitle,
    String moduleId,
    String contextSnapshotId,
    RunKind runKind,
    String taskTemplateId,
    List<String> requiredToolpacks,
    String taskSkillRef,
    TaskContext taskContext,
    List<String> readScope,
    List<String> writeScope,
    List<String> verifyCommands,
    List<String> stopRules,
    List<String> expectedOutputs,
    GitAlloc git
) {
}
