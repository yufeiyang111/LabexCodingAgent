package com.labex.labexagent.service;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class AgentCommandArchitectureContractTest {

    @Test
    void keepsBuiltinCommandTemplatesAndAliasesAvailable() {
        com.labex.labexagent.command.CommandRegistry registry =
                new com.labex.labexagent.command.CommandRegistry();

        com.labex.labexagent.command.CommandInfo init = registry.getCommand("init");
        com.labex.labexagent.command.CommandInfo compactAlias = registry.getCommand("summarize");

        assertNotNull(init);
        assertFalse(init.template().isBlank());
        assertNotNull(compactAlias);
        assertFalse(compactAlias.template().isBlank());
    }

    @Test
    void removesTheUnreachableSecondCommandExecutionRuntime() throws Exception {
        Path retiredExecutor = Path.of(
                "src/main/java/com/labex/labexagent/command/CommandExecutor.java");
        String commandService = Files.readString(Path.of(
                "src/main/java/com/labex/labexagent/service/AgentCommandService.java"), StandardCharsets.UTF_8);

        assertFalse(Files.exists(retiredExecutor));
        assertFalse(commandService.contains("CommandExecutor"));
        assertTrue(commandService.contains("commandInfo.resolveTemplate(arguments)"));
    }
}