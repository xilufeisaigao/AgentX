package com.agentx.platform.runtime.retrieval;

import java.nio.file.Path;
import java.util.List;

public interface SymbolRetriever {

    List<String> symbolsFor(Path filePath, List<String> lines);
}
