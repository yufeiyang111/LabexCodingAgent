package com.labex.labexagent.llm;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 编译期 Prompt Caching 策略（对标 OpenCode packages/llm/src/cache-policy.ts）。
 *
 * <p>默认 "AUTO" 策略在以下三处放置 ephemeral 缓存断点：
 * 1. 最后一个 Tool 定义 (Last Tool)
 * 2. System Prompt 消息 (System Prompt)
 * 3. 最后一个用户提问消息 (Latest User Turn)
 *
 * <p>该工具只供明确支持 inline cache hint 的协议适配器使用。当前
 * {@link OpenAiCompatibleChatRequestAdapter} 走 OpenAI-compatible 的隐式前缀缓存，
 * 不向请求体写入这些无效 hint。</p>
 */
public final class PromptCachePolicy {
    private static final Map<String, Object> EPHEMERAL_CACHE_HINT = Map.of("type", "ephemeral");

    private PromptCachePolicy() {
    }

    /**
     * 为消息流注入缓存断点（System Message 与 Latest User Message）。
     */
    public static List<Map<String, Object>> applyToMessages(List<Map<String, Object>> messages) {
        if (messages == null || messages.isEmpty()) {
            return List.of();
        }
        List<Map<String, Object>> result = new ArrayList<>(messages.size());

        // 1. 找到最后一个 user 消息的索引
        int lastUserIndex = -1;
        for (int i = messages.size() - 1; i >= 0; i--) {
            Map<String, Object> msg = messages.get(i);
            if (msg != null && "user".equalsIgnoreCase(String.valueOf(msg.get("role")))) {
                lastUserIndex = i;
                break;
            }
        }

        // 2. 拷贝并注入 cache_control
        for (int i = 0; i < messages.size(); i++) {
            Map<String, Object> msg = messages.get(i);
            if (msg == null) continue;
            Map<String, Object> copy = new LinkedHashMap<>(msg);

            String role = String.valueOf(msg.get("role"));
            boolean isSystem = "system".equalsIgnoreCase(role);
            boolean isLatestUser = (i == lastUserIndex);

            if (isSystem || isLatestUser) {
                if (!copy.containsKey("cache_control")) {
                    copy.put("cache_control", EPHEMERAL_CACHE_HINT);
                }
            }
            result.add(copy);
        }
        return result;
    }

    /**
     * 为工具列表的最后一个工具注入缓存断点。
     */
    public static List<Map<String, Object>> applyToTools(List<Map<String, Object>> tools) {
        if (tools == null || tools.isEmpty()) {
            return List.of();
        }
        List<Map<String, Object>> result = new ArrayList<>(tools.size());
        int lastIndex = tools.size() - 1;

        for (int i = 0; i < tools.size(); i++) {
            Map<String, Object> tool = tools.get(i);
            if (tool == null) continue;
            Map<String, Object> copy = new LinkedHashMap<>(tool);
            if (i == lastIndex) {
                if (!copy.containsKey("cache_control")) {
                    copy.put("cache_control", EPHEMERAL_CACHE_HINT);
                }
            }
            result.add(copy);
        }
        return result;
    }
}
