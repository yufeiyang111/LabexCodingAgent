package com.labex.labexagent.service;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.labex.entity.StudentProject;
import com.labex.labexagent.runtime.AgentSsePublisher;
import com.labex.labexagent.workspace.ProjectWorkspace;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 负责大模型组装后上下文的持久化归档与前端观测分发。
 * <p>
 * 核心能力：
 * 1. 在工作区 `.labex/context-history/` 目录下为每次发送给大模型的上下文保存一份格式化的 JSON 完整快照及易读的 Markdown 概览；
 * 2. 通过 SSE 事件（MODEL_CONTEXT_SNAPSHOT）将组装好的上下文推送至前端，供浏览器开发者控制台（Console）观测与调试。
 * </p>
 */
@Service
public class AgentContextSnapshotService {

    private static final Logger log = LoggerFactory.getLogger(AgentContextSnapshotService.class);
    private static final Gson PRETTY_GSON = new GsonBuilder()
            .setPrettyPrinting()
            .disableHtmlEscaping()
            .serializeNulls()
            .create();
    private static final DateTimeFormatter ISO_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS");

    public static final String CONTEXT_HISTORY_DIR = ".labex/context-history";

    /**
     * 将组装后的上下文记录到工作区 .labex/context-history/ 目录下
     *
     * @return 生成的相对 JSON 文件路径（如 ".labex/context-history/task-10-iter-1.json"），失败返回 null
     */
    public String recordSnapshot(StudentProject project,
                                 Long taskId,
                                 int iteration,
                                 String conversationId,
                                 String provider,
                                 String model,
                                 String systemPrompt,
                                 List<Map<String, Object>> messages,
                                 List<Map<String, Object>> tools,
                                 int estimatedTokens) {
        if (project == null || project.getWorkspacePath() == null || project.getWorkspacePath().isBlank()) {
            return null;
        }
        String timestamp = LocalDateTime.now().format(ISO_FORMATTER);
        String taskIdentifier = taskId != null ? String.valueOf(taskId) : "standalone";
        String fileBaseName = String.format("task-%s-iter-%d", taskIdentifier, iteration);
        String relativeJsonPath = CONTEXT_HISTORY_DIR + "/" + fileBaseName + ".json";
        String relativeMdPath = CONTEXT_HISTORY_DIR + "/" + fileBaseName + ".md";

        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("timestamp", timestamp);
        snapshot.put("taskId", taskId);
        snapshot.put("iteration", iteration);
        snapshot.put("conversationId", conversationId);
        snapshot.put("provider", provider);
        snapshot.put("model", model);
        snapshot.put("estimatedTokens", estimatedTokens);
        snapshot.put("messageCount", messages != null ? messages.size() : 0);
        snapshot.put("toolCount", tools != null ? tools.size() : 0);
        snapshot.put("systemPrompt", systemPrompt != null ? systemPrompt : "");
        snapshot.put("messages", messages != null ? messages : List.of());
        snapshot.put("tools", tools != null ? tools : List.of());

        try {
            Path jsonFile = ProjectWorkspace.paths(project).resolveForCreate(relativeJsonPath);
            Files.createDirectories(jsonFile.getParent());
            Files.writeString(jsonFile, PRETTY_GSON.toJson(snapshot), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);

            // 同时输出一份人类易读的 Markdown 概览文件
            Path mdFile = ProjectWorkspace.paths(project).resolveForCreate(relativeMdPath);
            Files.writeString(mdFile, renderMarkdown(snapshot, systemPrompt, messages, tools), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);

            log.info("AGENT_CONTEXT_SNAPSHOT_RECORDED taskId={} iteration={} jsonPath={}",
                    taskId, iteration, relativeJsonPath);
            return relativeJsonPath;
        } catch (Exception e) {
            log.warn("Failed to write agent context snapshot for task {} iter {}: {}", taskId, iteration, e.getMessage());
            return null;
        }
    }

