package com.labex.labexagent.runtime;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Replaces older in-run messages with a bounded, deterministic continuation checkpoint.
 *
 * <p>The compactor deliberately does not call an LLM: it is used after a provider has
 * already rejected the request for exceeding its context window. It preserves the current
 * task and runtime state explicitly, then extracts short, deduplicated facts from older
 * tool results and prior checkpoints.</p>
 */
final class ConversationCheckpointCompactor {
    static final int DEFAULT_KEEP_RECENT_TURNS = 3;

    private static final int MAX_CHECKPOINT_CHARS = 6_500;
    private static final int MAX_TASK_CHARS = 900;
    private static final int MAX_ITEM_CHARS = 260;
    private static final int MAX_PATHS = 10;
    private static final int MAX_FINDINGS = 8;
    private static final int MAX_EVIDENCE = 6;
    private static final int MAX_ISSUES = 6;
    private static final int MAX_ACTIONS = 8;
    private static final Pattern TOOL_RESULT = Pattern.compile(
            "^\\[Tool\\s+(.+?)\\s+result]", Pattern.CASE_INSENSITIVE);
    private static final Pattern FILE_PATH = Pattern.compile(
            "(?:(?:[A-Za-z]:)?[A-Za-z0-9_.@-]+(?:[\\\\/][A-Za-z0-9_.@-]+)+\\.[A-Za-z0-9]{1,10})");
    private static final Pattern API_KEY = Pattern.compile("(?i)\\bsk-[a-z0-9_-]{10,}\\b");
    private static final Pattern BEARER = Pattern.compile("(?i)\\bbearer\\s+[a-z0-9._~-]{10,}");
    private static final Pattern NAMED_SECRET = Pattern.compile(
            "(?i)\\b(api[_ -]?key|authorization|token|password|secret)\\s*[:=]\\s*([^\\s,;\\]}]+)");

    boolean compact(List<Map<String, Object>> messages, String taskRequest, AgentContext context) {
        return compactWithResult(messages, taskRequest, context).changed();
    }
    /** 只对已选定的 historical head 生成摘要，不再自行猜测 tail 边界。 */
    String checkpointForHistory(List<Map<String, Object>> historical,
                                String taskRequest,
                                AgentContext context) {
        List<Map<String, Object>> source = historical == null ? List.of() : historical;
        if (source.isEmpty()) {
            return "";
        }
        return buildCheckpoint(collectFacts(source, taskRequest, context));
    }

    Result compactWithResult(List<Map<String, Object>> messages, String taskRequest, AgentContext context) {
        if (messages == null || messages.size() <= DEFAULT_KEEP_RECENT_TURNS * 2) {
            return Result.unchanged();
        }
        int protectedCount = Math.min(messages.size(), DEFAULT_KEEP_RECENT_TURNS * 2);
        int protectFrom = messages.size() - protectedCount;
        if (protectFrom <= 0) {
            return Result.unchanged();
        }
        List<Map<String, Object>> historical = new ArrayList<>(messages.subList(0, protectFrom));
        CheckpointFacts facts = collectFacts(historical, taskRequest, context);
        return replaceHistoricalWithCheckpoint(messages, buildCheckpoint(facts), protectedCount);
    }

    boolean hasCompactableHistory(List<Map<String, Object>> messages, int keepRecentTurns) {
        if (messages == null || messages.isEmpty()) {
            return false;
        }
        int protectedCount = Math.min(messages.size(), Math.max(1, keepRecentTurns) * 2);
        return messages.size() > protectedCount;
    }

    Result compactWithCheckpoint(List<Map<String, Object>> messages, String checkpoint, int keepRecentTurns) {
        if (messages == null || checkpoint == null || checkpoint.isBlank()) {
            return Result.unchanged();
        }
        int protectedCount = Math.min(messages.size(), Math.max(1, keepRecentTurns) * 2);
        return replaceHistoricalWithCheckpoint(messages, checkpoint, protectedCount);
    }

