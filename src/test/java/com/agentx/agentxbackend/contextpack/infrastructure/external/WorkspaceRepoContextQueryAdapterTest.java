package com.agentx.agentxbackend.contextpack.infrastructure.external;

import com.agentx.agentxbackend.contextpack.application.port.out.RepoContextQueryPort;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkspaceRepoContextQueryAdapterTest {

    @TempDir
    Path tempDir;

    @Test
    void queryShouldUseSemanticIndexInsideSessionRepoOnly() throws Exception {
        Path outerRepo = tempDir.resolve("agentx-root");
        Path outerGit = outerRepo.resolve(".git");
        Path sessionRepo = outerRepo.resolve("sessions").resolve("ses-1").resolve("repo");
        Path sessionGit = sessionRepo.resolve(".git");
        Files.createDirectories(outerGit);
        Files.createDirectories(sessionGit);

        Path irrelevantOuterFile = outerRepo.resolve("src").resolve("main").resolve("java").resolve("control").resolve("OuterControlPlane.java");
        Files.createDirectories(irrelevantOuterFile.getParent());
        Files.writeString(
            irrelevantOuterFile,
            "package control; class OuterControlPlane { String value = \"control-plane only\"; }",
            StandardCharsets.UTF_8
        );

        Path sessionServiceFile = sessionRepo.resolve("src").resolve("main").resolve("java").resolve("demo").resolve("StudentDeleteService.java");
        Files.createDirectories(sessionServiceFile.getParent());
        Files.writeString(
            sessionServiceFile,
            """
            package demo;

            class StudentDeleteService {
                String removeStudentFromCourse(String studentId, String courseId) {
                    return "delete student from course relation";
                }
            }
            """,
            StandardCharsets.UTF_8
        );

        LangChain4jSemanticRepoIndexSupport semanticSupport = new LangChain4jSemanticRepoIndexSupport(
            new LangChain4jSemanticRepoIndexSupport.SemanticConfig(
                true,
                "https://api.openai.com/v1",
                "test-key",
                "text-embedding-3-small",
                30_000L,
                600L,
                256,
                8_000,
                64_000,
                600,
                80,
                16,
                0.0d
            ),
            ignored -> new KeywordEmbeddingModel()
        );
        WorkspaceRepoContextQueryAdapter adapter = new WorkspaceRepoContextQueryAdapter(
            "git",
            outerRepo.toString(),
            600L,
            semanticSupport
        );

        RepoContextQueryPort.RepoContext result = adapter.query(
            new RepoContextQueryPort.RepoContextQuery(
                "delete student course relation",
                List.of("sessions/ses-1/repo/"),
                8,
                3,
                400,
                1200
            )
        );

        assertEquals("langchain4j_semantic_v1", result.indexKind());
        assertTrue(result.repoRoot().replace('\\', '/').endsWith("sessions/ses-1/repo"));
        assertFalse(result.relevantFiles().isEmpty());
        assertEquals("src/main/java/demo/StudentDeleteService.java", result.relevantFiles().get(0).path());
        assertTrue(
            result.excerpts().stream().anyMatch(excerpt -> excerpt.path().equals("src/main/java/demo/StudentDeleteService.java"))
        );
        assertTrue(
            result.relevantFiles().stream().noneMatch(file -> file.path().contains("OuterControlPlane"))
        );
    }

    private static final class KeywordEmbeddingModel implements EmbeddingModel {

        @Override
        public Response<List<Embedding>> embedAll(List<TextSegment> textSegments) {
            List<Embedding> embeddings = textSegments.stream()
                .map(segment -> Embedding.from(vectorize(segment == null ? "" : segment.text())))
                .toList();
            return Response.from(embeddings);
        }

        private static float[] vectorize(String rawText) {
            String text = rawText == null ? "" : rawText.toLowerCase();
            float student = text.contains("student") ? 1.0f : 0.05f;
            float course = text.contains("course") ? 1.0f : 0.05f;
            float delete = (text.contains("delete") || text.contains("remove")) ? 1.0f : 0.05f;
            return new float[]{student, course, delete};
        }
    }
}
