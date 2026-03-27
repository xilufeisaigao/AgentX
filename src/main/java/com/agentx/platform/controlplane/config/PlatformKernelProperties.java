package com.agentx.platform.controlplane.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "agentx.platform")
public class PlatformKernelProperties {

    private final Workflow workflow = new Workflow();
    private final Agent agent = new Agent();

    public Workflow getWorkflow() {
        return workflow;
    }

    public Agent getAgent() {
        return agent;
    }

    public static class Workflow {
        private boolean userDefinedEnabled = false;
        private boolean agentNodeRebindingEnabled = true;
        private boolean parameterOverrideEnabled = true;

        public boolean isUserDefinedEnabled() {
            return userDefinedEnabled;
        }

        public void setUserDefinedEnabled(boolean userDefinedEnabled) {
            this.userDefinedEnabled = userDefinedEnabled;
        }

        public boolean isAgentNodeRebindingEnabled() {
            return agentNodeRebindingEnabled;
        }

        public void setAgentNodeRebindingEnabled(boolean agentNodeRebindingEnabled) {
            this.agentNodeRebindingEnabled = agentNodeRebindingEnabled;
        }

        public boolean isParameterOverrideEnabled() {
            return parameterOverrideEnabled;
        }

        public void setParameterOverrideEnabled(boolean parameterOverrideEnabled) {
            this.parameterOverrideEnabled = parameterOverrideEnabled;
        }
    }

    public static class Agent {
        private boolean architectSuggestionEnabled = true;
        private boolean architectAutoPoolEnabled = false;

        public boolean isArchitectSuggestionEnabled() {
            return architectSuggestionEnabled;
        }

        public void setArchitectSuggestionEnabled(boolean architectSuggestionEnabled) {
            this.architectSuggestionEnabled = architectSuggestionEnabled;
        }

        public boolean isArchitectAutoPoolEnabled() {
            return architectAutoPoolEnabled;
        }

        public void setArchitectAutoPoolEnabled(boolean architectAutoPoolEnabled) {
            this.architectAutoPoolEnabled = architectAutoPoolEnabled;
        }
    }
}