    private Result replaceHistoricalWithCheckpoint(List<Map<String, Object>> messages, String checkpoint,
                                                    int protectedCount) {
        int protectFrom = messages.size() - protectedCount;
        if (protectFrom <= 0) {
            return Result.unchanged();
        }
        String safeCheckpoint = redact(checkpoint);
        List<Map<String, Object>> historical = new ArrayList<>(messages.subList(0, protectFrom));
        int historicalChars = characterCount(historical);
        if (safeCheckpoint.length() >= historicalChars) {
            return Result.unchanged();
        }
        List<Map<String, Object>> compacted = new ArrayList<>(protectedCount + 1);
        compacted.add(Map.of("role", "user", "content", safeCheckpoint));
        compacted.addAll(messages.subList(protectFrom, messages.size()));
        messages.clear();
        messages.addAll(compacted);
        return new Result(true, safeCheckpoint);
    }

    private CheckpointFacts collectFacts(List<Map<String, Object>> historical,
                                         String taskRequest,
                                         AgentContext context) {
        CheckpointFacts facts = new CheckpointFacts();
        facts.task = compactText(taskRequest, MAX_TASK_CHARS);
        addRuntimeState(facts, context);

        for (Map<String, Object> message : historical) {
            String content = contentOf(message);
            if (content.isBlank()) {
                continue;
            }
            if (content.contains("<conversation-checkpoint")) {
                collectPriorCheckpoint(content, facts);
            }
            collectPaths(content, facts);
            Matcher toolResult = TOOL_RESULT.matcher(content);
            if (toolResult.find()) {
                collectToolResult(toolResult.group(1), content, facts);
            } else {
                collectGenericFinding(content, facts);
            }
        }
        return facts;
    }

    private void addRuntimeState(CheckpointFacts facts, AgentContext context) {
        if (context == null) {
            return;
        }
        facts.executionState.add("Stage: " + compactText(context.getStage(), MAX_ITEM_CHARS));
        if (!context.getPlanSummary().isBlank()) {
            for (String line : context.getPlanSummary().split("\\R")) {
                String normalized = compactText(line, MAX_ITEM_CHARS);
                if (!normalized.isBlank() && !normalized.startsWith("当前执行计划")) {
                    facts.plan.add(normalized);
                }
            }
        }
        if (context.getWriteCount() > 0) {
            facts.executionState.add("Successful write operations in this run: " + context.getWriteCount());
        }
        if (context.getVerificationCount() > 0) {
            facts.executionState.add("Verification operations completed: " + context.getVerificationCount());
        }
        if (context.hasUnverifiedChanges()) {
            facts.issues.add("Changed files still need verification: "
                    + compactText(String.join(", ", context.getUnverifiedChangeTargets()), MAX_ITEM_CHARS));
        }
    }

    private void collectPriorCheckpoint(String content, CheckpointFacts facts) {
        String section = "";
        for (String rawLine : content.split("\\R")) {
            String line = rawLine.trim();
            if (line.startsWith("## ")) {
                section = line.substring(3).toLowerCase(Locale.ROOT);
                continue;
            }
            if (!line.startsWith("- ")) {
                continue;
            }
            String bullet = compactText(line.substring(2), MAX_ITEM_CHARS);
            if (bullet.isBlank()) {
                continue;
            }
            if (section.contains("verification")) {
                facts.evidence.add(bullet);
            } else if (section.contains("failure") || section.contains("unresolved") || section.contains("issue")) {
                facts.issues.add(bullet);
            } else if (section.contains("file")) {
                facts.files.add(bullet);
            } else if (section.contains("action")) {
                facts.actions.add(bullet);
            } else {
                facts.findings.add(bullet);
            }
        }
    }

