package com.agentx.platform.runtime.tooling;

import com.agentx.platform.domain.execution.model.AgentPoolInstance;
import com.agentx.platform.domain.execution.model.GitWorkspace;
import com.agentx.platform.domain.execution.model.TaskRun;
import com.agentx.platform.domain.planning.model.WorkTask;
import com.agentx.platform.domain.shared.model.JsonPayload;
import com.agentx.platform.runtime.agentruntime.AgentRuntime;
import com.agentx.platform.runtime.agentruntime.ContainerLaunchSpec;
import com.agentx.platform.runtime.agentruntime.EphemeralExecutionResult;
import com.agentx.platform.runtime.agentruntime.ToolExecutionSpec;
import com.agentx.platform.runtime.application.workflow.TaskExecutionContract;
import com.agentx.platform.runtime.support.CommandResult;
import com.agentx.platform.runtime.support.CommandRunner;
import com.agentx.platform.runtime.support.CommandSpec;
import com.agentx.platform.runtime.workspace.git.GitWorktreeContainerBindings;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@Component
public class ToolExecutor {

    private final AgentRuntime agentRuntime;
    private final CommandRunner commandRunner;
    private final ToolRegistry toolRegistry;
    private final ToolCallNormalizer toolCallNormalizer;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient = HttpClient.newHttpClient();

    public ToolExecutor(
            AgentRuntime agentRuntime,
            CommandRunner commandRunner,
            ToolRegistry toolRegistry,
            ToolCallNormalizer toolCallNormalizer,
            ObjectMapper objectMapper
    ) {
        this.agentRuntime = agentRuntime;
        this.commandRunner = commandRunner;
        this.toolRegistry = toolRegistry;
        this.toolCallNormalizer = toolCallNormalizer;
        this.objectMapper = objectMapper;
    }

    public ToolExecutionOutcome executeForRun(
            WorkTask task,
            TaskRun run,
            AgentPoolInstance agentInstance,
            GitWorkspace workspace,
            TaskExecutionContract contract,
            ToolCall toolCall
    ) {
        return executeOne(new ExecutionContext(
                task,
                run,
                agentInstance,
                Path.of(workspace.worktreePath()).toAbsolutePath().normalize(),
                contract,
                false
        ), toolCall, false);
    }

    public ToolExecutionOutcome executeDeliveryPlan(
            WorkTask task,
            TaskRun run,
            AgentPoolInstance agentInstance,
            GitWorkspace workspace,
            TaskExecutionContract contract
    ) {
        ExecutionContext context = new ExecutionContext(
                task,
                run,
                agentInstance,
                Path.of(workspace.worktreePath()).toAbsolutePath().normalize(),
                contract,
                false
        );
        ensureMarkerFile(context);
        return executeBatch(
                context,
                contract.postDeliveryToolCalls(),
                true,
                "deliver task candidate"
        );
    }

    public ToolExecutionOutcome executeVerifyPlan(TaskRun run, TaskExecutionContract contract, Path checkoutPath) {
        return executeBatch(
                new ExecutionContext(null, run, null, checkoutPath.toAbsolutePath().normalize(), contract, true),
                contract.verifyToolCalls(),
                false,
                "execute deterministic verify plan"
        );
    }

    public ToolCall normalizeForRun(String runId, ToolCall toolCall) {
        return toolCallNormalizer.normalize(runId, toolCall);
    }

    private ToolExecutionOutcome executeBatch(
            ExecutionContext context,
            List<ToolCall> toolCalls,
            boolean terminal,
            String body
    ) {
        List<Map<String, Object>> results = new ArrayList<>();
        for (ToolCall toolCall : toolCalls) {
            ToolExecutionOutcome outcome = executeOne(context, toolCall, false);
            results.add(payloadMap(outcome.payload()));
            if (!outcome.succeeded()) {
                return new ToolExecutionOutcome(
                        terminal,
                        false,
                        outcome.body(),
                        jsonPayload(Map.of("results", results))
                );
            }
        }
        return new ToolExecutionOutcome(
                terminal,
                true,
                body,
                jsonPayload(Map.of("results", results))
        );
    }

