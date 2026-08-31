package com.labex.labexagent.cache;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(ConversationCacheProperties.class)
public class ConversationCacheConfiguration {
}
