package com.labex.labexagent.tool;

import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 生成 OpenAI-compatible tools 字段的确定性投影。
 *
 * <p>工具注册顺序与嵌套 Map 的遍历顺序不应影响 Provider 请求或 prompt cache 路由键。
 * 此处只规范化 JSON 对象键和工具列表顺序；JSON 数组仍保持原有顺序，避免改动 schema 语义。</p>
 */
public final class ToolSchemaCanonicalizer {
    private ToolSchemaCanonicalizer() {
    }

    /** 按工具名排序并递归规范化 schema，供 Provider body 与缓存路由键共用。 */
    public static List<Map<String, Object>> openAiTools(Collection<ToolDefinition> definitions) {
        if (definitions == null || definitions.isEmpty()) {
            return List.of();
        }
        return definitions.stream()
                .filter(definition -> definition != null)
                .sorted(Comparator.comparing(definition -> normalizeName(definition.getName())))
                .map(ToolSchemaCanonicalizer::openAiTool)
                .toList();
    }

    private static Map<String, Object> openAiTool(ToolDefinition definition) {
        LinkedHashMap<String, Object> tool = new LinkedHashMap<>();
        tool.put("type", "function");
        LinkedHashMap<String, Object> function = new LinkedHashMap<>();
        function.put("name", definition.getName());
        function.put("description", definition.getDescription());
        function.put("parameters", canonicalJsonValue(definition.getInputSchema()));
        tool.put("function", function);
        return tool;
    }

    private static Object canonicalJsonValue(Object value) {
        if (value instanceof Map<?, ?> source) {
            List<Map.Entry<?, ?>> entries = new ArrayList<>(source.entrySet());
            entries.sort(Comparator.comparing(entry -> String.valueOf(entry.getKey())));
            LinkedHashMap<String, Object> canonical = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : entries) {
                canonical.put(String.valueOf(entry.getKey()), canonicalJsonValue(entry.getValue()));
            }
            return canonical;
        }
        if (value instanceof Collection<?> values) {
            List<Object> canonical = new ArrayList<>();
            for (Object item : values) {
                canonical.add(canonicalJsonValue(item));
            }
            return canonical;
        }
        if (value != null && value.getClass().isArray()) {
            List<Object> canonical = new ArrayList<>();
            int length = Array.getLength(value);
            for (int index = 0; index < length; index++) {
                canonical.add(canonicalJsonValue(Array.get(value, index)));
            }
            return canonical;
        }
        return value;
    }

    private static String normalizeName(String name) {
        return name == null ? "" : name;
    }
}
