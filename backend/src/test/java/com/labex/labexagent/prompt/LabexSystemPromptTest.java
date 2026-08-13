package com.labex.labexagent.prompt;

import static org.assertj.core.api.Assertions.assertThat;

import com.labex.entity.StudentProject;
import com.labex.labexagent.execution.WorkerShellDescriptor;
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
    void systemPromptRequestsSafeStructuredMarkdownForUserVisibleFinalOutput() {
        StudentProject project = new StudentProject();
        project.setProjectName("PromptWorkspace");
        project.setWorkspacePath("D:/workspaces/prompt");
        project.setStructureJson("{}");

        String prompt = LabexSystemPrompt.buildSystemPrompt(project, "tools", "zh");

        assertThat(prompt)
                .contains("Use GitHub-flavored Markdown only")
                .contains("Use `:::note`, `:::tip`, `:::success`, `:::warning`, `:::important`, or `:::error`")
                .contains("Do not emit raw HTML");
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

    @Test
    void systemPromptExplainsTheOpenCodeRealShellContract() {
        StudentProject project = new StudentProject();
        project.setProjectName("PromptWorkspace");
        project.setWorkspacePath("D:/workspaces/prompt");
        project.setStructureJson("{}");

        String prompt = LabexSystemPrompt.buildSystemPrompt(project, "tools", "zh",
                WorkerShellDescriptor.bash("linux-wsl", "/bin/bash", "/workspace", true), "opencode");

        assertThat(prompt)
                .contains("<command_policy>")
                .contains("Execution backend: linux-wsl")
                .contains("Shell: Bash")
                .contains("Network: enabled")
                .contains("Permission profile: opencode")
                .contains("Bash/PowerShell syntax is supported")
                .contains("cd frontend&&npm install")
                .contains("workdir")
                .contains("timeout` in milliseconds")
                .contains("output_path")
                .contains("Use `read_file` with `output_path`")
                .doesNotContain("exactly one restricted direct command")
                .doesNotContain("never use `cd <dir> && <command>`");
    }

    @Test
    void systemPromptUsesInjectedPowerShellDescriptor() {
        StudentProject project = new StudentProject();
        project.setProjectName("PromptWorkspace");
        project.setStructureJson("{}");

        String prompt = LabexSystemPrompt.buildSystemPrompt(project, "tools", "en",
                WorkerShellDescriptor.powerShell("windows", "pwsh.exe", "C:/sandbox", false), "safe");

        assertThat(prompt)
                .contains("workspace_root: C:/sandbox")
                .contains("execution_backend: windows")
                .contains("shell: powershell")
                .contains("network: disabled")
                .contains("Permission profile: safe");
    }

    @Test
    void systemPromptDoesNotDuplicateTheToolNameList() {
        StudentProject project = new StudentProject();
        project.setProjectName("PromptWorkspace");
        project.setWorkspacePath("D:/workspaces/prompt");
        project.setStructureJson("{}");

        String prompt = LabexSystemPrompt.buildSystemPrompt(project,
                "- read_file: reads a file\n- grep: searches code\n- bash: runs shell commands");

        assertThat(prompt)
                .doesNotContain("read_file: reads a file")
                .doesNotContain("grep: searches code")
                .doesNotContain("bash: runs shell commands")
                .contains("Tool usage guidelines");
    }

    @Test
    void systemPromptNoLongerRecommendsCurlForVerification() {
        StudentProject project = new StudentProject();
        project.setProjectName("PromptWorkspace");
        project.setWorkspacePath("D:/workspaces/prompt");
        project.setStructureJson("{}");

        String prompt = LabexSystemPrompt.buildSystemPrompt(project, "tools");

        assertThat(prompt)
                .doesNotContain("Test the endpoint with curl")
                .contains("Check `git status` to confirm only intended files changed");
    }
}

