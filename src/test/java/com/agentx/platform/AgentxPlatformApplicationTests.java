package com.agentx.platform;

import com.agentx.platform.domain.catalog.port.CatalogStore;
import com.agentx.platform.domain.flow.model.EntryMode;
import com.agentx.platform.domain.flow.model.WorkflowBindingMode;
import com.agentx.platform.domain.flow.model.WorkflowNodeBinding;
import com.agentx.platform.domain.flow.model.WorkflowRun;
import com.agentx.platform.domain.flow.model.WorkflowRunStatus;
import com.agentx.platform.domain.flow.port.FlowStore;
import com.agentx.platform.domain.intake.model.Ticket;
import com.agentx.platform.domain.intake.model.TicketBlockingScope;
import com.agentx.platform.domain.intake.model.TicketStatus;
import com.agentx.platform.domain.intake.model.TicketType;
import com.agentx.platform.domain.intake.port.IntakeStore;
import com.agentx.platform.domain.planning.model.WorkModule;
import com.agentx.platform.domain.planning.model.WorkTask;
import com.agentx.platform.domain.planning.model.WorkTaskStatus;
import com.agentx.platform.domain.planning.port.PlanningStore;
import com.agentx.platform.domain.shared.model.ActorRef;
import com.agentx.platform.domain.shared.model.ActorType;
import com.agentx.platform.domain.shared.model.JsonPayload;
import com.agentx.platform.domain.shared.model.WriteScope;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class AgentxPlatformApplicationTests {

    @Autowired
    private CatalogStore catalogStore;

    @Autowired
    private FlowStore flowStore;

    @Autowired
    private IntakeStore intakeStore;

    @Autowired
    private PlanningStore planningStore;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void shouldReadSeededCatalogFromMysql() {
        assertThat(catalogStore.findAgent("coding-agent-java"))
                .isPresent()
                .get()
                .extracting(agent -> agent.displayName(), agent -> agent.runtimeType())
                .containsExactly("Java 编码代理", "docker");

        assertThat(catalogStore.findCapabilityPack("cap-java-backend-coding")).isPresent();
        assertThat(catalogStore.listSkillToolBindings("skill-java-coding")).isNotEmpty();
    }

    @Test
    void shouldPersistWorkflowAndPlanningArtifacts() {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        String workflowRunId = "test-run-" + suffix;
        String bindingId = "bind-" + suffix;
        String moduleId = "module-" + suffix;
        String taskId = "task-" + suffix;

        WorkflowRun run = new WorkflowRun(
                workflowRunId,
                "builtin-coding-flow",
                "Persistence Smoke " + suffix,
                WorkflowRunStatus.ACTIVE,
                EntryMode.MANUAL,
                false,
                new ActorRef(ActorType.SYSTEM, "test-suite")
        );
        WorkflowNodeBinding binding = new WorkflowNodeBinding(
                bindingId,
                workflowRunId,
                "coding",
                WorkflowBindingMode.DEFAULT,
                "coding-agent-java",
                false
        );
        WorkModule module = new WorkModule(
                moduleId,
                workflowRunId,
                "core-flow",
                "Persistence smoke module"
        );
        WorkTask task = new WorkTask(
                taskId,
                moduleId,
                "实现 healthz",
                "补一条最小的持久化烟雾任务",
                "java-backend-task",
                WorkTaskStatus.PLANNED,
                List.of(new WriteScope("src/main/java"), new WriteScope("src/test/java")),
                null,
                new ActorRef(ActorType.AGENT, "architect-agent")
        );

        try {
            flowStore.saveRun(run);
            flowStore.saveNodeBinding(binding);
            planningStore.saveModule(module);
            planningStore.saveTask(task);

            assertThat(flowStore.findRun(workflowRunId)).contains(run);
            assertThat(flowStore.listNodeBindings(workflowRunId)).contains(binding);
            assertThat(planningStore.listModules(workflowRunId)).contains(module);
            assertThat(planningStore.listTasksByWorkflow(workflowRunId)).contains(task);
        } finally {
            jdbcTemplate.update("delete from work_tasks where task_id = ?", taskId);
            jdbcTemplate.update("delete from work_modules where module_id = ?", moduleId);
            jdbcTemplate.update("delete from workflow_run_node_bindings where binding_id = ?", bindingId);
            jdbcTemplate.update("delete from workflow_runs where workflow_run_id = ?", workflowRunId);
        }
    }

    @Test
    void shouldPersistTicketBlockingScope() {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        String workflowRunId = "test-run-ticket-" + suffix;
        String ticketId = "ticket-" + suffix;

        WorkflowRun run = new WorkflowRun(
                workflowRunId,
                "builtin-coding-flow",
                "Ticket Scope Smoke " + suffix,
                WorkflowRunStatus.ACTIVE,
                EntryMode.MANUAL,
                false,
                new ActorRef(ActorType.HUMAN, "user-ticket-test")
        );
        Ticket ticket = new Ticket(
                ticketId,
                workflowRunId,
                TicketType.CLARIFICATION,
                TicketBlockingScope.GLOBAL_BLOCKING,
                TicketStatus.OPEN,
                "确认 healthz 是否探测数据库",
                new ActorRef(ActorType.AGENT, "architect-agent"),
                new ActorRef(ActorType.HUMAN, "user-ticket-test"),
                "architect",
                null,
                null,
                new JsonPayload("{\"question\":\"是否需要数据库探测\"}")
        );

        try {
            flowStore.saveRun(run);
            intakeStore.saveTicket(ticket);

            assertThat(intakeStore.listTicketsForWorkflow(workflowRunId))
                    .singleElement()
                    .satisfies(saved -> {
                        assertThat(saved.ticketId()).isEqualTo(ticketId);
                        assertThat(saved.workflowRunId()).isEqualTo(workflowRunId);
                        assertThat(saved.type()).isEqualTo(TicketType.CLARIFICATION);
                        assertThat(saved.blockingScope()).isEqualTo(TicketBlockingScope.GLOBAL_BLOCKING);
                        assertThat(saved.status()).isEqualTo(TicketStatus.OPEN);
                    });
            assertThat(intakeStore.listOpenTickets(workflowRunId))
                    .singleElement()
                    .extracting(Ticket::blockingScope)
                    .isEqualTo(TicketBlockingScope.GLOBAL_BLOCKING);
        } finally {
            jdbcTemplate.update("delete from tickets where ticket_id = ?", ticketId);
            jdbcTemplate.update("delete from workflow_runs where workflow_run_id = ?", workflowRunId);
        }
    }

}
