package com.labex.auth;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class OAuthProductionEnvironmentTemplateTest {
    @Test
    void productionTemplateDeclaresDedicatedBindingRedirect() throws Exception {
        String template = Files.readString(Path.of("..", "deploy", "linux", ".env.production.example"));

        assertTrue(template.contains(
                "LABEX_AGENT_AUTH_OAUTH_BINDING_FRONTEND_CALLBACK=https://labexagent.123845.xyz/projects"));
    }
}
