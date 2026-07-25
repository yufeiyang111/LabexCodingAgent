package com.labex.labexagent.prompt;

import static org.assertj.core.api.Assertions.assertThat;

import com.labex.entity.StudentProject;
import org.junit.jupiter.api.Test;

class LabexSystemPromptTest {
    @Test
    void systemPromptExplainsProjectMemoryInitializationRules() {
        StudentProject project = new StudentProject();
        project.setProjectName("PromptWorkspace");
        project.setWorkspacePath("D:/workspaces/prompt");
        project.setStructureJson("{}");

        String prompt = LabexSystemPrompt.buildSystemPrompt(project, "tools");

        assertThat(prompt)
                .contains("Project memory and init rules")
                .contains("Context Contract")
                .contains("Repository Index")
                .contains("Prompt/Context Initialization Rules")
                .contains("Do not preserve noisy facts");
    }

    @Test
    void systemPromptPromotesVisibleLanguagePolicyAboveEnglishInstructions() {
        StudentProject project = new StudentProject();
        project.setProjectName("PromptWorkspace");
        project.setWorkspacePath("D:/workspaces/prompt");
        project.setStructureJson("{}");

        String prompt = LabexSystemPrompt.buildSystemPrompt(project, "tools", "zh");

        assertThat(prompt)
                .contains("<visible_language>")
                .contains("User visible language: Simplified Chinese")
                .contains("All user-visible thinking, status updates, questions, option labels, tool summaries, error explanations, and final answers MUST use Simplified Chinese")
                .contains("This language rule overrides the English wording used elsewhere in this system prompt");
    }

    @Test
    void systemPromptDoesNotInjectAnUnboundedPersistedProjectTree() {
        StudentProject project = new StudentProject();
        project.setProjectName("LargeWorkspace");
        project.setWorkspacePath("D:/workspaces/large");
        project.setStructureJson("x".repeat(3_800_000));

        String prompt = LabexSystemPrompt.buildSystemPrompt(project, "tools");

        assertThat(prompt)
                .contains("project_structure_summary")
                .contains("omitted from the system prompt")
                .hasSizeLessThan(40_000)
                .doesNotContain("x".repeat(20_000));
    }


    @Test
    void systemPromptOmitsOversizedPersistedProjectTrees() {
        StudentProject project = new StudentProject();
        project.setProjectName("LargeWorkspace");
        project.setWorkspacePath("D:/workspaces/large");
        project.setStructureJson("x".repeat(50_000));

        String prompt = LabexSystemPrompt.buildSystemPrompt(project, "tools");

        assertThat(prompt)
                .contains("Stored project tree has 50000 characters")
                .contains("repository map and targeted file tools")
                .doesNotContain("x".repeat(20_000));
    }

    @Test
    void systemPromptUsesTheSandboxAliasInsteadOfTheHostWorkspacePath() {
        StudentProject project = new StudentProject();
        project.setProjectName("PromptWorkspace");
        project.setWorkspacePath("D:/workspaces/prompt");
        project.setStructureJson("{}");

        String prompt = LabexSystemPrompt.buildSystemPrompt(project, "tools");

        assertThat(prompt)
                .contains("workspace_root: /workspace")
                .doesNotContain("D:/workspaces/prompt");
    }
}

