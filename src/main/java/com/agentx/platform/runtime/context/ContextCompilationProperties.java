package com.agentx.platform.runtime.context;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;
import java.time.Duration;

@ConfigurationProperties("agentx.platform.context")
public class ContextCompilationProperties {

    private Path artifactRoot = Path.of(System.getProperty("java.io.tmpdir"), "agentx-context-packs");
    private Duration retention = Duration.ofHours(12);
    private int maxPackSize = 128_000;

    public Path getArtifactRoot() {
        return artifactRoot;
    }

    public void setArtifactRoot(Path artifactRoot) {
        this.artifactRoot = artifactRoot;
    }

    public Duration getRetention() {
        return retention;
    }

    public void setRetention(Duration retention) {
        this.retention = retention;
    }

    public int getMaxPackSize() {
        return maxPackSize;
    }

    public void setMaxPackSize(int maxPackSize) {
        this.maxPackSize = maxPackSize;
    }
}
