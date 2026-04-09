package com.agentx.platform;

import com.agentx.platform.runtime.context.CompiledContextPack;
import com.agentx.platform.runtime.context.ContextCompilationProperties;
import com.agentx.platform.runtime.context.ContextCompilationRequest;
import com.agentx.platform.runtime.context.ContextPackType;
import com.agentx.platform.runtime.context.ContextScope;
import com.agentx.platform.runtime.context.DefaultContextCompilationCenter;
import com.agentx.platform.runtime.context.FactBundle;
import com.agentx.platform.runtime.context.RetrievalSnippet;
import com.agentx.platform.runtime.evaluation.WorkflowEvalProperties;
import com.agentx.platform.runtime.evaluation.WorkflowEvalTraceCollector;
import com.agentx.platform.runtime.retrieval.FactRetriever;
import com.agentx.platform.runtime.retrieval.IndexedChunk;
import com.agentx.platform.runtime.retrieval.LexicalChunkRetriever;
import com.agentx.platform.runtime.retrieval.RepoIndexManifest;
import com.agentx.platform.runtime.retrieval.RepoIndexService;
import com.agentx.platform.runtime.retrieval.RetrievalProperties;
import com.agentx.platform.runtime.retrieval.RetrievalQuery;
import com.agentx.platform.runtime.retrieval.RetrievalQueryPlanner;
import com.agentx.platform.runtime.retrieval.WorkflowOverlayIndexService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DefaultContextCompilationCenterTests {

    @Test
    void shouldSkipRepoRetrievalForCodingPack() throws Exception {
        FactRetriever factRetriever = mock(FactRetriever.class);
        RepoIndexService repoIndexService = mock(RepoIndexService.class);
        WorkflowOverlayIndexService overlayIndexService = mock(WorkflowOverlayIndexService.class);
        RetrievalQueryPlanner queryPlanner = mock(RetrievalQueryPlanner.class);
        LexicalChunkRetriever lexicalChunkRetriever = mock(LexicalChunkRetriever.class);

        ContextCompilationProperties contextProperties = new ContextCompilationProperties();
        contextProperties.setArtifactRoot(Files.createTempDirectory("context-pack-coding"));
        RetrievalProperties retrievalProperties = new RetrievalProperties();
        WorkflowEvalProperties evalProperties = new WorkflowEvalProperties();
        evalProperties.setTraceCollectionEnabled(true);

        when(factRetriever.retrieve(any())).thenReturn(new FactBundle(Map.of(
                "task", Map.of("taskId", "task-1"),
                "runtimePlatform", "LINUX_CONTAINER"
        )));
        when(repoIndexService.buildBaseIndex()).thenReturn(baseManifest());
        when(queryPlanner.plan(any(), any())).thenReturn(new RetrievalQuery(List.of("student"), List.of("src/main/java")));
        when(lexicalChunkRetriever.retrieve(any(), any(), anyInt())).thenReturn(List.of(sampleSnippet()));

        DefaultContextCompilationCenter compilationCenter = new DefaultContextCompilationCenter(
                contextProperties,
                retrievalProperties,
                factRetriever,
                repoIndexService,
                overlayIndexService,
                queryPlanner,
                lexicalChunkRetriever,
                new ObjectMapper().findAndRegisterModules(),
                new WorkflowEvalTraceCollector(evalProperties)
        );

        CompiledContextPack codingPack = compilationCenter.compile(new ContextCompilationRequest(
                ContextPackType.CODING,
                ContextScope.task("workflow-1", "task-1", "run-1", "coding", null),
                "TEST"
        ));

        assertThat(codingPack.retrievalBundle().snippets()).isEmpty();
        verify(queryPlanner, never()).plan(any(), any());
        verify(lexicalChunkRetriever, never()).retrieve(any(), any(), anyInt());
    }

    @Test
    void shouldKeepLexicalRetrievalForArchitectPack() throws Exception {
        FactRetriever factRetriever = mock(FactRetriever.class);
        RepoIndexService repoIndexService = mock(RepoIndexService.class);
        WorkflowOverlayIndexService overlayIndexService = mock(WorkflowOverlayIndexService.class);
        RetrievalQueryPlanner queryPlanner = mock(RetrievalQueryPlanner.class);
        LexicalChunkRetriever lexicalChunkRetriever = mock(LexicalChunkRetriever.class);

        ContextCompilationProperties contextProperties = new ContextCompilationProperties();
        contextProperties.setArtifactRoot(Files.createTempDirectory("context-pack-architect"));
        RetrievalProperties retrievalProperties = new RetrievalProperties();
        WorkflowEvalProperties evalProperties = new WorkflowEvalProperties();
        evalProperties.setTraceCollectionEnabled(true);

        when(factRetriever.retrieve(any())).thenReturn(new FactBundle(Map.of(
                "workflowRun", Map.of("workflowRunId", "workflow-1"),
                "requirementDoc", Map.of("title", "student management")
        )));
        when(repoIndexService.buildBaseIndex()).thenReturn(baseManifest());
        when(queryPlanner.plan(any(), any())).thenReturn(new RetrievalQuery(List.of("student"), List.of("src/main/java")));
        when(lexicalChunkRetriever.retrieve(any(), any(), anyInt())).thenReturn(List.of(sampleSnippet()));

        DefaultContextCompilationCenter compilationCenter = new DefaultContextCompilationCenter(
                contextProperties,
                retrievalProperties,
                factRetriever,
                repoIndexService,
                overlayIndexService,
                queryPlanner,
                lexicalChunkRetriever,
                new ObjectMapper().findAndRegisterModules(),
                new WorkflowEvalTraceCollector(evalProperties)
        );

        CompiledContextPack architectPack = compilationCenter.compile(new ContextCompilationRequest(
                ContextPackType.ARCHITECT,
                ContextScope.workflow("workflow-1", "architect"),
                "TEST"
        ));

        assertThat(architectPack.retrievalBundle().snippets()).singleElement().satisfies(snippet ->
                assertThat(snippet.sourceRef()).isEqualTo("src/main/java/com/example/student/StudentService.java"));
        verify(queryPlanner).plan(any(), any());
        verify(lexicalChunkRetriever).retrieve(any(), any(), anyInt());
    }

    private RepoIndexManifest baseManifest() {
        return new RepoIndexManifest(
                "base-index",
                "fingerprint",
                "/workspace",
                List.of(new IndexedChunk(
                        "chunk-1",
                        "repo-code",
                        "src/main/java/com/example/student/StudentService.java",
                        "src/main/java/com/example/student/StudentService.java",
                        1,
                        12,
                        "class StudentService {}",
                        List.of("StudentService"),
                        false
                ))
        );
    }

    private RetrievalSnippet sampleSnippet() {
        return new RetrievalSnippet(
                "snippet-1",
                "repo-code",
                "src/main/java/com/example/student/StudentService.java",
                "StudentService",
                "class StudentService {}",
                0.91,
                List.of("StudentService"),
                Map.of("compiledAt", LocalDateTime.now().toString())
        );
    }
}
