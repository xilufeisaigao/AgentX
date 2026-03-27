package com.agentx.platform;

import com.agentx.platform.controlplane.config.PlatformKernelProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(PlatformKernelProperties.class)
public class AgentxPlatformApplication {

	public static void main(String[] args) {
		SpringApplication.run(AgentxPlatformApplication.class, args);
	}

}
