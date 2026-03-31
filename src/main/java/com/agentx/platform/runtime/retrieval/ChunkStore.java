package com.agentx.platform.runtime.retrieval;

public interface ChunkStore {

    RepoIndexManifest write(RepoIndexManifest manifest);
}