    private void collectToolResult(String toolName, String content, CheckpointFacts facts) {
        String normalizedTool = compactText(toolName, 80);
        List<String> usefulLines = usefulLines(content);
        if (!normalizedTool.isBlank()) {
            String actionDetail = usefulLines.isEmpty() ? "result retained in checkpoint" : usefulLines.get(0);
            facts.actions.add(normalizedTool + ": " + compactText(actionDetail, MAX_ITEM_CHARS));
        }
        for (String line : usefulLines) {
            if (isIssue(line)) {
                facts.issues.add(compactText(line, MAX_ITEM_CHARS));
            } else if (isEvidence(line)) {
                facts.evidence.add(compactText(line, MAX_ITEM_CHARS));
            } else {
                facts.findings.add(compactText(line, MAX_ITEM_CHARS));
            }
        }
    }

    private void collectGenericFinding(String content, CheckpointFacts facts) {
        if (content.startsWith("<") || content.startsWith("[Tool ")) {
            return;
        }
        for (String line : usefulLines(content)) {
            if (isIssue(line)) {
                facts.issues.add(compactText(line, MAX_ITEM_CHARS));
            } else if (isEvidence(line)) {
                facts.evidence.add(compactText(line, MAX_ITEM_CHARS));
            }
        }
    }

    private List<String> usefulLines(String content) {
        List<String> useful = new ArrayList<>();
        for (String rawLine : content.split("\\R")) {
            String line = compactText(rawLine, MAX_ITEM_CHARS);
            if (line.isBlank() || line.startsWith("[Tool ") || line.startsWith("[tool_result_pruned")
                    || line.startsWith("Current plan progress:") || line.startsWith("Current engineering stage:")
                    || line.startsWith("Call tools to continue.")) {
                continue;
            }
            if (isIssue(line) || isEvidence(line) || containsPath(line) || containsActionSignal(line)) {
                useful.add(line);
            }
            if (useful.size() >= 4) {
                break;
            }
        }
        return useful;
    }

    private void collectPaths(String content, CheckpointFacts facts) {
        Matcher matcher = FILE_PATH.matcher(content);
        while (matcher.find()) {
            facts.files.add(compactText(matcher.group(), MAX_ITEM_CHARS));
        }
    }

    private boolean containsPath(String line) {
        return FILE_PATH.matcher(line).find();
    }

    private boolean containsActionSignal(String line) {
        String lower = line.toLowerCase(Locale.ROOT);
        return lower.contains("modified") || lower.contains("updated") || lower.contains("created")
                || lower.contains("wrote") || lower.contains("applied") || lower.contains("changed")
                || lower.contains("删除") || lower.contains("修改") || lower.contains("新增") || lower.contains("写入");
    }

    private boolean isEvidence(String line) {
        String lower = line.toLowerCase(Locale.ROOT);
        return lower.contains("build success") || lower.contains("tests run") || lower.contains("test passed")
                || lower.contains("verified") || lower.contains("verification") || lower.contains("passed")
                || lower.contains("success") || lower.contains("通过") || lower.contains("验证成功");
    }

    private boolean isIssue(String line) {
        String lower = line.toLowerCase(Locale.ROOT);
        return lower.contains("build failure") || lower.contains("failure") || lower.contains("failed")
                || lower.contains("error") || lower.contains("exception") || lower.contains("unresolved")
                || lower.contains("blocked") || lower.contains("cannot") || lower.contains("not found")
                || lower.contains("失败") || lower.contains("错误") || lower.contains("异常") || lower.contains("未解决");
    }

    private String buildCheckpoint(CheckpointFacts facts) {
        StringBuilder checkpoint = new StringBuilder();
        checkpoint.append("<conversation-checkpoint version=\"2\">\n");
        appendSection(checkpoint, "Task and acceptance", List.of(facts.task));
        appendSection(checkpoint, "Execution state", facts.executionState);
        appendSection(checkpoint, "Plan progress", facts.plan);
        appendSection(checkpoint, "Files and code references", facts.files.values());
        appendSection(checkpoint, "Verification evidence", facts.evidence.values());
        appendSection(checkpoint, "Failures and unresolved work", facts.issues.values());
        appendSection(checkpoint, "Actions and findings", merge(facts.actions.values(), facts.findings.values()));
        checkpoint.append("## Retrieval guidance\n")
                .append("- Re-read the referenced file or rerun the relevant tool when exact output is required.\n")
                .append("- Treat successful verification as evidence unless current files contradict it.\n")
                .append("</conversation-checkpoint>");
        return limitCheckpoint(checkpoint.toString());
    }