    private ToolExecutionOutcome executeOne(ExecutionContext context, ToolCall rawToolCall, boolean terminal) {
        ToolCall toolCall = toolCallNormalizer.normalize(context.run().runId(), rawToolCall);
        toolRegistry.validate(context.contract().toolCatalog(), toolCall);
        LocalDateTime startedAt = LocalDateTime.now();
        RawToolExecution rawOutcome = switch (toolCall.toolId()) {
            case "tool-filesystem" -> filesystem(context, toolCall);
            case "tool-shell" -> shell(context, toolCall);
            case "tool-git" -> git(context, toolCall);
            case "tool-http-client" -> httpRequest(context, toolCall);
            default -> throw new IllegalArgumentException("unsupported toolId " + toolCall.toolId());
        };
        LocalDateTime finishedAt = LocalDateTime.now();
        return new ToolExecutionOutcome(
                terminal,
                rawOutcome.succeeded(),
                rawOutcome.body(),
                jsonPayload(standardizedEvidence(toolCall, terminal, rawOutcome.succeeded(), startedAt, finishedAt, rawOutcome.evidence()))
        );
    }

    private RawToolExecution filesystem(ExecutionContext context, ToolCall toolCall) {
        return switch (toolCall.operation()) {
            case "read_file" -> readFile(context.workspaceRoot(), toolCall);
            case "list_directory" -> listDirectory(context.workspaceRoot(), toolCall);
            case "search_text" -> searchText(context.workspaceRoot(), toolCall);
            case "write_file" -> writeFile(context, toolCall);
            case "delete_file" -> deleteFile(context, toolCall);
            default -> throw new IllegalArgumentException("unsupported filesystem operation " + toolCall.operation());
        };
    }

    private RawToolExecution git(ExecutionContext context, ToolCall toolCall) {
        List<String> command = switch (toolCall.operation()) {
            case "git_status" -> List.of("git", "status", "--short");
            case "git_diff_stat" -> List.of("git", "diff", "--stat");
            case "git_head" -> List.of("git", "rev-parse", "HEAD");
            default -> throw new IllegalArgumentException("unsupported git operation " + toolCall.operation());
        };
        CommandResult result = commandRunner.run(new CommandSpec(
                command,
                context.workspaceRoot(),
                Duration.ofSeconds(context.contract().timeoutSeconds()),
                Map.of()
        ));
        return rawCommandResult(toolCall.summary(), result, Map.of());
    }

    private RawToolExecution shell(ExecutionContext context, ToolCall toolCall) {
        String commandId = stringArgument(toolCall.arguments(), "commandId");
        List<String> command = context.contract().allowedCommandCatalog().get(commandId);
        if (command == null) {
            throw new IllegalArgumentException("unsupported commandId " + commandId);
        }
        Map<String, String> environment = new LinkedHashMap<>(context.contract().toolEnvironment());
        environment.put("RUN_ID", context.run().runId());
        environment.put("TASK_ID", context.run().taskId());
        EphemeralExecutionResult result = context.detached()
                ? executeDetachedCommand(context, command, environment)
                : agentRuntime.executeInRunningContainer(
                context.agentInstance(),
                new ToolExecutionSpec(
                        context.contract().workingDirectory(),
                        command,
                        environment,
                        Duration.ofSeconds(context.contract().timeoutSeconds())
                )
        );
        return new RawToolExecution(
                result.succeeded(),
                toolCall.summary(),
                Map.of(
                        "commandId", commandId,
                        "exitCode", result.exitCode(),
                        "stdout", truncate(result.stdout()),
                        "stderr", truncate(result.stderr()),
                        "timedOut", result.timedOut(),
                        "body", truncate(firstNonBlank(result.stdout(), result.stderr(), toolCall.summary()))
                )
        );
    }

