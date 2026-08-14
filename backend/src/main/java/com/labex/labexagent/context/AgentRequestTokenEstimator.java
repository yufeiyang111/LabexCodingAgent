package com.labex.labexagent.context;

import com.google.gson.Gson;
import com.labex.labexagent.attachment.AgentAttachmentProperties;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * 估算一次真实 Provider 请求的完整 token 占用。
 *
 * <p>估算输入使用完整 JSON，而不是只统计 content，因此 role、name、tool_call_id、
 * tool_calls 参数和工具结果都会进入预算。对于 OpenAI-compatible 图片 data URL，base64 不是
 * 普通文本：保留协议结构，并按可配置的视觉输入预算计入。Provider 实际 usage 到达后仍应作为
 * 更高优先级证据。</p>
 */
@Service
public final class AgentRequestTokenEstimator {
    private static final Gson GSON = new Gson();
    private static final int REQUEST_ENVELOPE_TOKENS = 12;
    private static final int MESSAGE_ENVELOPE_TOKENS = 4;
    private static final String BASE64_MARKER = ";base64,";

    private final AgentAttachmentProperties attachmentProperties;

    @Autowired
    public AgentRequestTokenEstimator(AgentAttachmentProperties attachmentProperties) {
        this.attachmentProperties = attachmentProperties == null
                ? new AgentAttachmentProperties() : attachmentProperties;
    }

    /** 保持非 Spring 单测和兼容调用可用；生产路径由 Spring 注入附件预算配置。 */
    public AgentRequestTokenEstimator() {
        this(new AgentAttachmentProperties());
    }

    public Estimate estimate(String systemPrompt,
                             Object toolDefinitions,
                             List<Map<String, Object>> messages,
                             Integer contextWindowTokens,
                             Integer reservedOutputTokens) {
        if (contextWindowTokens == null || contextWindowTokens <= 0) {
            throw new AgentContextOverflowException("context_window_unconfigured",
                    "Model context window must be configured before provider invocation");
        }
        int reserve = reservedOutputTokens == null ? 0 : Math.max(0, reservedOutputTokens);
        if (reserve >= contextWindowTokens) {
            throw new AgentContextOverflowException("output_reserve_exhausts_context_window",
                    "Reserved output tokens leave no capacity for provider input");
        }
        int systemTokens = estimateValue(systemPrompt);
        int toolTokens = estimateValue(toolDefinitions);
        int messageTokens = estimateMessages(messages);
        int inputTokens = REQUEST_ENVELOPE_TOKENS + systemTokens + toolTokens + messageTokens;
        int inputCapacity = contextWindowTokens - reserve;
        return new Estimate(systemTokens, toolTokens, messageTokens, reserve, inputTokens,
                inputTokens + reserve, contextWindowTokens, inputCapacity, inputTokens > inputCapacity);
    }

    public int estimateMessages(List<Map<String, Object>> messages) {
        if (messages == null || messages.isEmpty()) {
            return 0;
        }
        int total = 0;
        for (Map<String, Object> message : messages) {
            total += MESSAGE_ENVELOPE_TOKENS + estimateValue(message);
        }
        return total;
    }

    public int estimateValue(Object value) {
        return analyzeValue(value).totalTokens();
    }

    /** 返回文本协议与视觉输入的独立预算，供上下文账本复用。 */
    public ValueEstimate analyzeValue(Object value) {
        if (value == null) {
            return ValueEstimate.empty();
        }
        ImageCounter images = new ImageCounter();
        String serialized = sanitizedSerialization(value, images);
        int textTokens = estimateTextTokens(serialized);
        int imageTokens = images.count * Math.max(1, attachmentProperties.getEstimatedImageTokens());
        return new ValueEstimate(textTokens, imageTokens, images.count);
    }

    /** 为上下文预览提供脱敏后的协议文本，绝不回显附件的 base64 负载。 */
    public String sanitizedSerialization(Object value) {
        return sanitizedSerialization(value, new ImageCounter());
    }

