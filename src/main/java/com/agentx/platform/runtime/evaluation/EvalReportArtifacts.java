package com.agentx.platform.runtime.evaluation;

import java.nio.file.Path;
import java.util.Objects;

public record EvalReportArtifacts(
        Path outputDirectory,
        Path rawEvidencePath,
        Path scorecardPath,
        Path markdownReportPath,
        Path profileSnapshotPath
) {

    public EvalReportArtifacts {
        Objects.requireNonNull(outputDirectory, "outputDirectory must not be null");
        Objects.requireNonNull(rawEvidencePath, "rawEvidencePath must not be null");
        Objects.requireNonNull(scorecardPath, "scorecardPath must not be null");
        Objects.requireNonNull(markdownReportPath, "markdownReportPath must not be null");
        Objects.requireNonNull(profileSnapshotPath, "profileSnapshotPath must not be null");
    }
}