    /**
     * 通过 SSE 发送组装后上下文快照事件给前端
     */
    public void publishSnapshotToSse(AgentSsePublisher sse,
                                     Long taskId,
                                     int iteration,
                                     String conversationId,
                                     String provider,
                                     String model,
                                     String systemPrompt,
                                     List<Map<String, Object>> messages,
                                     List<Map<String, Object>> tools,
                                     int estimatedTokens,
                                     String filePath) {
        if (sse == null) {
            return;
        }
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("timestamp", LocalDateTime.now().format(ISO_FORMATTER));
            payload.put("taskId", taskId);
            payload.put("iteration", iteration);
            payload.put("conversationId", conversationId);
            payload.put("provider", provider);
            payload.put("model", model);
            payload.put("estimatedTokens", estimatedTokens);
            payload.put("messageCount", messages != null ? messages.size() : 0);
            payload.put("toolCount", tools != null ? tools.size() : 0);
            payload.put("filePath", filePath);
            payload.put("systemPrompt", systemPrompt != null ? systemPrompt : "");
            payload.put("messages", messages != null ? messages : List.of());
            payload.put("tools", tools != null ? tools : List.of());

            sse.sendTransient("MODEL_CONTEXT_SNAPSHOT", payload);
        } catch (Exception e) {
            log.debug("Unable to publish MODEL_CONTEXT_SNAPSHOT event: {}", e.getMessage());
        }
    }

    /**
     * 统一执行快照归档与前端事件发布
     */
    public void captureAndPublish(StudentProject project,
                                   Long taskId,
                                   int iteration,
                                   String conversationId,
                                   String provider,
                                   String model,
                                   String systemPrompt,
                                   List<Map<String, Object>> messages,
                                   List<Map<String, Object>> tools,
                                   int estimatedTokens,
                                   AgentSsePublisher sse) {
        String filePath = recordSnapshot(project, taskId, iteration, conversationId, provider, model,
                systemPrompt, messages, tools, estimatedTokens);
        publishSnapshotToSse(sse, taskId, iteration, conversationId, provider, model,
                systemPrompt, messages, tools, estimatedTokens, filePath);
    }

    private String renderMarkdown(Map<String, Object> meta,
                                  String systemPrompt,
                                  List<Map<String, Object>> messages,
                                  List<Map<String, Object>> tools) {
        StringBuilder sb = new StringBuilder();
        sb.append("# 🤖 LLM Assembled Context Snapshot (大模型组装后请求上下文)\n\n");
        sb.append("- **Task ID**: `").append(meta.get("taskId")).append("`\n");
        sb.append("- **Iteration (轮次)**: `").append(meta.get("iteration")).append("`\n");
        sb.append("- **Timestamp (时间)**: `").append(meta.get("timestamp")).append("`\n");
        sb.append("- **Model (模型)**: `").append(meta.get("model")).append("` (`").append(meta.get("provider")).append("`)\n");
        sb.append("- **Estimated Tokens (预估Tokens)**: `").append(meta.get("estimatedTokens")).append("`\n");
        sb.append("- **Messages Count (消息条数)**: `").append(meta.get("messageCount")).append("`\n");
        sb.append("- **Tools Count (工具定义数)**: `").append(meta.get("toolCount")).append("`\n\n");
        sb.append("---\n\n");

        sb.append("## 📝 1. System Prompt (系统提示词)\n\n```text\n");
        sb.append(systemPrompt == null || systemPrompt.isBlank() ? "(none)" : systemPrompt).append("\n```\n\n");
        sb.append("---\n\n");

        int count = messages == null ? 0 : messages.size();
        sb.append("## 💬 2. Assembled Messages (组装后消息列表 · 共 ").append(count).append(" 条)\n\n");
        if (messages != null) {
            for (int idx = 0; idx < messages.size(); idx++) {
                Map<String, Object> msg = messages.get(idx);
                String role = String.valueOf(msg.getOrDefault("role", "unknown"));
                sb.append("### Turn ").append(idx + 1).append(" · [").append(role).append("]\n\n");
                Object content = msg.get("content");
                if (content != null) {
                    sb.append("```text\n").append(content).append("\n```\n\n");
                }
                if (msg.containsKey("tool_calls")) {
                    sb.append("**tool_calls**:\n```json\n")
                      .append(PRETTY_GSON.toJson(msg.get("tool_calls")))
                      .append("\n```\n\n");
                }
                if (msg.containsKey("tool_call_id")) {
                    sb.append("- tool_call_id: `").append(msg.get("tool_call_id")).append("`\n");
                }
                if (msg.containsKey("name")) {
                    sb.append("- tool_name: `").append(msg.get("name")).append("`\n");
                }
                sb.append("\n");
            }
        }
        sb.append("---\n\n");

        sb.append("## 🛠️ 3. Tools Definition (注入大模型的工具定义 · 共 ")
          .append(tools == null ? 0 : tools.size()).append(" 项)\n\n");
        if (tools != null && !tools.isEmpty()) {
            sb.append("```json\n").append(PRETTY_GSON.toJson(tools)).append("\n```\n");
        } else {
            sb.append("_No tools provided for this turn._\n");
        }

        return sb.toString();
    }
}
