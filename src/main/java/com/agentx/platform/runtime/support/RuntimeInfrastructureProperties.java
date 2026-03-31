package com.agentx.platform.runtime.support;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;
import java.time.Duration;

@ConfigurationProperties("agentx.platform.runtime")
public class RuntimeInfrastructureProperties {

    private Path repoRoot;
    private String baseBranch = "main";
    private Path workspaceRoot = Path.of(System.getProperty("java.io.tmpdir"), "agentx-runtime-workspaces");
    private int dispatchBatchSize = 4;
    private Duration leaseTtl = Duration.ofSeconds(20);
    private Duration heartbeatInterval = Duration.ofSeconds(5);
    private Duration supervisorScanInterval = Duration.ofSeconds(2);
    private Duration driverScanInterval = Duration.ofSeconds(2);
    private Duration blockingPollInterval = Duration.ofMillis(200);
    private Duration blockingTimeout = Duration.ofSeconds(45);
    private int maxRunAttempts = 2;
    private boolean driverEnabled = true;
    private boolean supervisorEnabled = true;
    private final Docker docker = new Docker();

    public Path getRepoRoot() {
        return repoRoot;
    }

    public void setRepoRoot(Path repoRoot) {
        this.repoRoot = repoRoot;
    }

    public String getBaseBranch() {
        return baseBranch;
    }

    public void setBaseBranch(String baseBranch) {
        this.baseBranch = baseBranch;
    }

    public Path getWorkspaceRoot() {
        return workspaceRoot;
    }

    public void setWorkspaceRoot(Path workspaceRoot) {
        this.workspaceRoot = workspaceRoot;
    }

    public int getDispatchBatchSize() {
        return dispatchBatchSize;
    }

    public void setDispatchBatchSize(int dispatchBatchSize) {
        this.dispatchBatchSize = dispatchBatchSize;
    }

    public Duration getLeaseTtl() {
        return leaseTtl;
    }

    public void setLeaseTtl(Duration leaseTtl) {
        this.leaseTtl = leaseTtl;
    }

    public Duration getHeartbeatInterval() {
        return heartbeatInterval;
    }

    public void setHeartbeatInterval(Duration heartbeatInterval) {
        this.heartbeatInterval = heartbeatInterval;
    }

    public Duration getSupervisorScanInterval() {
        return supervisorScanInterval;
    }

    public void setSupervisorScanInterval(Duration supervisorScanInterval) {
        this.supervisorScanInterval = supervisorScanInterval;
    }

    public Duration getDriverScanInterval() {
        return driverScanInterval;
    }

    public void setDriverScanInterval(Duration driverScanInterval) {
        this.driverScanInterval = driverScanInterval;
    }

    public Duration getBlockingPollInterval() {
        return blockingPollInterval;
    }

    public void setBlockingPollInterval(Duration blockingPollInterval) {
        this.blockingPollInterval = blockingPollInterval;
    }

    public Duration getBlockingTimeout() {
        return blockingTimeout;
    }

    public void setBlockingTimeout(Duration blockingTimeout) {
        this.blockingTimeout = blockingTimeout;
    }

    public int getMaxRunAttempts() {
        return maxRunAttempts;
    }

    public void setMaxRunAttempts(int maxRunAttempts) {
        this.maxRunAttempts = maxRunAttempts;
    }

    public boolean isDriverEnabled() {
        return driverEnabled;
    }

    public void setDriverEnabled(boolean driverEnabled) {
        this.driverEnabled = driverEnabled;
    }

    public boolean isSupervisorEnabled() {
        return supervisorEnabled;
    }

    public void setSupervisorEnabled(boolean supervisorEnabled) {
        this.supervisorEnabled = supervisorEnabled;
    }

    public Docker getDocker() {
        return docker;
    }

    public Path requiredRepoRoot() {
        if (repoRoot == null) {
            throw new IllegalStateException("agentx.platform.runtime.repo-root must be configured for real workspace execution");
        }
        return repoRoot.toAbsolutePath().normalize();
    }

    public static class Docker {

        private String binary = "docker";
        private String networkMode = "bridge";

        public String getBinary() {
            return binary;
        }

        public void setBinary(String binary) {
            this.binary = binary;
        }

        public String getNetworkMode() {
            return networkMode;
        }

        public void setNetworkMode(String networkMode) {
            this.networkMode = networkMode;
        }
    }
}
