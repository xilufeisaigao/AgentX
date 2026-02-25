package com.agentx.agentxbackend.mergegate.application.port.out;

public interface TaskStateMutationPort {

    void markDone(String taskId);
}
