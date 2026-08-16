package com.labex.labexagent.tool;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.labex.labexagent.runtime.AgentMode;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

@Component
public class ToolRegistry {
    private final Map<String, AgentTool> tools = new LinkedHashMap<>();
    private final Map<String, AgentTool> dynamicTools = new ConcurrentHashMap<>();

    /** Legacy names remain executable for durable replay but are never exposed in model schemas. */
    private static final Map<String, String> COMPATIBILITY_ALIASES = Map.ofEntries(
            Map.entry("read", "read_file"),
            Map.entry("list", "list_files"),
            Map.entry("write", "write_file"),
            Map.entry("edit", "edit_file"),
            Map.entry("patch", "apply_patch"),
            Map.entry("webfetch", "web_fetch"),
            Map.entry("websearch", "web_search"),
            Map.entry("image", "understand_image"),
            Map.entry("repo_overview", "project_overview"),
            Map.entry("todowrite", "todo_write"),
            Map.entry("todo", "todo_write"),
            Map.entry("lsp_symbols", "lsp"),
            Map.entry("diagnostics", "lsp"));

    /** Internal handlers may support recovery paths but must never become a model-selectable tool. */
    private static final Set<String> INTERNAL_TOOLS = Set.of("invalid");

    /** 每个模式允许使用的工具白名单（参考 OpenCode 的 Permission.Ruleset） */
    private static final Map<String, Set<String>> MODE_ALLOWED_TOOLS = Map.of(
        // plan 模式：只读 + plan_exit 切换
        "plan", Set.of(
            "read_file", "read_tool_output", "glob", "grep", "list_files",
            "project_overview", "repo_map", "lsp", "retrieve_context",
            "web_fetch", "web_search", "understand_image",
            "create_plan", "todo_write", "plan_exit", "question", "skill", "mcp_call", "task", "context_note"
        ),
        // explore 模式：只读
        "explore", Set.of(
            "read_file", "read_tool_output", "glob", "grep", "list_files",
            "project_overview", "repo_map", "lsp", "retrieve_context",
            "web_fetch", "web_search", "understand_image",
            "question", "context_note"
        )
        // build 模式：不限制（默认，不在此 Map 中）
    );

    /** 禁用的工具（不注入系统提示词，不注册到 LLM） */
    private static final Set<String> DISABLED_TOOLS = Set.of(
        "repo_clone",        // 已禁用，始终返回失败
        "external_directory" // 已禁用，始终返回失败
    );

