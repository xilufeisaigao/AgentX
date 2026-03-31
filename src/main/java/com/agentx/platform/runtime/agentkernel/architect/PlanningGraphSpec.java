package com.agentx.platform.runtime.agentkernel.architect;

import com.agentx.platform.domain.shared.model.WriteScope;

import java.util.List;
import java.util.Objects;

public record PlanningGraphSpec(
        String summary,
        List<ModulePlan> modules,
        List<TaskPlan> tasks,
        List<DependencyPlan> dependencies
) {

    public PlanningGraphSpec {
        Objects.requireNonNull(summary, "summary must not be null");
        modules = List.copyOf(Objects.requireNonNull(modules, "modules must not be null"));
        tasks = List.copyOf(Objects.requireNonNull(tasks, "tasks must not be null"));
        dependencies = List.copyOf(Objects.requireNonNull(dependencies, "dependencies must not be null"));
    }

    public record ModulePlan(
            String moduleKey,
            String name,
            String description
    ) {

        public ModulePlan {
            Objects.requireNonNull(moduleKey, "moduleKey must not be null");
            Objects.requireNonNull(name, "name must not be null");
        }
    }

    public record TaskPlan(
            String taskKey,
            String moduleKey,
            String title,
            String objective,
            String taskTemplateId,
            List<WriteScope> writeScopes,
            String capabilityPackId
    ) {

        public TaskPlan {
            Objects.requireNonNull(taskKey, "taskKey must not be null");
            Objects.requireNonNull(moduleKey, "moduleKey must not be null");
            Objects.requireNonNull(title, "title must not be null");
            Objects.requireNonNull(objective, "objective must not be null");
            Objects.requireNonNull(taskTemplateId, "taskTemplateId must not be null");
            writeScopes = List.copyOf(Objects.requireNonNull(writeScopes, "writeScopes must not be null"));
            Objects.requireNonNull(capabilityPackId, "capabilityPackId must not be null");
        }
    }

    public record DependencyPlan(
            String taskKey,
            String dependsOnTaskKey
    ) {

        public DependencyPlan {
            Objects.requireNonNull(taskKey, "taskKey must not be null");
            Objects.requireNonNull(dependsOnTaskKey, "dependsOnTaskKey must not be null");
        }
    }
}
