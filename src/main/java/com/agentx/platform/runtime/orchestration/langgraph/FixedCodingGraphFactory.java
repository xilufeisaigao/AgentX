package com.agentx.platform.runtime.orchestration.langgraph;

import com.agentx.platform.runtime.application.workflow.FixedCodingNodeExecutor;
import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.StateGraph;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

@Component
public class FixedCodingGraphFactory {

    private final FixedCodingNodeExecutor nodeExecutor;
    private volatile CompiledGraph<PlatformWorkflowState> compiledGraph;

    public FixedCodingGraphFactory(FixedCodingNodeExecutor nodeExecutor) {
        this.nodeExecutor = nodeExecutor;
    }

    public CompiledGraph<PlatformWorkflowState> compiledGraph() {
        if (compiledGraph == null) {
            synchronized (this) {
                if (compiledGraph == null) {
                    compiledGraph = buildGraph();
                }
            }
        }
        return compiledGraph;
    }

    private CompiledGraph<PlatformWorkflowState> buildGraph() {
        try {
            StateGraph<PlatformWorkflowState> graph = new StateGraph<>(PlatformWorkflowState::new);
            graph.addNode("requirement", state -> completed(nodeExecutor.requirementNode(state)));
            graph.addNode("architect", state -> completed(nodeExecutor.architectNode(state)));
            graph.addNode("ticket-gate", state -> completed(nodeExecutor.ticketGateNode(state)));
            graph.addNode("task-graph", state -> completed(nodeExecutor.taskGraphNode(state)));
            graph.addNode("worker-manager", state -> completed(nodeExecutor.workerManagerNode(state)));
            graph.addNode("coding", state -> completed(nodeExecutor.codingNode(state)));
            graph.addNode("merge-gate", state -> completed(nodeExecutor.mergeGateNode(state)));
            graph.addNode("verify", state -> completed(nodeExecutor.verifyNode(state)));

            graph.addEdge(StateGraph.START, "requirement");
            graph.addConditionalEdges("requirement",
                    state -> completed(nodeExecutor.routeAfterRequirement(state)),
                    Map.of("architect", "architect", "ticket-gate", "ticket-gate"));
            graph.addConditionalEdges("architect",
                    state -> completed(nodeExecutor.routeAfterArchitect(state)),
                    Map.of("ticket-gate", "ticket-gate", "task-graph", "task-graph"));
            graph.addConditionalEdges("ticket-gate",
                    state -> completed(nodeExecutor.routeAfterTicketGate(state)),
                    Map.of("requirement", "requirement", "architect", "architect", "end", StateGraph.END));
            graph.addEdge("task-graph", "worker-manager");
            graph.addEdge("worker-manager", "coding");
            graph.addConditionalEdges("coding",
                    state -> completed(nodeExecutor.routeAfterCoding(state)),
                    Map.of("architect", "architect", "merge-gate", "merge-gate", "end", StateGraph.END));
            graph.addEdge("merge-gate", "verify");
            graph.addConditionalEdges("verify",
                    state -> completed(nodeExecutor.routeAfterVerify(state)),
                    Map.of("architect", "architect", "end", StateGraph.END));
            return graph.compile();
        } catch (Exception exception) {
            throw new IllegalStateException("failed to compile fixed coding graph", exception);
        }
    }

    private <T> CompletableFuture<T> completed(T value) {
        return CompletableFuture.completedFuture(value);
    }
}
