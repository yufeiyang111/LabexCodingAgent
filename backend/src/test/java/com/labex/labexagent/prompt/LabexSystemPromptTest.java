package com.labex.labexagent.prompt;

import static org.assertj.core.api.Assertions.assertThat;

import com.labex.entity.StudentProject;
import com.labex.labexagent.execution.WorkerShellDescriptor;
import com.labex.labexagent.runtime.profile.AgentRuntimeProfile;
import org.junit.jupiter.api.Test;

class LabexSystemPromptTest {
    @Test
    void systemPromptExplainsProjectMemoryInitializationRules() {
        String prompt = LabexSystemPrompt.buildSystemPrompt(project("PromptWorkspace", "D:/workspaces/prompt", "{}"), "tools");

        assertThat(prompt)
                .contains("Project memory and init rules")
                .contains("Context Contract")
                .contains("Repository Index")
                .contains("Prompt/Context Initialization Rules")
                .contains("Do not preserve noisy facts");
    }

    @Test
    void defaultPromptUsesTheLabexStandardPermissionProfile() {
        String prompt = LabexSystemPrompt.buildSystemPrompt(
                project("PromptWorkspace", "D:/workspaces/prompt", "{}"), "tools");

        assertThat(prompt)
                .contains("Permission profile: labex-standard")
                .doesNotContain("Permission profile: opencode");
    }
    @Test
    void systemPromptIsStableWhenSessionFactsMatchAndProjectTreeChanges() {
        StudentProject project = project("PromptWorkspace", "D:/workspaces/prompt", "first tree");
        WorkerShellDescriptor descriptor = WorkerShellDescriptor.bash("linux-wsl", "/bin/bash", "/workspace", true);

        String first = LabexSystemPrompt.buildSystemPrompt(project, "tools", "zh", descriptor, "labex-standard");
        project.setStructureJson("second tree with different files");
        String second = LabexSystemPrompt.buildSystemPrompt(project, "tools", "zh", descriptor, "labex-standard");

        assertThat(first)
                .isEqualTo(second)
                .contains("<visible_language>")
                .contains("<environment>")
                .contains("<command_policy>")
                .doesNotContain("project_structure_summary")
                .doesNotContain("first tree")
                .doesNotContain("second tree with different files");
    }

    @Test
    void systemPromptKeepsVisibleLanguageAndWorkerFactsAtSystemPriority() {
        String prompt = LabexSystemPrompt.buildSystemPrompt(
                project("PromptWorkspace", "D:/workspaces/prompt", "{}"), "tools", "zh",
                WorkerShellDescriptor.bash("linux-wsl", "/bin/bash", "/workspace", true), "labex-standard");

        assertThat(prompt)
                .startsWith("<visible_language>")
                .contains("User visible language: Simplified Chinese")
                .contains("<environment>")
                .contains("workspace_root: /workspace")
                .contains("execution_backend: linux-wsl")
                .contains("shell: bash")
                .contains("network: enabled")
                .contains("project_name: PromptWorkspace")
                .contains("<command_policy>")
                .contains("Permission profile: labex-standard")
                .doesNotContain("project_structure_summary");
    }

    @Test
    void systemPromptChangesWhenSessionRuntimeFactsChange() {
        StudentProject project = project("PromptWorkspace", "D:/workspaces/prompt", "{}");
        String linuxChinese = LabexSystemPrompt.buildSystemPrompt(project, "tools", "zh",
                WorkerShellDescriptor.bash("linux", "/bin/bash", "/workspace", true), "labex-standard");
        String windowsEnglish = LabexSystemPrompt.buildSystemPrompt(project, "tools", "en",
                WorkerShellDescriptor.powerShell("windows", "pwsh.exe", "C:/sandbox", false), "safe");

        assertThat(linuxChinese).isNotEqualTo(windowsEnglish);
        assertThat(windowsEnglish)
                .contains("User visible language: English")
                .contains("workspace_root: C:/sandbox")
                .contains("execution_backend: windows")
                .contains("shell: powershell")
                .contains("network: disabled")
                .contains("Permission profile: safe");
    }

