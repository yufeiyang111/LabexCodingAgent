package com.labex.labexagent.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.labex.labexagent.runtime.AgentContext;
import com.labex.labexagent.runtime.profile.AgentRuntimeProfile;
import java.util.List;
import org.junit.jupiter.api.Test;

class ToolSelectionPolicyTest {

    @Test
    void buildProfileExposesAtomicToolsAndKeepsLegacyControlToolsExecutableButHidden() {
        ToolRegistry registry = registry();
        registry.registerDynamicTool("mcp_weather", tool("mcp_weather"));
        ToolSelectionPolicy policy = new ToolSelectionPolicy();
        ToolSelectionPolicy.Capabilities all = new ToolSelectionPolicy.Capabilities(true, true, true, true);

        assertEquals(List.of(
                "read_file", "read_tool_output", "glob", "grep", "edit_file", "write_file", "shell",
                "todo_write", "question", "web_search", "web_fetch", "understand_image", "mcp_weather"),
                names(policy.select(registry, "build", all)));

        assertNotNull(registry.get("create_plan"));
        assertNotNull(registry.get("run_tests"));
        assertNotNull(registry.get("start_preview"));
        assertFalse(policy.isSelected(policy.select(registry, "build", all), "create_plan"));
        assertFalse(policy.isSelected(policy.select(registry, "build", all), "run_tests"));
        assertFalse(policy.isSelected(policy.select(registry, "build", all), "start_preview"));
    }

    @Test
    void readOnlyProfilesKeepTodoOptionalAndExcludeBuildWorkflowTools() {
        ToolRegistry registry = registry();
        ToolSelectionPolicy policy = new ToolSelectionPolicy();
        ToolSelectionPolicy.Capabilities all = new ToolSelectionPolicy.Capabilities(true, true, true, true);

        assertEquals(List.of(
                "read_file", "read_tool_output", "glob", "grep", "todo_write", "question", "web_search",
                "web_fetch", "understand_image", "plan_exit"),
                names(policy.select(registry, "plan", all)));
        assertEquals(List.of(
                "read_file", "read_tool_output", "glob", "grep", "question", "web_search", "web_fetch",
                "understand_image"), names(policy.select(registry, "explore", all)));
    }

    @Test
    void nativeProfileKeepsAtomicCapabilitiesAndLoadsOnDemandExtensionsWithoutHarnessControls() {
        ToolRegistry registry = registry();
        registry.registerDynamicTool("mcp_weather", tool("mcp_weather"));
        ToolSelectionPolicy policy = new ToolSelectionPolicy();
        ToolSelectionPolicy.Capabilities all = new ToolSelectionPolicy.Capabilities(true, true, true, true, true);

        assertEquals(List.of(
                "read_file", "read_tool_output", "glob", "grep", "write_file", "apply_patch", "shell",
                "todo_write", "question", "web_search", "web_fetch", "understand_image", "lsp", "skill"),
                names(policy.select(registry, "build", all, AgentRuntimeProfile.LABEX_NATIVE)));
        assertEquals(List.of(
                "read_file", "read_tool_output", "glob", "grep", "todo_write", "question", "web_search",
                "web_fetch", "understand_image", "lsp", "skill", "plan_exit"),
                names(policy.select(registry, "plan", all, AgentRuntimeProfile.LABEX_NATIVE)));
        assertFalse(policy.isSelected(policy.select(registry, "build", all, AgentRuntimeProfile.LABEX_NATIVE), "edit_file"));
        assertTrue(policy.isSelected(policy.select(registry, "build", all, AgentRuntimeProfile.LABEX_NATIVE), "apply_patch"));
        assertTrue(policy.isSelected(policy.select(registry, "build", all, AgentRuntimeProfile.LABEX_NATIVE), "todo_write"));
        assertFalse(policy.isSelected(policy.select(registry, "build", all, AgentRuntimeProfile.LABEX_NATIVE), "run_tests"));
        assertFalse(policy.isSelected(policy.select(registry, "build", all, AgentRuntimeProfile.LABEX_NATIVE), "mcp_weather"));
    }

