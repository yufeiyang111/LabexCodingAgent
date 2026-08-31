package com.labex.labexagent.workspace.cleanup;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(AgentEphemeralCleanupProperties.class)
public class AgentEphemeralCleanupConfiguration {
}
