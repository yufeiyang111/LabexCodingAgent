package com.labex.labexagent.llm;

import com.labex.labexagent.runtime.CancellationToken;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * 仅供本地真实链路验收使用的脚本化模型。
 *
 * <p>该 Provider 只有显式启用 acceptance 且未启用 prod 时才会注册。它不访问网络、不读取密钥，
 * 也不会绕过任何出站 URL 或工具权限策略；工具仍由正式 AgentLoopEngine 调度。</p>
 */
@Component
@Profile("acceptance & !prod")
public final class AcceptanceScriptedProvider implements LlmProvider {
    private static final String PROVIDER_ID = "acceptance_scripted";
    private static final Pattern ISOLATION_MARKER = Pattern.compile("\\[acceptance:isolation:([^]\\r\\n]+)]");
    private static final Map<String, Object> USAGE = Map.of(
            "prompt_tokens", 64,
            "completion_tokens", 32,
            "total_tokens", 96);

    @Override
    public String getProviderId() {
        return PROVIDER_ID;
    }

    @Override
    public String getProviderName() {
        return "Acceptance Scripted Provider";
    }

    @Override
    public boolean supportsStreaming() {
        return true;
    }

    @Override
    public boolean supportsToolCalling() {
        return true;
    }

    @Override
    public ProviderCapabilities capabilities() {
        return new ProviderCapabilities(true, true, false, true, true);
    }

    @Override
    public Map<String, Object> chatWithTools(String sysPrompt, List<Map<String, Object>> messages,
                                              List<Map<String, Object>> tools, LlmConfig config) {
        String prompt = flatten(messages);
        if (isCompactionRequest(sysPrompt)) {
            String summary = "Acceptance compaction preserved the active task, verified state, and next runtime action.";
            String content = "{\"summary\":\"" + summary + "\","
                    + "\"facts\":[\"The scripted acceptance provider never accesses the network.\"],"
                    + "\"nextActions\":[\"Continue the active acceptance scenario from its durable checkpoint.\"],"
                    + "\"openRisks\":[],\"files\":[],"
                    + "\"verification\":[\"Compaction response passed the production structured-output parser.\"]}";
            return Map.of("type", "text", "content", content, "usage", USAGE);
        }
        return Map.of("type", "text", "content", finalReply(prompt), "usage", USAGE);
    }

    @Override
    public void chatStream(String sysPrompt, List<Map<String, Object>> messages,
                           List<Map<String, Object>> tools, LlmConfig config,
                           Consumer<StreamChunk> onChunk) {
        chatStream(sysPrompt, messages, tools, config, CancellationToken.none(), onChunk);
    }

    @Override
    public void chatStream(String sysPrompt, List<Map<String, Object>> messages,
                           List<Map<String, Object>> tools, LlmConfig config,
                           CancellationToken cancellationToken, Consumer<StreamChunk> onChunk) {
        CancellationToken token = cancellationToken == null ? CancellationToken.none() : cancellationToken;
        if (token.isCancellationRequested()) {
            onChunk.accept(new StreamChunk("cancelled", "", null, null, null, true, null,
                    null, null, null));
            return;
        }

        String prompt = flatten(messages);
        onChunk.accept(new StreamChunk("thinking_delta", "Acceptance runtime scenario selected. ",
                null, null, null, false, null, null, null, null));

        if (prompt.contains("[acceptance:tool]") && !prompt.contains("[Tool list_files result]")) {
            emitTool(onChunk, "list_files", "{\"path\":\"\"}", "acceptance-tool-list");
            return;
        }
        if (prompt.contains("[acceptance:question]") && !hasResumedInteraction(prompt, "waiting_user")) {
            emitTool(onChunk, "question",
                    "{\"question\":\"是否继续真实验收？\",\"summary\":\"等待验收选择\","
                            + "\"options\":[\"继续真实验收\",\"停止\"]}",
                    "acceptance-question");
            return;
        }
        if (prompt.contains("[acceptance:approval]") && !hasResumedInteraction(prompt, "waiting_approval")) {
            emitTool(onChunk, "shell", "{\"command\":\"git add .\",\"timeout_seconds\":10}",
                    "acceptance-command-approval");
            return;
        }

        String finalText = finalReply(prompt);
        onChunk.accept(new StreamChunk("text_delta", finalText, null, null, null, false, null,
                null, null, null));
        onChunk.accept(new StreamChunk("done", "", null, null, null, true, USAGE,
                null, null, null));
    }

    private void emitTool(Consumer<StreamChunk> onChunk, String name, String arguments, String id) {
        onChunk.accept(new StreamChunk("tool_call", "", name, arguments, null, false, USAGE,
                id, 0, null));
        onChunk.accept(new StreamChunk("done", "", null, null, null, true, USAGE,
                null, null, null));
    }

    private boolean hasResumedInteraction(String prompt, String checkpointState) {
        String lower = prompt.toLowerCase(Locale.ROOT);
        return lower.contains(checkpointState)
                || lower.contains("interaction resumed")
                || lower.contains("user answered")
                || lower.contains("approval granted")
                || lower.contains("approval rejected")
                || lower.contains("command approval decision");
    }

    private boolean isCompactionRequest(String systemPrompt) {
        String normalized = systemPrompt == null ? "" : systemPrompt.toLowerCase(Locale.ROOT);
        return normalized.contains("context compaction agent")
                || normalized.contains("nextactions") && normalized.contains("openrisks");
    }

    private String finalReply(String prompt) {
        Matcher marker = ISOLATION_MARKER.matcher(prompt);
        String isolation = marker.find() ? marker.group(1).trim() : "none";
        if (prompt.contains("[acceptance:tool]")) {
            return "## Summary\n**Completed**\n- The production Agent loop executed `list_files` and returned its result.\n"
                    + "**Verification**\n- Native tool-call identity, tool observation, token usage, and final SSE completion were preserved.\n"
                    + "**Risk**\n- This response is generated only by the non-production acceptance profile.";
        }
        if (prompt.contains("[acceptance:question]")) {
            return "## Summary\n**Completed**\n- The durable user-question interaction resumed the same task after a reply.\n"
                    + "**Verification**\n- The checkpoint state changed from waiting_user and the final SSE reply completed without creating another task.\n"
                    + "**Risk**\n- The scripted provider is available only outside the production profile.";
        }
        if (prompt.contains("[acceptance:approval]")) {
            return "## Summary\n**Completed**\n- The one-time command approval decision resumed the original task and conversation.\n"
                    + "**Verification**\n- Approval ownership stayed bound to its request and the Agent emitted a final structured result.\n"
                    + "**Risk**\n- The disposable acceptance project may contain a failed git command observation by design.";
        }
        return "## Summary\n**Completed**\n- Conversation isolation marker: `" + isolation + "`.\n"
                + "**Verification**\n- The reply was derived only from messages supplied to this provider invocation; no shared mutable session state exists.\n"
                + "**Risk**\n- This is an acceptance-only deterministic response and does not validate an external model service.";
    }

    private String flatten(List<Map<String, Object>> messages) {
        if (messages == null || messages.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        for (Map<String, Object> message : messages) {
            if (message == null) {
                continue;
            }
            Object content = message.get("content");
            if (content != null) {
                out.append(content).append('\n');
            }
        }
        return out.toString();
    }
}