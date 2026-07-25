package com.labex.labexagent.secret;

import com.labex.service.AgentMcpServerService;
import com.labex.service.AgentModelConfigService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * Converts legacy plaintext credential columns after the additive schema migration has run.
 */
@Component
public class SecretCredentialMigrationRunner implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(SecretCredentialMigrationRunner.class);

    private final AgentModelConfigService modelConfigService;
    private final AgentMcpServerService mcpServerService;

    public SecretCredentialMigrationRunner(
            AgentModelConfigService modelConfigService,
            AgentMcpServerService mcpServerService) {
        this.modelConfigService = modelConfigService;
        this.mcpServerService = mcpServerService;
    }

    @Override
    public void run(ApplicationArguments args) {
        int modelCredentials = modelConfigService.migrateLegacySecrets();
        int mcpCredentials = mcpServerService.migrateLegacySecrets();
        if (modelCredentials > 0 || mcpCredentials > 0) {
            log.info("Migrated {} model credentials and {} MCP credentials into the encrypted secret store",
                    modelCredentials, mcpCredentials);
        }
    }
}
