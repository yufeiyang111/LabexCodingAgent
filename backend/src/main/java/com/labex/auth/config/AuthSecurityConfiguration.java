package com.labex.auth.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(AuthSecurityProperties.class)
public class AuthSecurityConfiguration {
}