    private String sanitizedSerialization(Object value, ImageCounter images) {
        Object normalized = normalizeVisualDataUrls(value, images);
        return normalized instanceof String text ? text : GSON.toJson(normalized);
    }

    private int estimateTextTokens(String serialized) {
        if (serialized == null || serialized.isBlank()) {
            return 0;
        }
        int utf8Bytes = serialized.getBytes(StandardCharsets.UTF_8).length;
        return Math.max(1, (int) Math.ceil(utf8Bytes / 3.0));
    }

    private Object normalizeVisualDataUrls(Object value, ImageCounter images) {
        if (value instanceof Map<?, ?> map) {
            return normalizeMap(map, images);
        }
        if (value instanceof Iterable<?> iterable) {
            List<Object> normalized = new ArrayList<>();
            for (Object item : iterable) {
                normalized.add(normalizeVisualDataUrls(item, images));
            }
            return normalized;
        }
        if (value instanceof Object[] array) {
            List<Object> normalized = new ArrayList<>(array.length);
            for (Object item : array) {
                normalized.add(normalizeVisualDataUrls(item, images));
            }
            return normalized;
        }
        return value;
    }

    private Map<String, Object> normalizeMap(Map<?, ?> source, ImageCounter images) {
        boolean visualPart = isVisualDataUrlPart(source);
        LinkedHashMap<String, Object> normalized = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : source.entrySet()) {
            String key = String.valueOf(entry.getKey());
            Object child = entry.getValue();
            if (visualPart && "image_url".equals(key) && child instanceof Map<?, ?> imageUrl) {
                LinkedHashMap<String, Object> imageUrlCopy = new LinkedHashMap<>();
                for (Map.Entry<?, ?> imageEntry : imageUrl.entrySet()) {
                    String imageKey = String.valueOf(imageEntry.getKey());
                    Object imageValue = imageEntry.getValue();
                    imageUrlCopy.put(imageKey, "url".equals(imageKey) && imageValue instanceof String url
                            ? visualPlaceholder(url) : normalizeVisualDataUrls(imageValue, images));
                }
                normalized.put(key, imageUrlCopy);
                continue;
            }
            normalized.put(key, normalizeVisualDataUrls(child, images));
        }
        if (visualPart) {
            images.count++;
        }
        return normalized;
    }

    private boolean isVisualDataUrlPart(Map<?, ?> value) {
        if (!"image_url".equalsIgnoreCase(String.valueOf(value.get("type")))) {
            return false;
        }
        if (!(value.get("image_url") instanceof Map<?, ?> imageUrl)) {
            return false;
        }
        Object rawUrl = imageUrl.get("url");
        if (!(rawUrl instanceof String url)) {
            return false;
        }
        int base64 = url.indexOf(BASE64_MARKER);
        return base64 > "data:image/".length()
                && url.regionMatches(true, 0, "data:image/", 0, "data:image/".length());
    }

    private String visualPlaceholder(String dataUrl) {
        int base64 = dataUrl.indexOf(BASE64_MARKER);
        return dataUrl.substring(0, base64 + BASE64_MARKER.length()) + "[visual-input]";
    }

    public record ValueEstimate(int textTokens, int imageInputTokens, int imageCount) {
        public ValueEstimate {
            textTokens = Math.max(0, textTokens);
            imageInputTokens = Math.max(0, imageInputTokens);
            imageCount = Math.max(0, imageCount);
        }

        public int totalTokens() {
            return textTokens + imageInputTokens;
        }

        private static ValueEstimate empty() {
            return new ValueEstimate(0, 0, 0);
        }
    }

    private static final class ImageCounter {
        private int count;
    }

    public record Estimate(int systemPromptTokens,
                           int toolSchemaTokens,
                           int messageTokens,
                           int reservedOutputTokens,
                           int inputTokens,
                           int totalWithReservedOutputTokens,
                           int contextWindowTokens,
                           int inputCapacityTokens,
                           boolean overflowsInputCapacity) {
    }
}