    private RawToolExecution httpRequest(ExecutionContext context, ToolCall toolCall) {
        String endpointId = stringArgument(toolCall.arguments(), "endpointId");
        HttpEndpointSpec endpointSpec = context.contract().httpEndpointCatalog().get(endpointId);
        if (endpointSpec == null) {
            throw new IllegalArgumentException("endpoint is not allowlisted: " + endpointId);
        }
        String method = stringArgumentOrDefault(toolCall.arguments(), "method", "GET").toUpperCase(Locale.ROOT);
        if (!endpointSpec.allowedMethods().contains(method)) {
            throw new IllegalArgumentException("http method is not allowed for endpoint " + endpointId + ": " + method);
        }
        String path = stringArgumentOrDefault(toolCall.arguments(), "path", "/");
        String requestBody = stringArgumentOrDefault(toolCall.arguments(), "body", "");
        String targetUrl = endpointSpec.baseUrl() + normalizeHttpPath(path);
        try {
            HttpRequest.Builder requestBuilder = HttpRequest.newBuilder(URI.create(targetUrl))
                    .timeout(Duration.ofSeconds(context.contract().timeoutSeconds()));
            if ("GET".equals(method)) {
                requestBuilder.GET();
            } else {
                requestBuilder.method(method, HttpRequest.BodyPublishers.ofString(requestBody));
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> headers = (Map<String, Object>) toolCall.arguments().getOrDefault("headers", Map.of());
            for (Map.Entry<String, Object> header : headers.entrySet()) {
                requestBuilder.header(header.getKey(), String.valueOf(header.getValue()));
            }
            HttpResponse<String> response = httpClient.send(requestBuilder.build(), HttpResponse.BodyHandlers.ofString());
            return new RawToolExecution(
                    response.statusCode() < 400,
                    toolCall.summary(),
                    Map.of(
                            "endpointId", endpointId,
                            "method", method,
                            "url", targetUrl,
                            "statusCode", response.statusCode(),
                            "body", truncate(response.body())
                    )
            );
        } catch (IOException exception) {
            return new RawToolExecution(
                    false,
                    exception.getMessage(),
                    Map.of(
                            "endpointId", endpointId,
                            "method", method,
                            "url", targetUrl,
                            "error", exception.getMessage(),
                            "body", exception.getMessage()
                    )
            );
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return new RawToolExecution(
                    false,
                    exception.getMessage(),
                    Map.of(
                            "endpointId", endpointId,
                            "method", method,
                            "url", targetUrl,
                            "error", exception.getMessage(),
                            "body", exception.getMessage()
                    )
            );
        }
    }

    private RawToolExecution readFile(Path workspaceRoot, ToolCall toolCall) {
        Path target = readPath(workspaceRoot, stringArgument(toolCall.arguments(), "path"));
        try {
            if (Files.notExists(target)) {
                return new RawToolExecution(
                        false,
                        "file does not exist: " + target,
                        Map.of(
                                "path", relativePath(workspaceRoot, target),
                                "body", "file does not exist"
                        )
                );
            }
            if (Files.isDirectory(target)) {
                return directoryListing(workspaceRoot, target, toolCall.summary());
            }
            String content = Files.readString(target, StandardCharsets.UTF_8);
            return new RawToolExecution(
                    true,
                    toolCall.summary(),
                    Map.of(
                            "path", relativePath(workspaceRoot, target),
                            "content", truncate(content),
                            "body", truncate(content)
                    )
            );
        } catch (IOException exception) {
            return new RawToolExecution(
                    false,
                    exception.getMessage(),
                    Map.of(
                            "path", relativePath(workspaceRoot, target),
                            "body", exception.getMessage()
                    )
            );
        }
    }

    private RawToolExecution listDirectory(Path workspaceRoot, ToolCall toolCall) {
        Path target = readPath(workspaceRoot, stringArgument(toolCall.arguments(), "path"));
        try {
            if (Files.notExists(target)) {
                return new RawToolExecution(
                        false,
                        "directory does not exist: " + target,
                        Map.of(
                                "path", relativePath(workspaceRoot, target),
                                "body", "directory does not exist"
                        )
                );
            }
            if (!Files.isDirectory(target)) {
                return new RawToolExecution(
                        false,
                        "path is not a directory: " + target,
                        Map.of(
                                "path", relativePath(workspaceRoot, target),
                                "body", "path is not a directory"
                        )
                );
            }
            return directoryListing(workspaceRoot, target, toolCall.summary());
        } catch (IOException exception) {
            return new RawToolExecution(
                    false,
                    exception.getMessage(),
                    Map.of(
                            "path", relativePath(workspaceRoot, target),
                            "body", exception.getMessage()
                    )
            );
        }
    }

    private RawToolExecution directoryListing(Path workspaceRoot, Path target, String summary) throws IOException {
        List<String> entries;
        try (var stream = Files.list(target)) {
            entries = stream.limit(100)
                    .map(path -> relativePath(workspaceRoot, path))
                    .sorted()
                    .toList();
        }
        String body = entries.isEmpty() ? "(empty directory)" : truncate(String.join(System.lineSeparator(), entries));
        return new RawToolExecution(
                true,
                summary,
                Map.of(
                        "path", relativePath(workspaceRoot, target),
                        "directory", true,
                        "entries", entries,
                        "body", body
                )
        );
    }

    private RawToolExecution searchText(Path workspaceRoot, ToolCall toolCall) {
        String query = stringArgument(toolCall.arguments(), "query");
        try {
            String normalized = query.toLowerCase(Locale.ROOT);
            StringBuilder matches = new StringBuilder();
            Files.walk(workspaceRoot)
                    .filter(Files::isRegularFile)
                    .limit(300)
                    .forEach(path -> appendMatches(workspaceRoot, path, normalized, matches));
            String body = truncate(matches.toString());
            return new RawToolExecution(
                    true,
                    toolCall.summary(),
                    Map.of(
                            "query", query,
                            "matches", body,
                            "body", body
                    )
            );
        } catch (IOException exception) {
            return new RawToolExecution(
                    false,
                    exception.getMessage(),
                    Map.of(
                            "query", query,
                            "body", exception.getMessage()
                    )
            );
        }
    }

    private RawToolExecution writeFile(ExecutionContext context, ToolCall toolCall) {
        WorkTask task = context.task();
        if (task == null) {
            throw new IllegalArgumentException("write_file requires task context");
        }
        Path target = writePath(task, context.workspaceRoot(), stringArgument(toolCall.arguments(), "path"));
        String content = stringArgumentOrDefault(toolCall.arguments(), "content", "");
        try {
            Path parent = target.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(target, content, StandardCharsets.UTF_8);
            return new RawToolExecution(
                    true,
                    toolCall.summary(),
                    Map.of(
                            "path", relativePath(context.workspaceRoot(), target),
                            "bytes", content.getBytes(StandardCharsets.UTF_8).length,
                            "body", "wrote " + relativePath(context.workspaceRoot(), target)
                    )
            );
        } catch (IOException exception) {
            return new RawToolExecution(
                    false,
                    exception.getMessage(),
                    Map.of(
                            "path", relativePath(context.workspaceRoot(), target),
                            "body", exception.getMessage()
                    )
            );
        }
    }

    private void ensureMarkerFile(ExecutionContext context) {
        WorkTask task = context.task();
        if (task == null) {
            throw new IllegalArgumentException("delivery marker requires task context");
        }
        Path markerPath = writePath(task, context.workspaceRoot(), context.contract().markerFile());
        String content = """
                taskId=%s
                runId=%s
                generatedAt=%s
                """.formatted(task.taskId(), context.run().runId(), LocalDateTime.now());
        try {
            Path parent = markerPath.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(markerPath, content, StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new IllegalStateException("failed to materialize delivery marker " + markerPath, exception);
        }
    }

    private RawToolExecution deleteFile(ExecutionContext context, ToolCall toolCall) {
        WorkTask task = context.task();
        if (task == null) {
            throw new IllegalArgumentException("delete_file requires task context");
        }
        Path target = writePath(task, context.workspaceRoot(), stringArgument(toolCall.arguments(), "path"));
        try {
            Files.deleteIfExists(target);
            return new RawToolExecution(
                    true,
                    toolCall.summary(),
                    Map.of(
                            "path", relativePath(context.workspaceRoot(), target),
                            "body", "deleted " + relativePath(context.workspaceRoot(), target)
                    )
            );
        } catch (IOException exception) {
            return new RawToolExecution(
                    false,
                    exception.getMessage(),
                    Map.of(
                            "path", relativePath(context.workspaceRoot(), target),
                            "body", exception.getMessage()
                    )
            );
        }
    }

    private EphemeralExecutionResult executeDetachedCommand(
            ExecutionContext context,
            List<String> command,
            Map<String, String> environment
    ) {
        GitWorktreeContainerBindings.GitContainerBinding gitBinding = GitWorktreeContainerBindings.forWorktree(
                context.workspaceRoot(),
                context.contract().workingDirectory(),
                true,
                true
        );
        Map<String, String> launchEnvironment = new LinkedHashMap<>(environment);
        launchEnvironment.putAll(gitBinding.environment());
        return agentRuntime.executeOnce(new ContainerLaunchSpec(
                "tool-" + shortToken(context.run().runId(), UUID.randomUUID().toString()),
                context.contract().image(),
                context.contract().workingDirectory(),
                command,
                gitBinding.mounts(),
                launchEnvironment,
                Duration.ofSeconds(context.contract().timeoutSeconds())
        ));
    }

    private RawToolExecution rawCommandResult(String body, CommandResult result, Map<String, Object> extras) {
        Map<String, Object> evidence = new LinkedHashMap<>(extras);
        evidence.put("exitCode", result.exitCode());
        evidence.put("stdout", truncate(result.stdout()));
        evidence.put("stderr", truncate(result.stderr()));
        evidence.put("timedOut", result.timedOut());
        if (!evidence.containsKey("body")) {
            evidence.put("body", truncate(firstNonBlank(result.stdout(), result.stderr(), body)));
        }
        return new RawToolExecution(result.exitCode() == 0 && !result.timedOut(), body, evidence);
    }

    private void appendMatches(Path workspaceRoot, Path path, String normalized, StringBuilder matches) {
        try {
            List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
            for (int index = 0; index < lines.size(); index++) {
                if (lines.get(index).toLowerCase(Locale.ROOT).contains(normalized)) {
                    matches.append(relativePath(workspaceRoot, path))
                            .append(":")
                            .append(index + 1)
                            .append(" ")
                            .append(lines.get(index))
                            .append(System.lineSeparator());
                    if (matches.length() > 4_000) {
                        return;
                    }
                }
            }
        } catch (IOException ignored) {
            // Best-effort retrieval in a bounded coding turn.
        }
    }

    private Path readPath(Path workspaceRoot, String requestedPath) {
        Path target = workspaceRoot.resolve(requestedPath).normalize();
        if (!target.startsWith(workspaceRoot)) {
            throw new IllegalArgumentException("requested path escapes workspace: " + requestedPath);
        }
        return target;
    }

    private Path writePath(WorkTask task, Path workspaceRoot, String requestedPath) {
        Path target = readPath(workspaceRoot, requestedPath);
        String relativePath = workspaceRoot.relativize(target).toString().replace('\\', '/');
        boolean allowed = task.writeScopes().stream()
                .map(writeScope -> writeScope.path().replace('\\', '/'))
                .anyMatch(scope -> relativePath.equals(scope) || relativePath.startsWith(scope + "/"));
        if (!allowed) {
            throw new IllegalArgumentException("write path is outside task write scope: " + requestedPath);
        }
        return target;
    }

    private String relativePath(Path workspaceRoot, Path target) {
        if (workspaceRoot.equals(target)) {
            return ".";
        }
        return workspaceRoot.relativize(target).toString().replace('\\', '/');
    }

    private String stringArgument(Map<String, Object> arguments, String key) {
        Object value = arguments.get(key);
        if (value == null || String.valueOf(value).isBlank()) {
            throw new IllegalArgumentException("missing required argument: " + key);
        }
        return String.valueOf(value);
    }

    private String stringArgumentOrDefault(Map<String, Object> arguments, String key, String defaultValue) {
        Object value = arguments.get(key);
        return value == null ? defaultValue : String.valueOf(value);
    }

    private String normalizeHttpPath(String rawPath) {
        if (rawPath == null || rawPath.isBlank()) {
            return "/";
        }
        return rawPath.startsWith("/") ? rawPath : "/" + rawPath;
    }

    private Map<String, Object> standardizedEvidence(
            ToolCall toolCall,
            boolean terminal,
            boolean succeeded,
            LocalDateTime startedAt,
            LocalDateTime finishedAt,
            Map<String, Object> details
    ) {
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("callId", toolCall.callId());
        evidence.put("toolId", toolCall.toolId());
        evidence.put("operation", toolCall.operation());
        evidence.put("argumentsSummary", summarizeArguments(toolCall.arguments()));
        evidence.put("startedAt", startedAt.toString());
        evidence.put("finishedAt", finishedAt.toString());
        evidence.put("succeeded", succeeded);
        evidence.put("terminal", terminal);
        evidence.putAll(details);
        return evidence;
    }

    private Map<String, Object> summarizeArguments(Map<String, Object> arguments) {
        Map<String, Object> summary = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : arguments.entrySet()) {
            summary.put(entry.getKey(), summarizeArgumentValue(entry.getKey(), entry.getValue()));
        }
        return summary;
    }

    private Object summarizeArgumentValue(String key, Object value) {
        if (value == null) {
            return null;
        }
        if ("content".equals(key)) {
            String stringValue = String.valueOf(value);
            return Map.of(
                    "bytes", stringValue.getBytes(StandardCharsets.UTF_8).length,
                    "preview", truncate(stringValue, 200)
            );
        }
        if (value instanceof Map<?, ?> mapValue) {
            Map<String, Object> summary = new LinkedHashMap<>();
            mapValue.forEach((entryKey, entryValue) -> summary.put(String.valueOf(entryKey), summarizeArgumentValue(String.valueOf(entryKey), entryValue)));
            return summary;
        }
        if (value instanceof List<?> listValue) {
            return listValue.stream()
                    .map(item -> summarizeArgumentValue("item", item))
                    .toList();
        }
        String stringValue = String.valueOf(value);
        return stringValue.length() <= 200 ? stringValue : stringValue.substring(0, 200);
    }

    private String truncate(String value) {
        return truncate(value, 4_000);
    }

    private String truncate(String value, int maxLength) {
        if (value == null) {
            return "";
        }
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }

    private String firstNonBlank(String first, String second, String fallback) {
        if (first != null && !first.isBlank()) {
            return first.trim();
        }
        if (second != null && !second.isBlank()) {
            return second.trim();
        }
        return fallback;
    }

    private JsonPayload jsonPayload(Map<String, Object> data) {
        try {
            return new JsonPayload(objectMapper.writeValueAsString(data));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("failed to serialize tool payload", exception);
        }
    }

    private Map<String, Object> payloadMap(JsonPayload payload) {
        if (payload == null) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(payload.json(), Map.class);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("failed to parse tool payload", exception);
        }
    }

    private String shortToken(String... values) {
        return UUID.nameUUIDFromBytes(String.join("|", values).getBytes(StandardCharsets.UTF_8))
                .toString()
                .replace("-", "")
                .substring(0, 20);
    }

    public record ToolExecutionOutcome(
            boolean terminal,
            boolean succeeded,
            String body,
            JsonPayload payload
    ) {
    }

    private record RawToolExecution(
            boolean succeeded,
            String body,
            Map<String, Object> evidence
    ) {
    }

    private record ExecutionContext(
            WorkTask task,
            TaskRun run,
            AgentPoolInstance agentInstance,
            Path workspaceRoot,
            TaskExecutionContract contract,
            boolean detached
    ) {
    }
}
