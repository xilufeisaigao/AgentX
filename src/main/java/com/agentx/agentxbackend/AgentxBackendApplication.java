package com.agentx.agentxbackend;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class AgentxBackendApplication {

    public static void main(String[] args) {
        SpringApplication.run(AgentxBackendApplication.class, args);
    }

}
