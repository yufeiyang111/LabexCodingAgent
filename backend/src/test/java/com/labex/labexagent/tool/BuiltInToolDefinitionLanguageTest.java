package com.labex.labexagent.tool;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class BuiltInToolDefinitionLanguageTest {
    private static final String TOOL_PACKAGE = "com.labex.labexagent.tool.impl.";
    private static final Pattern CJK_CHARACTER = Pattern.compile("[\\p{IsHan}\\p{IsHiragana}\\p{IsKatakana}\\p{IsHangul}]");
    private static final List<String> STATIC_TOOL_CLASSES = List.of(
            "ApplyPatchTool", "BashTool", "ContextNoteTool", "CreatePlanTool", "EditFileTool",
            "ExecuteCodeTool", "ExternalDirectoryTool", "GlobTool", "GrepTool", "ImageUnderstandingTool",
            "InvalidTool", "ListFilesTool", "LspTool", "McpCallTool", "PlanExitTool", "ProjectOverviewTool",
            "ProposeProjectConfigTool", "QuestionTool", "ReadFileTool", "ReadToolOutputTool", "RepoMapTool",
            "RetrieveContextTool", "RunCommandTool", "RunTestsTool", "SkillTool", "StartPreviewTool",
            "StopPreviewTool", "TaskTool", "TodoWriteTool", "WebFetchTool", "WebSearchTool", "WriteFileTool");

    @Test
    void modelVisibleBuiltInToolDescriptionsAndSchemaDescriptionsAreEnglish() throws Exception {
        List<AgentTool> staticTools = new ArrayList<>();
        for (String className : STATIC_TOOL_CLASSES) {
            staticTools.add(instantiate(className));
        }
        ToolRegistry registry = new ToolRegistry(staticTools);

        assertThat(registry.definitions()).isNotEmpty();
        for (ToolDefinition definition : registry.definitions()) {
            assertEnglish("tool " + definition.getName(), definition.getDescription());
            assertSchemaDescriptionsAreEnglish("tool " + definition.getName(), definition.getInputSchema());
        }
    }

    private void assertSchemaDescriptionsAreEnglish(String owner, Object value) {
        if (value instanceof Map<?, ?> map) {
            Object description = map.get("description");
            if (description != null) {
                assertEnglish(owner + " schema", String.valueOf(description));
            }
            for (Object nested : map.values()) {
                assertSchemaDescriptionsAreEnglish(owner, nested);
            }
            return;
        }
        if (value instanceof Iterable<?> iterable) {
            for (Object nested : iterable) {
                assertSchemaDescriptionsAreEnglish(owner, nested);
            }
        }
    }

    private void assertEnglish(String owner, String text) {
        assertThat(text).as(owner + " description").doesNotContainPattern(CJK_CHARACTER);
    }

    private AgentTool instantiate(String className) throws Exception {
        Class<?> type = Class.forName(TOOL_PACKAGE + className);
        Constructor<?> constructor = java.util.Arrays.stream(type.getDeclaredConstructors())
                .min(Comparator.comparingInt(Constructor::getParameterCount))
                .orElseThrow();
        constructor.setAccessible(true);
        Object[] arguments = java.util.Arrays.stream(constructor.getParameterTypes())
                .map(this::defaultValue)
                .toArray();
        return (AgentTool) constructor.newInstance(arguments);
    }

    private Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) return null;
        if (type == boolean.class) return false;
        if (type == char.class) return '\0';
        if (type == byte.class) return (byte) 0;
        if (type == short.class) return (short) 0;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == float.class) return 0F;
        if (type == double.class) return 0D;
        throw new IllegalArgumentException("Unsupported primitive type: " + type);
    }
}
