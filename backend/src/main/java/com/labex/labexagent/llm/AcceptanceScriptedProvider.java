package com.labex.labexagent.llm;

import com.google.gson.JsonObject;
import com.labex.labexagent.runtime.CancellationToken;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
    private static final Logger log = LoggerFactory.getLogger(AcceptanceScriptedProvider.class);
    private static final String PROVIDER_ID = "acceptance_scripted";
    private static final Pattern ISOLATION_MARKER = Pattern.compile("\\[acceptance:isolation:([^]\\r\\n]+)]");
    private static final String COMPACTION_ROOT_PATH = "./".repeat(15_000);
    private static final String ENVIRONMENT_TEST_SCRIPT = """
            const fs = require('node:fs');
            const marker = '.acceptance-environment-recovered';
            if (!fs.existsSync(marker)) {
              fs.writeFileSync(marker, 'ready');
              console.error('Non-resolvable parent POM for acceptance fixture');
              process.exit(1);
            }
            console.log('environment restored');
            """;
    private static final String ENVIRONMENT_TEST_PACKAGE =
            "{\"name\":\"acceptance-environment\",\"scripts\":{\"test\":\"node acceptance-environment-test.cjs\"}}";
    private static final Map<String, Object> USAGE = Map.of(
            "prompt_tokens", 64,
            "completion_tokens", 32,
            "total_tokens", 96);
    private static final Map<String, Object> CACHE_HIT_USAGE = Map.of(
            "prompt_tokens", 200,
            "completion_tokens", 20,
            "total_tokens", 220,
            "cached_tokens", 50,
            "cache_write_tokens", 10,
            "cache_usage_reported", true);

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
        return chatWithTools(sysPrompt, messages, tools, config, CancellationToken.none());
    }

    @Override
    public Map<String, Object> chatWithTools(String sysPrompt, List<Map<String, Object>> messages,
                                              List<Map<String, Object>> tools, LlmConfig config,
                                              CancellationToken cancellationToken) {
        CancellationToken token = cancellationToken == null ? CancellationToken.none() : cancellationToken;
        if (token.isCancellationRequested()) {
            return cancelledResponse();
        }
        String prompt = flatten(messages);
        if (isCompactionRequest(sysPrompt)) {
            if (prompt.contains("[acceptance:compaction-cancel]")) {
                holdCompactionRequest(token);
            }
            if (token.isCancellationRequested()) {
                return cancelledResponse();
            }
            String marker = prompt.contains("[acceptance:compaction]") ? " [acceptance:compaction]" : "";
            String summary = "Acceptance compaction preserved the active task, verified state, and next runtime action." + marker;
            String content = "{\"summary\":\"" + summary + "\","
                    + "\"facts\":[\"The scripted acceptance provider never accesses the network.\"],"
                    + "\"nextActions\":[\"Continue the active acceptance scenario from its durable checkpoint.\"],"
                    + "\"openRisks\":[],\"files\":[],"
                    + "\"verification\":[\"Compaction response passed the production structured-output parser.\"]}";
            return Map.of("type", "text", "content", content, "usage", USAGE);
        }
        return Map.of("type", "text", "content", finalReply(prompt), "usage", usageFor(prompt));
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
        log.info("ACCEPTANCE_PROVIDER_STREAM model={} messageCount={} compactionMarker={} permissionBatchMarker={} interactionResolved={} listResult={}",
                config == null ? "" : config.modelName(), messages == null ? 0 : messages.size(),
                prompt.contains("[acceptance:compaction]"), prompt.contains("[acceptance:permission-batch]"),
                hasResumedInteraction(prompt, "waiting_user"), prompt.contains("[Tool list_files result]"));
        if (prompt.contains("[acceptance:reasoning-boundary]")) {
            onChunk.accept(new StreamChunk("thinking_delta", "\\",
                    null, null, null, false, null, null, null, null));
            onChunk.accept(new StreamChunk("thinking_delta", "<TH",
                    null, null, null, false, null, null, null, null));
            onChunk.accept(new StreamChunk("thinking_delta", "INK data-kind=\\\"hidden\\\"\\>Acceptance runtime scenario selected. \\</THINK",
                    null, null, null, false, null, null, null, null));
            onChunk.accept(new StreamChunk("thinking_delta", "ING\\>",
                    null, null, null, false, null, null, null, null));
        } else {
            onChunk.accept(new StreamChunk("thinking_delta", "Acceptance runtime scenario selected. ",
                    null, null, null, false, null, null, null, null));
        }

        if (prompt.contains("[acceptance:stream-break]")) {
            onChunk.accept(new StreamChunk("text_delta",
                    "Partial response before the scripted provider connection closes.",
                    null, null, null, false, null, null, null, null));
            return;
        }

        if (prompt.contains("[acceptance:text-tool-fallback]")
                && !prompt.contains("[Tool list_files result]")) {
            onChunk.accept(new StreamChunk("text_delta",
                    "<tool_call>{\"name\":\"list_files\",\"arguments\":{\"path\":\"\"}}</tool_call>",
                    null, null, null, false, null, null, null, null));
            onChunk.accept(new StreamChunk("done", "", null, null, null, true, usageFor(prompt),
                    null, null, null));
            return;
        }
        if (prompt.contains("[acceptance:native-tool-input]") && !prompt.contains("invalid_json")) {
            emitToolBatch(onChunk, List.of(
                    new ScriptedToolCall("list_files", "{\"path\":",
                            "acceptance-native-invalid"),
                    new ScriptedToolCall("list_files", "{\"path\":\"\"}",
                            "acceptance-native-valid")));
            return;
        }
        if (prompt.contains("[acceptance:tool]") && !prompt.contains("[Tool list_files result]")) {
            emitTool(onChunk, "list_files", "{\"path\":\"\"}", "acceptance-tool-list");
            return;
        }
        if (isCompactionScenario(prompt)
                && !hasResumedInteraction(prompt, "waiting_user")) {
            emitTool(onChunk, "question",
                    "{\"question\":\"是否继续长上下文压缩验收？\",\"summary\":\"等待压缩验收选择\","
                            + "\"options\":[\"继续压缩验收\",\"停止\"]}",
                    "acceptance-compaction-question");
            return;
        }
        if (isCompactionScenario(prompt)
                && !prompt.contains("[Tool list_files result]")) {
            emitTool(onChunk, "list_files",
                    "{\"path\":\"" + COMPACTION_ROOT_PATH + "\"}",
                    "acceptance-compaction-large-tool-call");
            return;
        }
        if (isCompactionScenario(prompt)
                && prompt.contains("[acceptance:compaction-restart]")
                && prompt.contains("[Tool list_files result]")
                && !prompt.contains("[acceptance:compaction-restart-wait]")) {
            emitTool(onChunk, "question",
                    "{\"question\":\"Continue compaction recovery after the JVM restart?\","
                            + "\"summary\":\"[acceptance:compaction-restart-wait]\","
                            + "\"options\":[\"Continue after restart\",\"Stop\"]}",
                    "acceptance-compaction-restart-question");
            return;
        }
        if (prompt.contains("[acceptance:permission-batch]")
                && !hasResumedInteraction(prompt, "waiting_approval")) {
            emitToolBatch(onChunk, List.of(
                    new ScriptedToolCall("read_file", "{\"file_path\":\".env\"}",
                            "acceptance-permission-batch-read"),
                    new ScriptedToolCall("list_files", "{\"path\":\"\"}",
                            "acceptance-permission-batch-list")));
            return;
        }
        if (prompt.contains("[acceptance:permission]") && !hasResumedInteraction(prompt, "waiting_approval")) {
            emitTool(onChunk, "read_file", "{\"file_path\":\".env\"}",
                    "acceptance-permission-read-env");
            return;
        }
        if (prompt.contains("[acceptance:environment-wait]")
                && !prompt.contains("[Tool write_file result]")) {
            emitToolBatch(onChunk, List.of(
                    new ScriptedToolCall("write_file",
                            writeFileArguments("acceptance-environment-test.cjs", ENVIRONMENT_TEST_SCRIPT),
                            "acceptance-environment-script"),
                    new ScriptedToolCall("write_file",
                            writeFileArguments("package.json", ENVIRONMENT_TEST_PACKAGE),
                            "acceptance-environment-package")));
            return;
        }
        if (prompt.contains("[acceptance:environment-wait]")
                && countOccurrences(prompt, "[Tool run_tests result]") < 2) {
            emitTool(onChunk, "run_tests", "{\"strategy\":\"test\"}",
                    countOccurrences(prompt, "[Tool run_tests result]") == 0
                            ? "acceptance-environment-first-test"
                            : "acceptance-environment-retry-test");
            return;
        }
        if (prompt.contains("[acceptance:unverified]") && !prompt.contains("[Tool write_file result]")) {
            emitTool(onChunk, "write_file",
                    "{\"file_path\":\"unverified-acceptance.txt\",\"content\":\"must-not-complete\"}",
                    "acceptance-unverified-write");
            return;
        }
        if (prompt.contains("[acceptance:evidence]") && !prompt.contains("[Tool write_file result]")) {
            emitTool(onChunk, "write_file",
                    "{\"file_path\":\"package.json\",\"content\":"
                    + "\"{\\\"name\\\":\\\"acceptance-evidence\\\","
                    + "\\\"scripts\\\":{\\\"test\\\":"
                    + "\\\"node -e \\\\\\\"process.exit(0)\\\\\\\"\\\"}}\"}",
                    "acceptance-evidence-write");
            return;
        }
        if (prompt.contains("[acceptance:evidence]")
                && prompt.contains("[Tool write_file result]")
                && !prompt.contains("[Tool read_file result]")) {
            emitTool(onChunk, "read_file", "{\"file_path\":\"package.json\"}", "acceptance-evidence-read");
            return;
        }
        if (prompt.contains("[acceptance:evidence]")
                && prompt.contains("[Tool read_file result]")
                && prompt.contains("package.json")
                && !prompt.contains("[Tool run_tests result]")) {
            emitTool(onChunk, "run_tests", "{\"strategy\":\"test\"}", "acceptance-evidence-test");
            return;
        }
        if (prompt.contains("[acceptance:question]") && !hasResumedInteraction(prompt, "waiting_user")) {
            emitTool(onChunk, "question",
                    "{\"question\":\"是否继续真实验收？\",\"summary\":\"等待验收选择\","
                            + "\"options\":[\"继续真实验收\",\"停止\"]}",
                    "acceptance-question");
            return;
        }
        if (prompt.contains("[acceptance:approval-cancel]")
                && !hasResumedInteraction(prompt, "waiting_approval")) {
            emitTool(onChunk, "shell",
                    "{\"command\":\"python3 -m http.server 0\",\"timeout_seconds\":40}",
                    "acceptance-approved-command-cancel-shell");
            return;
        }
        if (prompt.contains("[acceptance:approval]") && !hasResumedInteraction(prompt, "waiting_approval")) {
            emitToolBatch(onChunk, List.of(
                    new ScriptedToolCall("shell", "{\"command\":\"git add .\",\"timeout_seconds\":10}",
                            "acceptance-command-approval-shell"),
                    new ScriptedToolCall("list_files", "{\"path\":\"\"}",
                            "acceptance-command-approval-list")));
            return;
        }

        if (prompt.contains("[acceptance:checkout-hold]")) {
            holdCheckoutLease(token);
        }

        String finalText = finalReply(prompt);
        if (prompt.contains("[acceptance:reasoning-boundary]")) {
            onChunk.accept(new StreamChunk("text_delta", "\\", null, null, null, false, null,
                    null, null, null));
            onChunk.accept(new StreamChunk("text_delta", "<thi", null, null, null, false, null,
                    null, null, null));
            onChunk.accept(new StreamChunk("text_delta",
                    "nk data-channel=\\\"content\\\"\\>Private content-channel outer plan. "
                            + "<thinking>Nested private plan.</thinking> Private content-channel tail. \\</think",
                    null, null, null, false, null, null, null, null));
            onChunk.accept(new StreamChunk("text_delta", "ing\\>", null, null, null, false, null,
                    null, null, null));
        }
        onChunk.accept(new StreamChunk("text_delta", finalText, null, null, null, false, null,
                null, null, null));
        onChunk.accept(new StreamChunk("done", "", null, null, null, true, usageFor(prompt),
                null, null, null));
    }


    private Map<String, Object> usageFor(String prompt) {
        return prompt != null && prompt.contains("[acceptance:cache-telemetry]") ? CACHE_HIT_USAGE : USAGE;
    }

    /** acceptance profile 内暂挂没有 token 的 compaction Provider 调用，供真实 interrupt 竞态验收。 */
    private void holdCompactionRequest(CancellationToken token) {
        long configured = Long.getLong("labex.acceptance.compaction.hold.ms", 4000L);
        long remaining = Math.max(0L, Math.min(15000L, configured));
        while (remaining > 0L && !token.isCancellationRequested()) {
            long slice = Math.min(100L, remaining);
            try {
                Thread.sleep(slice);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            }
            remaining -= slice;
        }
    }

    private Map<String, Object> cancelledResponse() {
        return Map.of("type", "cancelled", "message", "Provider request cancelled", "content", "");
    }

    private void holdCheckoutLease(CancellationToken token) {
        long configured = Long.getLong("labex.acceptance.hold.ms", 4000L);
        long remaining = Math.max(0L, Math.min(15000L, configured));
        while (remaining > 0L && !token.isCancellationRequested()) {
            long slice = Math.min(100L, remaining);
            try {
                Thread.sleep(slice);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            }
            remaining -= slice;
        }
    }

    private String writeFileArguments(String filePath, String content) {
        JsonObject arguments = new JsonObject();
        arguments.addProperty("file_path", filePath);
        arguments.addProperty("content", content);
        return arguments.toString();
    }

    private int countOccurrences(String value, String marker) {
        if (value == null || value.isEmpty() || marker == null || marker.isEmpty()) return 0;
        int count = 0;
        int offset = 0;
        while ((offset = value.indexOf(marker, offset)) >= 0) {
            count++;
            offset += marker.length();
        }
        return count;
    }

    private void emitTool(Consumer<StreamChunk> onChunk, String name, String arguments, String id) {
        emitToolBatch(onChunk, List.of(new ScriptedToolCall(name, arguments, id)));
    }

    private void emitToolBatch(Consumer<StreamChunk> onChunk, List<ScriptedToolCall> calls) {
        for (int index = 0; index < calls.size(); index++) {
            ScriptedToolCall call = calls.get(index);
            onChunk.accept(new StreamChunk("tool_call", "", call.name(), call.arguments(), null, false, USAGE,
                    call.id(), index, null));
        }
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
                || lower.contains("command approval decision")
                || lower.contains("resolution status: answered")
                || lower.contains("resolution status: approved")
                || lower.contains("resolution status: rejected")
                || lower.contains("resolution status: cancelled")
                || lower.contains("resolution status: failed")
                || lower.contains("persisted user response is ready");
    }

    private boolean isCompactionScenario(String prompt) {
        return prompt.contains("[acceptance:compaction]");
    }

    private boolean isCompactionRequest(String systemPrompt) {
        String normalized = systemPrompt == null ? "" : systemPrompt.toLowerCase(Locale.ROOT);
        return normalized.contains("context compaction agent")
                || normalized.contains("nextactions") && normalized.contains("openrisks");
    }

    private String finalReply(String prompt) {
        Matcher marker = ISOLATION_MARKER.matcher(prompt);
        String isolation = marker.find() ? marker.group(1).trim() : "none";
        if (isCompactionScenario(prompt)) {
            return "## Summary\n**Completed**\n- The durable compaction epoch was applied before the final provider turn.\n"
                    + "**Verification**\n- The latest summary, retained tail, and post-boundary transcript remained protocol-valid.\n"
                    + "**Risk**\n- The large native tool arguments are acceptance-profile only.";
        }
        if (prompt.contains("[acceptance:checkout-hold]")) {
            return "## Summary\n**Completed**\n- The acceptance run held and released the project checkout lease.\n"
                    + "**Verification**\n- A concurrent task can observe the durable workspace-wait state.\n"
                    + "**Risk**\n- The hold duration is bounded and acceptance-profile only.";
        }
        if (prompt.contains("[acceptance:cache-telemetry]")) {
            return "## Summary\n**Completed**\n- Prompt cache telemetry was emitted by the acceptance provider.\n"
                    + "**Verification**\n- The durable token usage event reports 50 cached tokens from 200 prompt tokens.\n"
                    + "**Risk**\n- These deterministic usage values are acceptance-profile only.";
        }
        if (prompt.contains("[acceptance:text-tool-fallback]")) {
            return "## Summary\n**Completed**\n- The strict explicit text tool-call fallback executed `list_files`.\n"
                    + "**Verification**\n- Ordinary prose remains non-executable; this acceptance-only envelope passed tool selection and schema validation.\n"
                    + "**Risk**\n- Native structured tool calls remain the preferred production protocol.";
        }
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
            String decision = prompt.toLowerCase(Locale.ROOT).contains("rejected") ? "rejected" : "approved";
            return "## Summary\n**Completed**\n- The one-time command approval decision was " + decision
                    + " and resumed the original task and conversation.\n"
                    + "**Verification**\n- Approval ownership stayed bound to its request and the Agent emitted a final structured result.\n"
                    + "**Risk**\n- The disposable acceptance project may contain a failed git command observation by design.";
        }
        if (prompt.contains("[acceptance:permission-batch]")) {
            return "## Summary\n**Completed**\n- The multi-tool permission batch resumed the original task.\n"
                    + "**Verification**\n- The approved call and skipped companion call both produced durable Provider tool results in their original order.\n"
                    + "**Risk**\n- This deterministic batch is available only in the acceptance profile.";
        }
        if (prompt.contains("[acceptance:permission]")) {
            return "## Summary\n**Completed**\n- The tool permission decision resumed the original task.\n"
                    + "**Verification**\n- The acceptance run reached a final reply after the .env read decision.\n"
                    + "**Risk**\n- The acceptance provider never reads credentials itself.";
        }
        if (prompt.contains("[acceptance:environment-wait]")) {
            return "## Summary\n**Completed**\n- The environment recovery resumed the original task after the same verification command succeeded.\n"
                    + "**Verification**\n- The first deterministic run reported a dependency-resolution blocker and the resumed run passed without creating a new task.\n"
                    + "**Risk**\n- The dependency failure is an acceptance-only local fixture and does not access the network.";
        }
        if (prompt.contains("[acceptance:evidence]")) {
            return "## Summary\n**Completed**\n- The edit and completion evidence scenario finished.\n"
                    + "**Verification**\n- A write_file observation and successful run_tests observation produced durable completion evidence.\n"
                    + "**Risk**\n- The package file belongs only to the disposable acceptance project.";
        }
        return "## Summary\n**Completed**\n- Conversation isolation marker: `" + isolation + "`.\n"
                + "**Verification**\n- The reply was derived only from messages supplied to this provider invocation; no shared mutable session state exists.\n"
                + "**Risk**\n- This is an acceptance-only deterministic response and does not validate an external model service.";
    }

    private record ScriptedToolCall(String name, String arguments, String id) {
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