    @Test
    void nativeBuildExposesOnlyTheUnifiedShellEntryPoint() {
        ToolSelectionPolicy policy = new ToolSelectionPolicy();
        ToolSelectionPolicy.Capabilities all = new ToolSelectionPolicy.Capabilities(true, true, true, true, true);
        List<ToolDefinition> selected = policy.select(registry(), "build", all, AgentRuntimeProfile.LABEX_NATIVE);

        assertTrue(policy.isSelected(selected, "shell"));
        assertFalse(policy.isSelected(selected, "bash"));
        assertFalse(policy.isSelected(selected, "run_command"));
        assertFalse(policy.isSelected(selected, "run_tests"));
        assertFalse(policy.isSelected(selected, "execute_code"));
    }

    @Test
    void nativeProfileDoesNotExposeTheSkillReaderWhenTheUserHasNoEnabledSkillCatalog() {
        ToolSelectionPolicy policy = new ToolSelectionPolicy();
        ToolSelectionPolicy.Capabilities withoutSkills = new ToolSelectionPolicy.Capabilities(
                true, false, true, true, false);

        List<ToolDefinition> selected = policy.select(registry(), "build", withoutSkills,
                AgentRuntimeProfile.LABEX_NATIVE);

        assertTrue(policy.isSelected(selected, "lsp"));
        assertFalse(policy.isSelected(selected, "skill"));
    }
    @Test
    void capabilityFilteringOnlyRemovesOptionalCapabilitiesFromTheCoreProfile() {
        ToolRegistry registry = registry();
        registry.registerDynamicTool("mcp_weather", tool("mcp_weather"));
        ToolSelectionPolicy policy = new ToolSelectionPolicy();

        List<ToolDefinition> selected = policy.select(registry, "build",
                new ToolSelectionPolicy.Capabilities(false, false, false, false));

        assertEquals(List.of(
                "read_file", "read_tool_output", "glob", "grep", "edit_file", "write_file", "shell",
                "todo_write", "question"), names(selected));
        assertFalse(policy.isSelected(selected, "understand_image"));
        assertFalse(policy.isSelected(selected, "mcp_weather"));
        assertTrue(policy.isSelected(selected, "shell"));
    }

    @Test
    void unknownModesExposeNoTools() {
        ToolSelectionPolicy policy = new ToolSelectionPolicy();
        assertTrue(policy.select(registry(), "unknown", ToolSelectionPolicy.Capabilities.none()).isEmpty());
    }

    private static ToolRegistry registry() {
        return new ToolRegistry(List.of(
                tool("read_file"), tool("read_tool_output"), tool("glob"), tool("grep"),
                tool("edit_file"), tool("write_file"), tool("apply_patch"), tool("shell"),
                tool("todo_write"), tool("question"), tool("web_search"), tool("web_fetch"),
                tool("understand_image"), tool("lsp"), tool("skill"), tool("task"), tool("mcp_call"),
                tool("create_plan"), tool("plan_exit"), tool("bash"), tool("run_command"), tool("run_tests"),
                tool("execute_code"),
                tool("context_note"), tool("list_files"), tool("project_overview"), tool("repo_map"),
                tool("retrieve_context"), tool("propose_project_config"), tool("start_preview"),
                tool("stop_preview")));
    }

    private static List<String> names(List<ToolDefinition> definitions) {
        return definitions.stream().map(ToolDefinition::getName).toList();
    }

    private static AgentTool tool(String name) {
        return new AgentTool() {
            @Override
            public ToolDefinition definition() {
                return ToolDefinition.builder().name(name).description(name).build();
            }

            @Override
            public ToolResult execute(AgentContext context, JsonObject args) {
                return ToolResult.ok("ok");
            }
        };
    }
}