    public ToolRegistry(List<AgentTool> agentTools) {
        for (AgentTool tool : agentTools) {
            if (tool == null || tool.definition() == null) {
                throw new IllegalArgumentException("registered tool and definition are required");
            }
            String name = tool.definition().getName();
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException("registered tool name is required");
            }
            if (COMPATIBILITY_ALIASES.containsKey(name)) {
                throw new IllegalStateException("compatibility alias must not be registered as a model tool: " + name);
            }
            if (DISABLED_TOOLS.contains(name) || INTERNAL_TOOLS.contains(name)) {
                continue;
            }
            AgentTool previous = this.tools.putIfAbsent(name, tool);
            if (previous != null) {
                throw new IllegalStateException("duplicate registered tool name: " + name);
            }
        }
    }

    public AgentTool get(String name) {
        AgentTool tool = dynamicTools.get(name);
        if (tool != null) return tool;
        return this.tools.get(canonicalName(name));
    }

    public String canonicalName(String name) {
        if (name == null || name.isBlank()) return "";
        String normalized = name.trim();
        if (dynamicTools.containsKey(normalized)) return normalized;
        return COMPATIBILITY_ALIASES.getOrDefault(normalized, normalized);
    }

    public boolean isCompatibilityAlias(String name) {
        return name != null && !name.isBlank()
                && !dynamicTools.containsKey(name.trim())
                && COMPATIBILITY_ALIASES.containsKey(name.trim());
    }

    /**
     * Canonicalize durable calls emitted by earlier model schemas before validating the current schema.
     * Compatibility is intentionally centralized here rather than advertised to new model turns.
     */
    public JsonObject normalizeArguments(String requestedName, JsonObject arguments) {
        JsonObject normalized = arguments == null ? new JsonObject() : arguments.deepCopy();
        String canonicalName = canonicalName(requestedName);
        switch (canonicalName) {
            case "read_file" -> {
                moveFirst(normalized, "file_path", "path", "filePath");
                moveFirst(normalized, "limit", "max_lines");
            }
            case "write_file" -> {
                moveFirst(normalized, "file_path", "path", "filePath");
                moveFirst(normalized, "content", "file_content", "fileContent");
            }
            case "edit_file" -> {
                moveFirst(normalized, "file_path", "path", "filePath");
                moveFirst(normalized, "old_string", "oldString", "old_text");
                moveFirst(normalized, "new_string", "newString", "new_text");
                moveFirst(normalized, "expected_sha256", "expectedSha256", "file_sha256", "sha256");
            }
            case "shell" -> normalizeLegacyShellArguments(normalized);
            case "start_preview" -> normalizeLegacyPreviewArguments(normalized);
            case "apply_patch" -> normalizeLegacyPatchArguments(requestedName, normalized);
            case "lsp" -> normalizeLegacyLspArguments(requestedName, normalized);
            case "task" -> normalizeLegacyTaskArguments(normalized);
            default -> {
                // No compatibility fields are defined for this canonical tool.
            }
        }
        return normalized;
    }

    /**
     * Some compatibility inputs are preserved for execution but hidden from the canonical model schema.
     * They are removed only from the validation view, never from the execution input.
     */
    public JsonObject argumentsForSchemaValidation(String requestedName, JsonObject normalizedArguments) {
        JsonObject validationArguments = normalizedArguments == null ? new JsonObject() : normalizedArguments.deepCopy();
        if ("shell".equals(canonicalName(requestedName))) {
            validationArguments.remove("network");
        }
        return validationArguments;
    }

    private static void normalizeLegacyShellArguments(JsonObject arguments) {
        moveFirst(arguments, "command", "cmd", "shell_command");
        moveFirst(arguments, "workdir", "working_directory", "workingDirectory", "cwd");
        if (!arguments.has("timeout") && arguments.has("timeout_seconds")) {
            JsonElement seconds = arguments.get("timeout_seconds");
            arguments.add("timeout", millisecondsFromSeconds(seconds));
        }
        arguments.remove("timeout_seconds");
    }

    private static JsonElement millisecondsFromSeconds(JsonElement seconds) {
        if (seconds == null || seconds.isJsonNull() || !seconds.isJsonPrimitive()
                || !seconds.getAsJsonPrimitive().isNumber()) {
            return seconds == null ? com.google.gson.JsonNull.INSTANCE : seconds.deepCopy();
        }
        try {
            long secondsValue = seconds.getAsLong();
            long milliseconds = Math.multiplyExact(secondsValue, 1_000L);
            if (milliseconds > Integer.MAX_VALUE) return new com.google.gson.JsonPrimitive(Integer.MAX_VALUE);
            if (milliseconds < Integer.MIN_VALUE) return new com.google.gson.JsonPrimitive(Integer.MIN_VALUE);
            return new com.google.gson.JsonPrimitive((int) milliseconds);
        } catch (ArithmeticException | NumberFormatException invalid) {
            return seconds.deepCopy();
        }
    }

    private static void normalizeLegacyPreviewArguments(JsonObject arguments) {
        moveFirst(arguments, "command", "cmd", "shell_command");
        moveFirst(arguments, "workdir", "working_directory", "workingDirectory", "cwd");
        moveFirst(arguments, "readiness_path", "readinessPath");
    }

    private static void normalizeLegacyPatchArguments(String requestedName, JsonObject arguments) {
        if (!"patch".equals(requestedName) || !arguments.has("changes") || !arguments.get("changes").isJsonArray()) {
            return;
        }
        JsonArray changes = arguments.getAsJsonArray("changes");
        for (JsonElement item : changes) {
            if (!item.isJsonObject()) continue;
            JsonObject change = item.getAsJsonObject();
            moveFirst(change, "old_string", "oldString", "old_text");
            moveFirst(change, "new_string", "newString", "new_text");
            if (change.has("operation")) continue;
            if (change.has("old_string") && change.has("new_string")) {
                change.addProperty("operation", "replace");
            } else if (change.has("content")) {
                change.addProperty("operation", "create");
            }
        }
    }

    private static void normalizeLegacyLspArguments(String requestedName, JsonObject arguments) {
        moveFirst(arguments, "file_path", "path", "filePath");
        moveFirst(arguments, "include_declaration", "includeDeclaration");
        if ("lsp_symbols".equals(requestedName)) {
            if (!arguments.has("action")) arguments.addProperty("action", "documentSymbol");
            moveFirst(arguments, "max_results", "max_symbols");
        } else if ("diagnostics".equals(requestedName) && !arguments.has("action")) {
            arguments.addProperty("action", "diagnostics");
        }
        if (arguments.has("include_declaration") && arguments.get("include_declaration").isJsonPrimitive()
                && arguments.get("include_declaration").getAsJsonPrimitive().isString()) {
            String value = arguments.get("include_declaration").getAsString();
            if ("true".equalsIgnoreCase(value) || "false".equalsIgnoreCase(value)) {
                arguments.addProperty("include_declaration", Boolean.parseBoolean(value));
            }
        }
    }

    private static void normalizeLegacyTaskArguments(JsonObject arguments) {
        moveFirst(arguments, "prompt", "task", "question");
        arguments.remove("task_id");
        if (!arguments.has("description") && arguments.has("prompt")
                && arguments.get("prompt").isJsonPrimitive()
                && arguments.get("prompt").getAsJsonPrimitive().isString()) {
            arguments.addProperty("description", arguments.get("prompt").getAsString());
        }
    }

    private static void moveFirst(JsonObject arguments, String target, String... legacyFields) {
        for (String legacyField : legacyFields) {
            if (legacyField.equals(target) || !arguments.has(legacyField)) continue;
            JsonElement legacyValue = arguments.get(legacyField);
            if (!arguments.has(target)) {
                arguments.add(target, legacyValue == null ? com.google.gson.JsonNull.INSTANCE : legacyValue.deepCopy());
            }
            arguments.remove(legacyField);
        }
    }

    /** Tool definitions before capability filtering. */
    public Collection<ToolDefinition> definitions() {
        List<ToolDefinition> all = new ArrayList<>();
        this.tools.values().stream().map(AgentTool::definition).forEach(all::add);
        this.dynamicTools.values().stream().map(AgentTool::definition).forEach(all::add);
        return List.copyOf(all);
    }

    /** Static tool definitions filtered by mode; dynamic MCP tools cannot bypass read-only modes. */
    public Collection<ToolDefinition> staticDefinitionsForMode(String mode) {
        if (!AgentMode.isSupported(mode)) return List.of();
        Set<String> allowed = MODE_ALLOWED_TOOLS.get(mode);
        if (allowed == null) {
            return AgentMode.isUnrestricted(mode)
                    ? this.tools.values().stream().map(AgentTool::definition).toList()
                    : List.of();
        }
        return this.tools.values().stream()
                .map(AgentTool::definition)
                .filter(definition -> allowed.contains(definition.getName()))
                .toList();
    }

    /** Dynamic MCP schemas; ToolSelectionPolicy applies mode and capability filtering. */
    public Collection<ToolDefinition> dynamicDefinitions() {
        return this.dynamicTools.values().stream().map(AgentTool::definition).toList();
    }

    /** Compatibility API: dynamic tools are available only in unrestricted modes. */
    public Collection<ToolDefinition> definitionsForMode(String mode) {
        List<ToolDefinition> result = new ArrayList<>(staticDefinitionsForMode(mode));
        if (AgentMode.isUnrestricted(mode)) {
            dynamicDefinitions().stream()
                    .sorted(Comparator.comparing(ToolDefinition::getName))
                    .forEach(result::add);
        }
        return List.copyOf(result);
    }

    /** 检查某工具在某模式下是否允许使用 */
    public boolean isToolAllowed(String mode, String toolName) {
        if (!AgentMode.isSupported(mode)) return false;
        // Dynamic tools still pass through permission evaluation; unknown modes stop here.
        if (dynamicTools.containsKey(toolName)) return true;
        String canonicalName = canonicalName(toolName);
        Set<String> allowed = MODE_ALLOWED_TOOLS.get(mode);
        if (allowed == null) return AgentMode.isUnrestricted(mode) && tools.containsKey(canonicalName);
        return allowed.contains(canonicalName);
    }

    // ===== 动态工具管理 =====

    /** 注册动态工具（MCP 工具等） */
    public void registerDynamicTool(String name, AgentTool tool) {
        dynamicTools.put(name, tool);
    }

    /** 注册多个动态工具 */
    public void registerDynamicTools(Map<String, AgentTool> tools) {
        dynamicTools.putAll(tools);
    }

    /** 移除动态工具 */
    public void removeDynamicTool(String name) {
        dynamicTools.remove(name);
    }

    /** 清除所有动态工具 */
    public void clearDynamicTools() {
        dynamicTools.clear();
    }

    /** 获取动态工具数量 */
    public int getDynamicToolCount() {
        return dynamicTools.size();
    }
}
