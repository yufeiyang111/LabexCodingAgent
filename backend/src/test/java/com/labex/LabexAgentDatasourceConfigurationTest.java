package com.labex;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Configuration;

class LabexAgentDatasourceConfigurationTest {
    @Test
    void loadsMysqlDatasourceCredentialsFromProjectDotEnv() throws Exception {
        String originalUserDir = System.getProperty("user.dir");
        Path projectDir = Files.createTempDirectory("labex-agent-config-");
        Files.writeString(projectDir.resolve(".env"), """
                LABEX_AGENT_DB_URL=jdbc:mysql://127.0.0.1:3307/labex_agent_from_env
                LABEX_AGENT_DB_USERNAME=labex_user
                LABEX_AGENT_DB_PASSWORD=env-secret
                """, StandardCharsets.UTF_8);

        try {
            System.setProperty("user.dir", projectDir.toString());
            try (ConfigurableApplicationContext context = new SpringApplicationBuilder(TestConfiguration.class)
                    .web(WebApplicationType.NONE)
                    .properties("spring.config.location=classpath:/")
                    .run()) {
                assertThat(context.getEnvironment().getProperty("spring.datasource.driver-class-name"))
                        .isEqualTo("com.mysql.cj.jdbc.Driver");
                assertThat(context.getEnvironment().getProperty("spring.datasource.url"))
                        .isEqualTo("jdbc:mysql://127.0.0.1:3307/labex_agent_from_env");
                assertThat(context.getEnvironment().getProperty("spring.datasource.username"))
                        .isEqualTo("labex_user");
                assertThat(context.getEnvironment().getProperty("spring.datasource.password"))
                        .isEqualTo("env-secret");
            }
        } finally {
            System.setProperty("user.dir", originalUserDir);
        }
    }

    @Configuration
    static class TestConfiguration {
    }
}
