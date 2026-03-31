package com.agentx.platform.runtime.agentkernel.model;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties("agentx.platform.model")
public class AgentModelProperties {

    private Duration timeout = Duration.ofSeconds(20);
    private int maxRetries = 1;
    private final DeepSeek deepseek = new DeepSeek();

    public Duration getTimeout() {
        return timeout;
    }

    public void setTimeout(Duration timeout) {
        this.timeout = timeout;
    }

    public int getMaxRetries() {
        return maxRetries;
    }

    public void setMaxRetries(int maxRetries) {
        this.maxRetries = maxRetries;
    }

    public DeepSeek getDeepseek() {
        return deepseek;
    }

    public static class DeepSeek {

        private String baseUrl = "https://api.deepseek.com";
        private String apiKey;

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public String getApiKey() {
            return apiKey;
        }

        public void setApiKey(String apiKey) {
            this.apiKey = apiKey;
        }
    }
}
