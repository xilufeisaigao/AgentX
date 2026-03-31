package com.agentx.platform.runtime.evaluation;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;

@ConfigurationProperties("agentx.platform.evaluation")
public class WorkflowEvalProperties {

    private Path artifactRoot = Path.of("artifacts", "evaluation-runs");
    private boolean traceCollectionEnabled = true;

    public Path getArtifactRoot() {
        return artifactRoot;
    }

    public void setArtifactRoot(Path artifactRoot) {
        this.artifactRoot = artifactRoot;
    }

    public boolean isTraceCollectionEnabled() {
        return traceCollectionEnabled;
    }

    public void setTraceCollectionEnabled(boolean traceCollectionEnabled) {
        this.traceCollectionEnabled = traceCollectionEnabled;
    }
}
