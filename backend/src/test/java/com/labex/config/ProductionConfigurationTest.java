package com.labex.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;

class ProductionConfigurationTest {

    private static final String MASTER_KEY = Base64.getEncoder().encodeToString(new byte[32]);
    private static final String LONG_JWT_SECRET = "x".repeat(64);

    private Map<String, Object> validProperties() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("spring.datasource.url", "jdbc:mysql://db.internal:3306/labex_agent");
        properties.put("spring.datasource.username", "labex");
        properties.put("spring.datasource.password", "production-password");
        properties.put("jwt.secret", LONG_JWT_SECRET);
        properties.put("labex-agent.secret-store.master-key", MASTER_KEY);
        properties.put("labex-agent.execution.permission-profile", "opencode");
        properties.put("labex-agent.cors.allowed-origins", "https://agent.example.com");
        properties.put("labex-agent.websocket.allowed-origins", "https://agent.example.com");
        properties.put("labex-agent.project-base-path", "/srv/labex-agent/workspaces");
        properties.put("rag.upload-path", "/srv/labex-agent/uploads");
        return properties;
    }

    /** 以 production profile 启动 validator；成功返回 null，失败返回根因消息。 */
    private String attemptStartup(Map<String, Object> overrides) {
        Map<String, Object> properties = validProperties();
        properties.putAll(overrides);
        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        context.getEnvironment().setActiveProfiles("production");
        context.getEnvironment().getPropertySources()
                .addFirst(new MapPropertySource("production-test", properties));
        context.register(ProductionStartupValidator.class);
        try {
            context.refresh();
            return null;
        } catch (RuntimeException failure) {
            return rootCause(failure).getMessage();
        } finally {
            context.close();
        }
    }

    private Throwable rootCause(Throwable throwable) {
        Throwable cause = throwable;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        return cause;
    }

    @Test
    void acceptsACompleteProductionConfiguration() {
        assertThat(attemptStartup(Map.of())).isNull();
    }

    @Test
    void failsFastWhenDatabaseCredentialsAreMissing() {
        String message = attemptStartup(Map.of(
                "spring.datasource.url", "",
                "spring.datasource.username", "",
                "spring.datasource.password", ""));
        assertThat(message).contains("spring.datasource.url", "spring.datasource.username",
                "spring.datasource.password");
    }

    @Test
    void failsFastWhenJwtSecretIsTheLocalPlaceholder() {
        String message = attemptStartup(Map.of(
                "jwt.secret", "labex-agent-local-dev-secret-key-change-me-please-64-bytes-minimum"));
        assertThat(message).contains("jwt.secret");
    }

    @Test
    void failsFastWhenJwtSecretIsShorterThanSixtyFourBytes() {
        assertThat(attemptStartup(Map.of("jwt.secret", "short-secret"))).contains("jwt.secret");
    }

    @Test
    void failsFastWhenSecretStoreMasterKeyIsMissingOrMalformed() {
        assertThat(attemptStartup(Map.of("labex-agent.secret-store.master-key", "")))
                .contains("master-key");
        assertThat(attemptStartup(Map.of("labex-agent.secret-store.master-key", "not-base64!!!")))
                .contains("master-key");
    }

    @Test
    void rejectsFullAccessPermissionProfileInProduction() {
        assertThat(attemptStartup(Map.of("labex-agent.execution.permission-profile", "full_access")))
                .contains("full_access");
    }

    @Test
    void rejectsMissingWildcardOrPlainHttpCorsOrigins() {
        assertThat(attemptStartup(Map.of("labex-agent.cors.allowed-origins", "")))
                .contains("cors.allowed-origins");
        assertThat(attemptStartup(Map.of("labex-agent.cors.allowed-origins", "*")))
                .contains("cors.allowed-origins");
        assertThat(attemptStartup(Map.of("labex-agent.cors.allowed-origins", "http://agent.example.com")))
                .contains("HTTPS");
    }

    @Test
    void rejectsLocalhostWebsocketOriginsInProduction() {
        assertThat(attemptStartup(Map.of(
                "labex-agent.websocket.allowed-origins",
                "http://localhost:3000,http://127.0.0.1:3000")))
                .contains("websocket");
        assertThat(attemptStartup(Map.of("labex-agent.websocket.allowed-origins", "*")))
                .contains("websocket");
    }

    @Test
    void rejectsWindowsDriveLetterPathsInProduction() {
        assertThat(attemptStartup(Map.of("labex-agent.project-base-path", "D:/LabexAgent/workspaces")))
                .contains("/srv/labex-agent/workspaces");
        assertThat(attemptStartup(Map.of("rag.upload-path", "D:/LabexAgent/uploads")))
                .contains("/srv/labex-agent/uploads");
    }

    @Test
    void reportsAllProblemsTogetherInsteadOfOneAtATime() {
        String message = attemptStartup(Map.of(
                "spring.datasource.password", "",
                "jwt.secret", "short",
                "labex-agent.cors.allowed-origins", ""));
        assertThat(message)
                .contains("spring.datasource.password")
                .contains("jwt.secret")
                .contains("cors.allowed-origins");
    }
}
