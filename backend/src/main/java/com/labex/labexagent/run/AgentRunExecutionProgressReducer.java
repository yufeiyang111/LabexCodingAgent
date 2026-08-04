package com.labex.labexagent.run;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * 从持久化 Tool Part 重建工程执行进度的纯 reducer。
 *
 * <p>该类不读写数据库，也不依赖 SSE。运行时增量投影和 JVM 重启后的完整重放必须复用同一套规则。</p>
 */
@Component
public final class AgentRunExecutionProgressReducer {

    private static final Set<String> NON_SETTLED_STATUSES = Set.of(
            "", "pending", "running", "streaming", "waiting_approval", "waiting_user", "skipped", "interrupted");
    private static final Set<String> FAILURE_STATUSES = Set.of("error", "failed", "environment_blocked");

    public State initial() {
        return new State("intake", 0, 0, false, Set.of(), Set.of());
    }

    public State apply(State current, String toolName, JsonObject arguments, String status, String output) {
        State state = current == null ? initial() : current;
        String normalizedStatus = normalize(status);
        if (NON_SETTLED_STATUSES.contains(normalizedStatus)) {
            return state;
        }
        if (FAILURE_STATUSES.contains(normalizedStatus)) {
            return state.withStage("repair");
        }
        if (!"completed".equals(normalizedStatus) && !"success".equals(normalizedStatus)) {
            return state;
        }

        String tool = normalize(toolName);
        if (isPlanTool(tool)) {
            return "intake".equals(state.stage()) ? state.withStage("explore") : state;
        }
        if (isReadTool(tool)) {
            if (isManualVerificationRead(state, tool, arguments, output)) {
                LinkedHashSet<String> remaining = new LinkedHashSet<>(state.unverifiedChangeTargets());
                String target = toolTarget(arguments);
                if (remaining.isEmpty()) {
                    return state.withVerification("read_file", Set.of(), false);
                }
                remaining.remove(target);
                return state.withVerification("read_file", remaining, !remaining.isEmpty());
            }
            if ("intake".equals(state.stage()) || "explore".equals(state.stage())) {
                return state.withStage("design");
            }
            return state;
        }
        if (isWriteTool(tool)) {
            LinkedHashSet<String> targets = new LinkedHashSet<>(state.unverifiedChangeTargets());
            String target = toolTarget(arguments);
            if (!target.isBlank()) {
                targets.add(target);
            }
            return new State("implement", state.writeCount() + 1, state.verificationCount(), true,
                    state.trustedVerificationSources(), targets);
        }
        if (isVerificationTool(tool)) {
            return state.withVerification(tool, Set.of(), false);
        }
        return state;
    }

    private boolean isManualVerificationRead(State state, String tool, JsonObject arguments, String output) {
        if (!state.hasUnverifiedChanges() || !"read_file".equals(tool)) {
            return false;
        }
        String target = toolTarget(arguments);
        if (!state.unverifiedChangeTargets().isEmpty()
                && (target.isBlank() || !state.unverifiedChangeTargets().contains(target))) {
            return false;
        }
        String content = output == null ? "" : output;
        return content.contains("[read_file path=") && content.contains("sha256=");
    }

    private String toolTarget(JsonObject arguments) {
        if (arguments == null) {
            return "";
        }
        for (String key : List.of("file_path", "path", "target_file", "relativePath")) {
            JsonElement value = arguments.get(key);
            if (value == null || value.isJsonNull()) {
                continue;
            }
            String text = value.isJsonPrimitive() ? value.getAsString() : value.toString();
            if (text != null && !text.isBlank()) {
                return text.trim().replace('\\', '/');
            }
        }
        return "";
    }

    private boolean isPlanTool(String tool) {
        return Set.of("create_plan", "plan", "todo_write", "todowrite").contains(tool);
    }

    private boolean isReadTool(String tool) {
        return Set.of("read_file", "grep", "glob", "list_files", "search_code", "project_overview",
                "retrieve_context").contains(tool);
    }

    private boolean isWriteTool(String tool) {
        return Set.of("write_file", "edit_file", "apply_patch", "write", "edit", "patch").contains(tool);
    }

    private boolean isVerificationTool(String tool) {
        return "run_tests".equals(tool);
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    public record State(String stage,
                        int writeCount,
                        int verificationCount,
                        boolean hasUnverifiedChanges,
                        Set<String> trustedVerificationSources,
                        Set<String> unverifiedChangeTargets) {
        public State {
            stage = stage == null || stage.isBlank() ? "intake" : stage.trim().toLowerCase(Locale.ROOT);
            writeCount = Math.max(0, writeCount);
            verificationCount = Math.max(0, verificationCount);
            trustedVerificationSources = normalizedSet(trustedVerificationSources, true);
            unverifiedChangeTargets = normalizedSet(unverifiedChangeTargets, false);
            hasUnverifiedChanges = hasUnverifiedChanges || !unverifiedChangeTargets.isEmpty();
        }

        public State withStage(String nextStage) {
            return new State(nextStage, writeCount, verificationCount, hasUnverifiedChanges,
                    trustedVerificationSources, unverifiedChangeTargets);
        }

        public State withVerification(String source, Set<String> remainingTargets, boolean unverified) {
            LinkedHashSet<String> sources = new LinkedHashSet<>(trustedVerificationSources);
            if (source != null && !source.isBlank()) {
                sources.add(source.trim().toLowerCase(Locale.ROOT));
            }
            return new State("verify", writeCount, verificationCount + 1, unverified,
                    sources, remainingTargets);
        }

        private static Set<String> normalizedSet(Set<String> values, boolean lowerCase) {
            if (values == null || values.isEmpty()) {
                return Set.of();
            }
            LinkedHashSet<String> normalized = new LinkedHashSet<>();
            for (String value : values) {
                if (value == null || value.isBlank()) {
                    continue;
                }
                String text = value.trim().replace('\\', '/');
                normalized.add(lowerCase ? text.toLowerCase(Locale.ROOT) : text);
            }
            return Collections.unmodifiableSet(normalized);
        }
    }
}