package com.labex.labexagent.service;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.labex.entity.StudentProject;
import com.labex.labexagent.runtime.AgentContext;
import com.labex.labexagent.tool.ToolResult;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

@Service
public class AgentMetricsService {
    private static final Gson GSON = new Gson();

    public void recordTool(AgentContext context,
                           String toolName,
                           JsonObject args,
                           ToolResult result,
                           long durationMs,
                           AgentPostEditHookService.HookReport hookReport) {
        if (context == null || context.getProject() == null) return;
        try {
            Map<String, Object> event = new LinkedHashMap<>();
            event.put("time", LocalDateTime.now().toString());
            event.put("event", "tool_call");
            event.put("sessionId", context.getSessionId());
            event.put("conversationId", context.getConversationId());
            event.put("taskId", context.getTaskId());
            event.put("stage", context.getStage());
            event.put("tool", toolName == null ? "" : toolName);
            event.put("success", result != null && result.isSuccess());
            event.put("durationMs", durationMs);
            event.put("approvalRequired", result != null && result.isApprovalRequired());
            event.put("writeCount", context.getWriteCount());
            event.put("verificationCount", context.getVerificationCount());
            event.put("unverifiedChanges", context.hasUnverifiedChanges());
            if (hookReport != null) {
                event.put("changedFiles", hookReport.changedFiles());
                event.put("diagnosticErrors", hookReport.errorCount());
                event.put("diagnosticWarnings", hookReport.warningCount());
                event.put("suggestedCommands", hookReport.suggestedCommands());
            }
            if (args != null) {
                event.put("target", firstTarget(args));
            }
            append(context.getProject(), event);
        } catch (Exception ignored) {
            // Metrics must never break the agent loop.
        }
    }

    public void recordLoopGuard(AgentContext context, String signature, int iteration) {
        if (context == null || context.getProject() == null) return;
        try {
            Map<String, Object> event = new LinkedHashMap<>();
            event.put("time", LocalDateTime.now().toString());
            event.put("event", "loop_guard");
            event.put("sessionId", context.getSessionId());
            event.put("conversationId", context.getConversationId());
            event.put("taskId", context.getTaskId());
            event.put("stage", context.getStage());
            event.put("iteration", iteration);
            event.put("signature", signature);
            append(context.getProject(), event);
        } catch (Exception ignored) {
            // Metrics must never break the agent loop.
        }
    }

    private void append(StudentProject project, Map<String, Object> event) throws Exception {
        Path dir = Path.of(project.getWorkspacePath()).toAbsolutePath().normalize().resolve(".labex");
        Files.createDirectories(dir);
        Path file = dir.resolve("agent-metrics.jsonl");
        Files.writeString(file, GSON.toJson(event) + "\n", StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    private String firstTarget(JsonObject args) {
        for (String key : new String[]{"file_path", "path", "command", "cmd", "query", "pattern", "symbol"}) {
            if (args.has(key) && !args.get(key).isJsonNull()) {
                String value = args.get(key).getAsString();
                if (value != null && !value.isBlank()) return value;
            }
        }
        return "";
    }
}
