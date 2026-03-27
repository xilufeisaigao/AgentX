package com.agentx.platform;

import com.agentx.platform.domain.execution.model.GitWorkspaceStatus;
import com.agentx.platform.domain.execution.model.TaskRunStatus;
import com.agentx.platform.domain.flow.model.WorkflowRunStatus;
import com.agentx.platform.domain.intake.model.RequirementStatus;
import com.agentx.platform.domain.intake.model.TicketStatus;
import com.agentx.platform.domain.planning.model.WorkTaskStatus;
import com.agentx.platform.domain.shared.model.ActorRef;
import com.agentx.platform.domain.shared.model.ActorType;
import com.agentx.platform.runtime.application.workflow.AnswerTicketCommand;
import com.agentx.platform.runtime.application.workflow.FixedCodingWorkflowUseCase;
import com.agentx.platform.runtime.application.workflow.StartCodingWorkflowCommand;
import com.agentx.platform.runtime.application.workflow.WorkflowRuntimeSnapshot;
import com.agentx.platform.runtime.application.workflow.WorkflowScenario;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class FixedCodingWorkflowIntegrationTests {

    @Autowired
    private FixedCodingWorkflowUseCase workflowUseCase;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void shouldCompleteHappyPathWorkflow() {
        String workflowRunId = workflowUseCase.start(new StartCodingWorkflowCommand(
                "Happy Path Runtime",
                "Happy Path Requirement",
                "实现一个最小固定主链 happy path。",
                new ActorRef(ActorType.HUMAN, "happy-user"),
                false,
                WorkflowScenario.defaultScenario()
        ));

        try {
            WorkflowRuntimeSnapshot snapshot = workflowUseCase.runUntilStable(workflowRunId);

            assertThat(snapshot.workflowRun().status()).isEqualTo(WorkflowRunStatus.COMPLETED);
            assertThat(snapshot.requirementDoc().status()).isEqualTo(RequirementStatus.CONFIRMED);
            assertThat(snapshot.tasks()).isNotEmpty();
            assertThat(snapshot.tasks()).anyMatch(task -> task.status() == WorkTaskStatus.DONE);
            assertThat(snapshot.taskRuns()).anyMatch(run -> run.status() == TaskRunStatus.SUCCEEDED);
            assertThat(snapshot.workspaces()).anyMatch(workspace ->
                    workspace.status() == GitWorkspaceStatus.MERGED || workspace.status() == GitWorkspaceStatus.CLEANED);
            assertThat(snapshot.tickets()).noneMatch(ticket ->
                    ticket.status() != TicketStatus.RESOLVED && ticket.status() != TicketStatus.CANCELED);
        } finally {
            cleanupWorkflow(workflowRunId);
        }
    }

    @Test
    void shouldResumeFromHumanClarification() {
        String workflowRunId = workflowUseCase.start(new StartCodingWorkflowCommand(
                "Clarification Runtime",
                "Clarification Requirement",
                "第一次编码时需要人工澄清的固定主链场景。",
                new ActorRef(ActorType.HUMAN, "clarification-user"),
                false,
                new WorkflowScenario(true, false, false)
        ));

        try {
            WorkflowRuntimeSnapshot waitingSnapshot = workflowUseCase.runUntilStable(workflowRunId);
            assertThat(waitingSnapshot.workflowRun().status()).isEqualTo(WorkflowRunStatus.WAITING_ON_HUMAN);
            assertThat(waitingSnapshot.tasks()).anyMatch(task -> task.status() == WorkTaskStatus.BLOCKED);
            assertThat(waitingSnapshot.taskRuns()).anyMatch(run -> run.status() == TaskRunStatus.CANCELED);
            assertThat(waitingSnapshot.tickets()).anyMatch(ticket ->
                    ticket.status() == TicketStatus.OPEN && ticket.assignee().type() == ActorType.HUMAN);

            WorkflowRuntimeSnapshot repeatedWaitingSnapshot = workflowUseCase.runUntilStable(workflowRunId);
            assertThat(repeatedWaitingSnapshot.taskRuns()).hasSameSizeAs(waitingSnapshot.taskRuns());
            assertThat(repeatedWaitingSnapshot.tickets()).hasSameSizeAs(waitingSnapshot.tickets());
            assertThat(repeatedWaitingSnapshot.tasks()).hasSameSizeAs(waitingSnapshot.tasks());

            String ticketId = waitingSnapshot.tickets().stream()
                    .filter(ticket -> ticket.assignee().type() == ActorType.HUMAN)
                    .findFirst()
                    .orElseThrow()
                    .ticketId();

            workflowUseCase.answerTicket(new AnswerTicketCommand(
                    ticketId,
                    "healthz 只需要最小探活，不需要附加探测语义。",
                    new ActorRef(ActorType.HUMAN, "clarification-user")
            ));

            WorkflowRuntimeSnapshot completedSnapshot = workflowUseCase.runUntilStable(workflowRunId);
            assertThat(completedSnapshot.workflowRun().status()).isEqualTo(WorkflowRunStatus.COMPLETED);
            assertThat(completedSnapshot.tasks()).allMatch(task -> task.status() == WorkTaskStatus.DONE);
            assertThat(completedSnapshot.taskRuns()).anyMatch(run -> run.status() == TaskRunStatus.CANCELED);
            assertThat(completedSnapshot.taskRuns()).anyMatch(run -> run.status() == TaskRunStatus.SUCCEEDED);
            assertThat(completedSnapshot.snapshots()).hasSizeGreaterThanOrEqualTo(2);
        } finally {
            cleanupWorkflow(workflowRunId);
        }
    }

    @Test
    void shouldKeepCompletedWorkflowIdempotent() {
        String workflowRunId = workflowUseCase.start(new StartCodingWorkflowCommand(
                "Idempotent Runtime",
                "Idempotent Requirement",
                "验证完成态 workflow 的重复执行不会重复造数据。",
                new ActorRef(ActorType.HUMAN, "idempotent-user"),
                false,
                WorkflowScenario.defaultScenario()
        ));

        try {
            WorkflowRuntimeSnapshot firstSnapshot = workflowUseCase.runUntilStable(workflowRunId);
            WorkflowRuntimeSnapshot secondSnapshot = workflowUseCase.runUntilStable(workflowRunId);

            assertThat(firstSnapshot.workflowRun().status()).isEqualTo(WorkflowRunStatus.COMPLETED);
            assertThat(secondSnapshot.workflowRun().status()).isEqualTo(WorkflowRunStatus.COMPLETED);
            assertThat(secondSnapshot.requirementVersions()).hasSameSizeAs(firstSnapshot.requirementVersions());
            assertThat(secondSnapshot.tickets()).hasSameSizeAs(firstSnapshot.tickets());
            assertThat(secondSnapshot.tasks()).hasSameSizeAs(firstSnapshot.tasks());
            assertThat(secondSnapshot.taskRuns()).hasSameSizeAs(firstSnapshot.taskRuns());
            assertThat(secondSnapshot.nodeRuns()).hasSameSizeAs(firstSnapshot.nodeRuns());
        } finally {
            cleanupWorkflow(workflowRunId);
        }
    }

    private void cleanupWorkflow(String workflowRunId) {
        jdbcTemplate.execute("set foreign_key_checks = 0");
        try {
            jdbcTemplate.update("""
                    delete from workflow_node_run_events
                    where node_run_id in (
                      select node_run_id from workflow_node_runs where workflow_run_id = ?
                    )
                    """, workflowRunId);
            jdbcTemplate.update("""
                    delete from task_run_events
                    where run_id in (
                      select run_id from task_runs
                      where task_id in (
                        select wt.task_id
                        from work_tasks wt
                        join work_modules wm on wm.module_id = wt.module_id
                        where wm.workflow_run_id = ?
                      )
                    )
                    """, workflowRunId);
            jdbcTemplate.update("""
                    delete from git_workspaces
                    where task_id in (
                      select wt.task_id
                      from work_tasks wt
                      join work_modules wm on wm.module_id = wt.module_id
                      where wm.workflow_run_id = ?
                    )
                    """, workflowRunId);
            jdbcTemplate.update("""
                    delete from task_runs
                    where task_id in (
                      select wt.task_id
                      from work_tasks wt
                      join work_modules wm on wm.module_id = wt.module_id
                      where wm.workflow_run_id = ?
                    )
                    """, workflowRunId);
            jdbcTemplate.update("delete from agent_pool_instances where current_workflow_run_id = ?", workflowRunId);
            jdbcTemplate.update("""
                    delete from task_context_snapshots
                    where task_id in (
                      select wt.task_id
                      from work_tasks wt
                      join work_modules wm on wm.module_id = wt.module_id
                      where wm.workflow_run_id = ?
                    )
                    """, workflowRunId);
            jdbcTemplate.update("""
                    delete from work_task_capability_requirements
                    where task_id in (
                      select wt.task_id
                      from work_tasks wt
                      join work_modules wm on wm.module_id = wt.module_id
                      where wm.workflow_run_id = ?
                    )
                    """, workflowRunId);
            jdbcTemplate.update("""
                    delete from work_task_dependencies
                    where task_id in (
                      select wt.task_id
                      from work_tasks wt
                      join work_modules wm on wm.module_id = wt.module_id
                      where wm.workflow_run_id = ?
                    )
                    or depends_on_task_id in (
                      select wt.task_id
                      from work_tasks wt
                      join work_modules wm on wm.module_id = wt.module_id
                      where wm.workflow_run_id = ?
                    )
                    """, workflowRunId, workflowRunId);
            jdbcTemplate.update("""
                    delete from work_tasks
                    where module_id in (
                      select module_id from work_modules where workflow_run_id = ?
                    )
                    """, workflowRunId);
            jdbcTemplate.update("delete from work_modules where workflow_run_id = ?", workflowRunId);
            jdbcTemplate.update("""
                    delete from ticket_events
                    where ticket_id in (
                      select ticket_id from tickets where workflow_run_id = ?
                    )
                    """, workflowRunId);
            jdbcTemplate.update("delete from tickets where workflow_run_id = ?", workflowRunId);
            jdbcTemplate.update("""
                    delete from requirement_doc_versions
                    where doc_id in (
                      select doc_id from requirement_docs where workflow_run_id = ?
                    )
                    """, workflowRunId);
            jdbcTemplate.update("delete from requirement_docs where workflow_run_id = ?", workflowRunId);
            jdbcTemplate.update("delete from workflow_node_runs where workflow_run_id = ?", workflowRunId);
            jdbcTemplate.update("delete from workflow_run_node_bindings where workflow_run_id = ?", workflowRunId);
            jdbcTemplate.update("delete from workflow_run_events where workflow_run_id = ?", workflowRunId);
            jdbcTemplate.update("delete from workflow_runs where workflow_run_id = ?", workflowRunId);
        } finally {
            jdbcTemplate.execute("set foreign_key_checks = 1");
        }
    }
}
