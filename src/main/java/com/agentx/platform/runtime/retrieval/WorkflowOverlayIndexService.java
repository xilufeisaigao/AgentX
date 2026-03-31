package com.agentx.platform.runtime.retrieval;

import java.nio.file.Path;

public interface WorkflowOverlayIndexService {

    RepoIndexManifest buildOverlayIndex(String workflowRunId, String taskId, Path overlayRoot);
}
