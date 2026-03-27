package com.agentx.platform.runtime.persistence.mybatis.config;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Configuration;

@Configuration
@MapperScan("com.agentx.platform.runtime.persistence.mybatis.mapper")
public class MybatisPersistenceConfig {
}