    @Test
    void systemPromptExplainsTheLabexStandardRealShellContract() {
        String prompt = LabexSystemPrompt.buildSystemPrompt(
                project("PromptWorkspace", "D:/workspaces/prompt", "{}"), "tools", "zh",
                WorkerShellDescriptor.bash("linux-wsl", "/bin/bash", "/workspace", true), "labex-standard");

        assertThat(prompt)
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
    void systemPromptRequestsSafeStructuredMarkdownForUserVisibleFinalOutput() {
        String prompt = LabexSystemPrompt.buildSystemPrompt(project("PromptWorkspace", "D:/workspaces/prompt", "{}"), "tools");

        assertThat(prompt)
                .contains("Use GitHub-flavored Markdown only")
                .contains("Use `:::note`, `:::tip`, `:::success`, `:::warning`, `:::important`, or `:::error`")
                .contains("Do not emit raw HTML");
    }

    @Test
    void systemPromptDoesNotDuplicateTheToolNameList() {
        String prompt = LabexSystemPrompt.buildSystemPrompt(project("PromptWorkspace", "D:/workspaces/prompt", "{}"),
                "- read_file: reads a file\n- grep: searches code\n- bash: runs shell commands");

        assertThat(prompt)
                .doesNotContain("read_file: reads a file")
                .doesNotContain("grep: searches code")
                .doesNotContain("bash: runs shell commands")
                .contains("Tool usage guidelines");
    }

    @Test
    void systemPromptTreatsTodoAsOptionalProgressAndDoesNotRequireCreatePlanOrDedicatedTestTools() {
        String prompt = LabexSystemPrompt.buildSystemPrompt(project("PromptWorkspace", "D:/workspaces/prompt", "{}"), "tools");

        assertThat(prompt)
                .contains("For multi-step work, update the optional todo list when it improves clarity")
                .doesNotContain("start with create_plan")
                .doesNotContain("use create_plan to break task")
                .doesNotContain("Plan created and all tasks marked complete")
                .doesNotContain("use create_plan(action=\"complete\"")
                .doesNotContain("run_tests");
    }

    @Test
    void systemPromptNoLongerRecommendsCurlForVerification() {
        String prompt = LabexSystemPrompt.buildSystemPrompt(project("PromptWorkspace", "D:/workspaces/prompt", "{}"), "tools");

        assertThat(prompt)
                .doesNotContain("Test the endpoint with curl")
                .contains("Check `git status` to confirm only intended files changed");
    }

    @Test
    void nativeProfileAddsTheEvidenceDrivenDirectExecutionContract() {
        StudentProject project = project("PromptWorkspace", "D:/workspaces/prompt", "{}");
        WorkerShellDescriptor descriptor = WorkerShellDescriptor.bash("linux", "/bin/bash", "/workspace", true);

        String legacy = LabexSystemPrompt.buildSystemPrompt(project, "tools", "en", descriptor,
                "labex-standard", AgentRuntimeProfile.LABEX_LEGACY);
        String nativePrompt = LabexSystemPrompt.buildSystemPrompt(project, "tools", "en", descriptor,
                "labex-standard", AgentRuntimeProfile.LABEX_NATIVE);

        assertThat(legacy).doesNotContain("<labex_native_runtime>");
        assertThat(nativePrompt)
                .contains("<labex_native_runtime>")
                .contains("actual tool and verification evidence")
                .contains("Progress is a harness projection");
    }

    @Test
    void systemPromptIncludesOpenCodeCodingDisciplineAndReflectionCycle() {
        String prompt = LabexSystemPrompt.buildSystemPrompt(project("PromptWorkspace", "D:/workspaces/prompt", "{}"), "tools");

        assertThat(prompt)
                .contains("<coding_discipline>")
                .contains("Following existing conventions")
                .contains("NEVER assume a library/package is available")
                .contains("DO NOT add unsolicited comments")
                .contains("Atomic and idiom-preserving edits")
                .contains("Precise Code References")
                .contains("file_path:line_number")
                .contains("Engineering workflow & Reflection cycle")
                .contains("Observe & Reflect (Feedback Loop & Debugging)");
    }

    private static StudentProject project(String name, String workspacePath, String structureJson) {
        StudentProject project = new StudentProject();
        project.setProjectName(name);
        project.setWorkspacePath(workspacePath);
        project.setStructureJson(structureJson);
        return project;
    }
}
