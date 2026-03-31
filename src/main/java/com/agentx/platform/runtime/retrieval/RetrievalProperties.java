package com.agentx.platform.runtime.retrieval;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;
import java.util.List;

@ConfigurationProperties("agentx.platform.retrieval")
public class RetrievalProperties {

    private Path indexRoot = Path.of(System.getProperty("java.io.tmpdir"), "agentx-retrieval-index");
    private int chunkSize = 24;
    private int topK = 8;
    private long maxFileSize = 512 * 1024;
    private List<String> indexedExtensions = List.of(".md", ".sql", ".yaml", ".yml", ".json", ".properties", ".xml", ".java", ".kt", ".gradle", ".txt");

    public Path getIndexRoot() {
        return indexRoot;
    }

    public void setIndexRoot(Path indexRoot) {
        this.indexRoot = indexRoot;
    }

    public int getChunkSize() {
        return chunkSize;
    }

    public void setChunkSize(int chunkSize) {
        this.chunkSize = chunkSize;
    }

    public int getTopK() {
        return topK;
    }

    public void setTopK(int topK) {
        this.topK = topK;
    }

    public long getMaxFileSize() {
        return maxFileSize;
    }

    public void setMaxFileSize(long maxFileSize) {
        this.maxFileSize = maxFileSize;
    }

    public List<String> getIndexedExtensions() {
        return indexedExtensions;
    }

    public void setIndexedExtensions(List<String> indexedExtensions) {
        this.indexedExtensions = indexedExtensions;
    }
}
