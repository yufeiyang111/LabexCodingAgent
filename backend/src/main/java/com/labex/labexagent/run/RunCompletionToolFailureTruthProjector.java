package com.labex.labexagent.run;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.labex.entity.AgentRunPart;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 从 durable Tool Part 重建当前仍未被同一实际操作成功重试消解的执行失败。
 *
 * <p>工具 transport 的 {@code completed} 不等于进程/工具的实际成功：例如 shell 的非零 exit
 * 会以 completed Part 保存模型可读输出，但 metadata 中仍保留 {@code failureClass} / execution
 * 状态。最终完成判定只能消费这里的结构化事实，不能解析模型文本或工具输出字符串。</p>
 */
public final class RunCompletionToolFailureTruthProjector {

    public List<String> unresolvedFailureLabels(List<AgentRunPart> parts) {
        Map<String, FailureFact> latestOutcomes = new LinkedHashMap<>();
        List<AgentRunPart> ordered = (parts == null ? List.<AgentRunPart>of() : parts).stream()
                .filter(part -> part != null && "tool".equalsIgnoreCase(part.getPartType()))
                .sorted(Comparator.comparingLong(this::orderingValue))
                .toList();
        for (AgentRunPart part : ordered) {
            String identity = operationIdentity(part);
            FailureFact failure = failureFact(part);
            if (failure != null) {
                latestOutcomes.put(identity, failure);
                continue;
            }
            if (isSuccessfulExecution(part)) {
                latestOutcomes.remove(identity);
            }
        }
        return latestOutcomes.values().stream().map(FailureFact::label).distinct().toList();
    }

    private long orderingValue(AgentRunPart part) {
        return part.getPartId() == null ? Long.MAX_VALUE : Math.max(0L, part.getPartId());
    }

    private FailureFact failureFact(AgentRunPart part) {
        JsonObject metadata = metadata(part);
        String failureClass = text(metadata, "failureClass");
        JsonObject execution = object(metadata, "execution");
        String executionStatus = text(execution, "status");
        Integer exitCode = integer(execution, "exitCode");
        String status = normalized(part.getStatus());

        if (!failureClass.isBlank()) {
            return new FailureFact(labelTool(part), failureClass);
        }
        if (exitCode != null && exitCode != 0) {
            return new FailureFact(labelTool(part), "non_zero_exit");
        }
        if ("timed_out".equals(executionStatus) || "cancelled".equals(executionStatus)
                || "infrastructure_error".equals(executionStatus) || "failed".equals(executionStatus)) {
            return new FailureFact(labelTool(part), executionStatus);
        }
        if ("failed".equals(status)) {
            return new FailureFact(labelTool(part), "tool_error");
        }
        return null;
    }

    private boolean isSuccessfulExecution(AgentRunPart part) {
        if (!"completed".equals(normalized(part.getStatus()))) {
            return false;
        }
        JsonObject metadata = metadata(part);
        if (!text(metadata, "failureClass").isBlank()) {
            return false;
        }
        JsonObject execution = object(metadata, "execution");
        String executionStatus = text(execution, "status");
        Integer exitCode = integer(execution, "exitCode");
        if (exitCode != null && exitCode != 0) {
            return false;
        }
        return executionStatus.isBlank() || "succeeded".equals(executionStatus);
    }

    private static final Set<String> FILE_MUTATION_TOOLS = Set.of(
            "write_file", "write", "edit_file", "edit", "apply_patch", "patch");

    private String operationIdentity(AgentRunPart part) {
        String tool = normalized(labelTool(part));
        String target = targetPath(part);
        if (FILE_MUTATION_TOOLS.contains(tool) && !target.isBlank()) {
            return "file:" + target;
        }
        if (!target.isBlank()) {
            return tool + ":" + target;
        }
        return tool;
    }

    private String targetPath(AgentRunPart part) {
        if (part == null || part.getInputJson() == null || part.getInputJson().isBlank()) {
            return "";
        }
        try {
            JsonElement el = JsonParser.parseString(part.getInputJson());
            if (el.isJsonObject()) {
                JsonObject obj = el.getAsJsonObject();
                for (String key : List.of("file_path", "path", "filePath", "file", "filename", "command", "cmd")) {
                    if (obj.has(key) && !obj.get(key).isJsonNull()) {
                        String raw = obj.get(key).getAsString().trim();
                        if (!raw.isBlank()) {
                            return raw.replace('\\', '/').replaceAll("^(/|\\./)+", "").toLowerCase(Locale.ROOT);
                        }
                    }
                }
            }
        } catch (RuntimeException ignored) {
        }
        return "";
    }

    private String canonicalInput(String raw) {
        if (raw == null || raw.isBlank()) {
            return "{}";
        }
        try {
            return canonical(JsonParser.parseString(raw));
        } catch (RuntimeException ignored) {
            return raw.trim();
        }
    }

    private String canonical(JsonElement value) {
        if (value == null || value.isJsonNull()) {
            return "null";
        }
        if (value.isJsonObject()) {
            JsonObject object = value.getAsJsonObject();
            List<String> keys = new ArrayList<>(object.keySet());
            keys.sort(String::compareTo);
            StringBuilder builder = new StringBuilder("{");
            for (String key : keys) {
                if (builder.length() > 1) {
                    builder.append(',');
                }
                builder.append(key).append(':').append(canonical(object.get(key)));
            }
            return builder.append('}').toString();
        }
        if (value.isJsonArray()) {
            StringBuilder builder = new StringBuilder("[");
            for (JsonElement element : value.getAsJsonArray()) {
                if (builder.length() > 1) {
                    builder.append(',');
                }
                builder.append(canonical(element));
            }
            return builder.append(']').toString();
        }
        return value.toString();
    }

    private JsonObject metadata(AgentRunPart part) {
        if (part == null || part.getMetadata() == null || part.getMetadata().isBlank()) {
            return new JsonObject();
        }
        try {
            JsonElement value = JsonParser.parseString(part.getMetadata());
            return value.isJsonObject() ? value.getAsJsonObject() : new JsonObject();
        } catch (RuntimeException ignored) {
            return new JsonObject();
        }
    }

    private JsonObject object(JsonObject source, String key) {
        if (source == null || key == null || !source.has(key) || !source.get(key).isJsonObject()) {
            return new JsonObject();
        }
        return source.getAsJsonObject(key);
    }

    private String text(JsonObject source, String key) {
        if (source == null || key == null || !source.has(key) || source.get(key).isJsonNull()) {
            return "";
        }
        try {
            return normalized(source.get(key).getAsString());
        } catch (RuntimeException ignored) {
            return "";
        }
    }

    private Integer integer(JsonObject source, String key) {
        if (source == null || key == null || !source.has(key) || source.get(key).isJsonNull()) {
            return null;
        }
        try {
            return source.get(key).getAsInt();
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private String labelTool(AgentRunPart part) {
        String tool = part == null ? "" : part.getToolName();
        return tool == null || tool.isBlank() ? "tool" : tool.trim();
    }

    private String normalized(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest((value == null ? "" : value).getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte current : digest) {
                hex.append(String.format("%02x", current));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 is unavailable", error);
        }
    }

    private record FailureFact(String toolName, String failureClass) {
        String label() {
            return toolName + " (" + failureClass + ")";
        }
    }
}
