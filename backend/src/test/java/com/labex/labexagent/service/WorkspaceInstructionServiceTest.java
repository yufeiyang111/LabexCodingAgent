package com.labex.labexagent.service;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorkspaceInstructionServiceTest {

    @Test
    void discoversAndLoadsLabexAgentMdFileInRoot(@TempDir Path workspaceDir) throws IOException {
        Files.writeString(workspaceDir.resolve("LabexAgent.md"), "# Labex Rules\nFollow modular architecture.");

        WorkspaceInstructionService service = new WorkspaceInstructionService();
        String instructions = service.loadInstructionsFromPath(workspaceDir);

        assertTrue(instructions.contains("<project_instructions>"));
        assertTrue(instructions.contains("Instruction Source: LabexAgent.md"));
        assertTrue(instructions.contains("Follow modular architecture."));
        assertTrue(instructions.contains("</project_instructions>"));
    }

    @Test
    void discoversAndLoadsLabexAgentMdInNestedUploadDirectory(@TempDir Path workspaceDir) throws IOException {
        // 模拟用户上传的项目解压到子目录 4425/ 下
        Path projectSubdir = workspaceDir.resolve("4425");
        Files.createDirectories(projectSubdir);
        Files.writeString(projectSubdir.resolve("LabexAgent.md"), "# Project 4425 Rules\nCustom instructions for 4425 project.");

        WorkspaceInstructionService service = new WorkspaceInstructionService();
        String instructions = service.loadInstructionsFromPath(workspaceDir);

        assertTrue(instructions.contains("<project_instructions>"));
        assertTrue(instructions.contains("Instruction Source: 4425/LabexAgent.md"));
        assertTrue(instructions.contains("Custom instructions for 4425 project."));
    }

    @Test
    void discoversAndLoadsAgentsMdFileWhenLabexAgentMdIsAbsent(@TempDir Path workspaceDir) throws IOException {
        Files.writeString(workspaceDir.resolve("AGENTS.md"), "# Project Rules\nAlways run tests before completing.");

        WorkspaceInstructionService service = new WorkspaceInstructionService();
        String instructions = service.loadInstructionsFromPath(workspaceDir);

        assertTrue(instructions.contains("<project_instructions>"));
        assertTrue(instructions.contains("Instruction Source: AGENTS.md"));
        assertTrue(instructions.contains("Always run tests before completing."));
    }

    @Test
    void discoversAndLoadsSubRulesUnderDotLabexRules(@TempDir Path workspaceDir) throws IOException {
        Path rulesDir = workspaceDir.resolve(".labex").resolve("rules");
        Files.createDirectories(rulesDir);
        Files.writeString(rulesDir.resolve("01-security.md"), "Never expose API tokens.");

        WorkspaceInstructionService service = new WorkspaceInstructionService();
        String instructions = service.loadInstructionsFromPath(workspaceDir);

        assertTrue(instructions.contains("<project_instructions>"));
        assertTrue(instructions.contains(".labex/rules/01-security.md"));
        assertTrue(instructions.contains("Never expose API tokens."));
    }

    @Test
    void returnsEmptyWhenNoInstructionFilesExist(@TempDir Path workspaceDir) {
        WorkspaceInstructionService service = new WorkspaceInstructionService();
        String instructions = service.loadInstructionsFromPath(workspaceDir);

        assertTrue(instructions.isEmpty());
    }
}