    private void appendSection(StringBuilder checkpoint, String title, Iterable<String> items) {
        List<String> nonBlank = new ArrayList<>();
        for (String item : items) {
            String normalized = compactText(item, MAX_ITEM_CHARS);
            if (!normalized.isBlank()) {
                nonBlank.add(normalized);
            }
        }
        if (nonBlank.isEmpty()) {
            return;
        }
        checkpoint.append("## ").append(title).append('\n');
        for (String item : nonBlank) {
            checkpoint.append("- ").append(item).append('\n');
        }
    }

    private List<String> merge(Iterable<String> first, Iterable<String> second) {
        LinkedHashSet<String> merged = new LinkedHashSet<>();
        first.forEach(merged::add);
        second.forEach(merged::add);
        return new ArrayList<>(merged);
    }

    private String limitCheckpoint(String checkpoint) {
        if (checkpoint.length() <= MAX_CHECKPOINT_CHARS) {
            return checkpoint;
        }
        String closingTag = "</conversation-checkpoint>";
        int contentLimit = MAX_CHECKPOINT_CHARS - closingTag.length() - 90;
        return checkpoint.substring(0, Math.max(0, contentLimit)).trim()
                + "\n- Additional historical details were omitted to fit the context budget.\n"
                + closingTag;
    }

    private String compactText(String text, int maxChars) {
        if (text == null || text.isBlank()) {
            return "";
        }
        String normalized = redact(text).replace('\r', ' ').replace('\n', ' ').replaceAll("\\s+", " ").trim();
        if (normalized.length() <= maxChars) {
            return normalized;
        }
        return normalized.substring(0, Math.max(1, maxChars - 3)).trim() + "...";
    }

    private String redact(String text) {
        String value = text == null ? "" : text;
        value = API_KEY.matcher(value).replaceAll("[REDACTED]");
        value = BEARER.matcher(value).replaceAll("Bearer [REDACTED]");
        return NAMED_SECRET.matcher(value).replaceAll("$1=[REDACTED]");
    }

    private String contentOf(Map<String, Object> message) {
        if (message == null) {
            return "";
        }
        Object content = message.get("content");
        return content instanceof String value ? value : "";
    }

    private int characterCount(List<Map<String, Object>> messages) {
        int total = 0;
        for (Map<String, Object> message : messages) {
            total += contentOf(message).length();
        }
        return total;
    }

    private static final class CheckpointFacts {
        private String task = "";
        private final List<String> executionState = new ArrayList<>();
        private final List<String> plan = new ArrayList<>();
        private final BoundedSet files = new BoundedSet(MAX_PATHS);
        private final BoundedSet findings = new BoundedSet(MAX_FINDINGS);
        private final BoundedSet evidence = new BoundedSet(MAX_EVIDENCE);
        private final BoundedSet issues = new BoundedSet(MAX_ISSUES);
        private final BoundedSet actions = new BoundedSet(MAX_ACTIONS);
    }

    private static final class BoundedSet {
        private final int limit;
        private final Set<String> values = new LinkedHashSet<>();

        private BoundedSet(int limit) {
            this.limit = limit;
        }

        private void add(String value) {
            if (value != null && !value.isBlank() && values.size() < limit) {
                values.add(value);
            }
        }

        private Set<String> values() {
            return values;
        }
    }


    record Result(boolean changed, String checkpoint) {
        static Result unchanged() {
            return new Result(false, "");
        }
    }
}
