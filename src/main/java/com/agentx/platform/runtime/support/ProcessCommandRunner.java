package com.agentx.platform.runtime.support;

import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Component
public class ProcessCommandRunner implements CommandRunner {

    @Override
    public CommandResult run(CommandSpec commandSpec) {
        Instant startedAt = Instant.now();
        ProcessBuilder processBuilder = new ProcessBuilder(commandSpec.command());
        if (commandSpec.workingDirectory() != null) {
            processBuilder.directory(commandSpec.workingDirectory().toFile());
        }
        processBuilder.environment().putAll(commandSpec.environment());

        try {
            Process process = processBuilder.start();
            StreamCollector stdoutCollector = new StreamCollector(process.getInputStream());
            StreamCollector stderrCollector = new StreamCollector(process.getErrorStream());
            stdoutCollector.start();
            stderrCollector.start();

            boolean finished = process.waitFor(commandSpec.timeout().toMillis(), TimeUnit.MILLISECONDS);
            if (!finished) {
                process.destroyForcibly();
            }
            stdoutCollector.join();
            stderrCollector.join();
            Duration elapsed = Duration.between(startedAt, Instant.now());
            return new CommandResult(
                    finished ? process.exitValue() : -1,
                    stdoutCollector.content(),
                    stderrCollector.content(),
                    !finished,
                    elapsed
            );
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("command execution interrupted: " + String.join(" ", commandSpec.command()), exception);
        } catch (IOException exception) {
            throw new IllegalStateException("failed to start command: " + String.join(" ", commandSpec.command()), exception);
        }
    }

    private static final class StreamCollector extends Thread {

        private final InputStream inputStream;
        private final List<String> lines = new ArrayList<>();

        private StreamCollector(InputStream inputStream) {
            this.inputStream = inputStream;
            setDaemon(true);
        }

        @Override
        public void run() {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    lines.add(line);
                }
            } catch (IOException exception) {
                lines.add("[stream read failed] " + exception.getMessage());
            }
        }

        private String content() {
            return String.join(System.lineSeparator(), lines);
        }
    }
}
