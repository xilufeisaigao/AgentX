package com.agentx.platform.runtime.retrieval;

import com.agentx.platform.runtime.context.RetrievalSnippet;

import java.util.List;

public interface LexicalChunkRetriever {

    List<RetrievalSnippet> retrieve(List<IndexedChunk> chunks, RetrievalQuery query, int limit);
}
