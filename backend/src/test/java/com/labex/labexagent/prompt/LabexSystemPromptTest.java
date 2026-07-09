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
}
