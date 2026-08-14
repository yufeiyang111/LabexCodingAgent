package com.labex.labexagent.runtime;

import com.labex.labexagent.context.AgentRequestTokenEstimator;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class ContextUsageEstimator {
    private static final String TOOL_RESULT_PREFIX = "[Tool ";
    private static final int MAX_PREVIEW_SECTION_CHARS = 8_000;
    private static final int MAX_PREVIEW_TOTAL_CHARS = 36_000;
    private static final Pattern API_KEY = Pattern.compile("(?i)\\bsk-[a-z0-9_-]{10,}\\b");
    private static final Pattern BEARER = Pattern.compile("(?i)\\bbearer\\s+[a-z0-9._~-]{10,}");
    private static final Pattern NAMED_SECRET = Pattern.compile(
            "(?i)\\b(api[_ -]?key|authorization|token|password|secret)\\s*[:=]\\s*([^\\s,;\\]}]+)");

    private final AgentRequestTokenEstimator requestTokenEstimator;

    @Autowired
    public ContextUsageEstimator(AgentRequestTokenEstimator requestTokenEstimator) {
        this.requestTokenEstimator = requestTokenEstimator == null
                ? new AgentRequestTokenEstimator() : requestTokenEstimator;
    }

    /** 保持既有直接构造测试可用；运行时由 Spring 注入带附件配置的统一估算器。 */
    public ContextUsageEstimator() {
        this(new AgentRequestTokenEstimator());
    }

    public ContextUsageSnapshot estimate(String conversationId, String sessionId, String provider, String model,
                                         Integer contextWindowTokens, String systemPrompt, Object tools,
                                         PromptContext promptContext, List<Map<String, Object>> messages,
                                         String trimState) {
        return estimate(conversationId, sessionId, provider, model, contextWindowTokens, systemPrompt, tools,
                promptContext, messages, trimState, "LAST_ACTUAL_REQUEST", Map.of());
    }

    public ContextUsageSnapshot estimateNextRequest(String conversationId, String sessionId, String provider, String model,
                                                    Integer contextWindowTokens, String systemPrompt, Object tools,
                                                    PromptContext promptContext, List<Map<String, Object>> messages,
                                                    Map<String, Object> previewMetadata) {
        return estimate(conversationId, sessionId, provider, model, contextWindowTokens, systemPrompt, tools,
                promptContext, messages, "NEXT_REQUEST_ESTIMATE", "NEXT_REQUEST_ESTIMATE", previewMetadata);
    }

    private ContextUsageSnapshot estimate(String conversationId, String sessionId, String provider, String model,
                                          Integer contextWindowTokens, String systemPrompt, Object tools,
                                          PromptContext promptContext, List<Map<String, Object>> messages,
                                          String trimState, String previewSource,
                                          Map<String, Object> previewMetadata) {
        Map<String, Integer> categories = estimateCategories(systemPrompt, tools, promptContext, messages);

        return new ContextUsageSnapshot(conversationId, sessionId, provider, model,
                contextWindowTokens, categories, trimState,
                buildPreview(systemPrompt, tools, promptContext, messages), previewSource, previewMetadata);
    }

    public Map<String, Integer> estimateCategories(String systemPrompt, Object tools,
                                                   PromptContext promptContext,
                                                   List<Map<String, Object>> messages) {
        PromptContext context = promptContext == null ? new PromptContext("", "", "", "") : promptContext;
        Map<String, Integer> categories = new LinkedHashMap<>();
        categories.put("systemPrompt", estimateTokens(systemPrompt));
        categories.put("toolDefinitions", requestTokenEstimator.estimateValue(tools));
        categories.put("projectContext", estimateTokens(context.projectContext()));
        categories.put("workspaceMemory", estimateTokens(context.workspaceMemory()));
        categories.put("conversationMemory", estimateTokens(context.conversationMemory()));
        categories.put("runRecoveryContext", estimateTokens(context.runRecoveryContext()));
        categories.put("compactionSummary", estimateTokens(context.compactionSummary()));
        categories.put("skillsAndInstructions", estimateTokens(context.skillsAndInstructions()));
        categories.put("fixedInstructions", estimateTokens(context.fixedInstructions()));
        categories.put("conversationMessages", 0);
        categories.put("imageInputs", 0);
        categories.put("toolResults", 0);
        categories.put("messageProtocol", 0);

        for (Map<String, Object> message : messages == null ? List.<Map<String, Object>>of() : messages) {
            Object content = message.get("content");
            String text = contentText(content);
            if (text.equals(context.initialContextMessage())) continue;
            String role = String.valueOf(message.getOrDefault("role", "user"));
            String category;
            if ("tool".equalsIgnoreCase(role)
                    || (text.startsWith(TOOL_RESULT_PREFIX) && text.contains("result]"))) {
                category = "toolResults";
            } else if (isCompactionSummary(text)) {
                category = "compactionSummary";
            } else {
                category = "conversationMessages";
            }
            AgentRequestTokenEstimator.ValueEstimate contentEstimate = requestTokenEstimator.analyzeValue(content);
            categories.merge(category, contentEstimate.textTokens(), Integer::sum);
            categories.merge("imageInputs", contentEstimate.imageInputTokens(), Integer::sum);
            LinkedHashMap<String, Object> protocol = new LinkedHashMap<>(message);
            protocol.remove("content");
            categories.merge("messageProtocol", requestTokenEstimator.estimateValue(protocol), Integer::sum);
        }
        return Map.copyOf(categories);
    }

    public int estimateTokens(String text) {
        return requestTokenEstimator.estimateValue(text);
    }

    private boolean isCompactionSummary(String text) {
        return text != null && text.contains("<conversation-checkpoint");
    }

    private String joinNonBlank(String... values) {
        StringBuilder result = new StringBuilder();
        for (String value : values) {
            if (value == null || value.isBlank()) continue;
            if (!result.isEmpty()) result.append('\n');
            result.append(value);
        }
        return result.toString();
    }

    private List<ContextPreviewSection> buildPreview(String systemPrompt, Object tools,
                                                     PromptContext promptContext,
                                                     List<Map<String, Object>> messages) {
        StringBuilder conversation = new StringBuilder();
        StringBuilder toolResults = new StringBuilder();
        StringBuilder compactionSummaries = new StringBuilder();
        for (Map<String, Object> message : messages == null ? List.<Map<String, Object>>of() : messages) {
            Object content = message.get("content");
            String text = contentText(content);
            if (text.equals(promptContext.initialContextMessage())) continue;
            String role = String.valueOf(message.getOrDefault("role", "user"));
            String entry = "[" + role + "] " + text + "\n";
            if ("tool".equalsIgnoreCase(role)
                    || (text.startsWith(TOOL_RESULT_PREFIX) && text.contains("result]"))) {
                toolResults.append(entry);
            } else if (isCompactionSummary(text)) {
                compactionSummaries.append(entry);
            } else {
                conversation.append(entry);
            }
        }
        List<PreviewInput> inputs = List.of(
                new PreviewInput("systemPrompt", systemPrompt),
                new PreviewInput("projectContext", promptContext.projectContext()),
                new PreviewInput("workspaceMemory", promptContext.workspaceMemory()),
                new PreviewInput("conversationMemory", promptContext.conversationMemory()),
                new PreviewInput("runRecoveryContext", promptContext.runRecoveryContext()),
                new PreviewInput("compactionSummary", joinNonBlank(promptContext.compactionSummary(), compactionSummaries.toString())),
                new PreviewInput("skillsAndInstructions", promptContext.skillsAndInstructions()),
                new PreviewInput("toolDefinitions", serialize(tools)),
                new PreviewInput("fixedInstructions", promptContext.fixedInstructions()),
                new PreviewInput("conversationMessages", conversation.toString()),
                new PreviewInput("toolResults", toolResults.toString()));
        List<ContextPreviewSection> sections = new ArrayList<>();
        int remaining = MAX_PREVIEW_TOTAL_CHARS;
        for (PreviewInput input : inputs) {
            String safe = redact(input.content()).trim();
            if (safe.isBlank() || remaining <= 0) continue;
            int maxChars = Math.min(MAX_PREVIEW_SECTION_CHARS, remaining);
            boolean truncated = safe.length() > maxChars;
            String content = truncated ? safe.substring(0, maxChars) + "\n[... truncated ...]" : safe;
            sections.add(new ContextPreviewSection(input.key(), content, estimateTokens(content), truncated));
            remaining -= Math.min(maxChars, safe.length());
        }
        return sections;
    }

    private String redact(String value) {
        String safe = value == null ? "" : value;
        safe = API_KEY.matcher(safe).replaceAll("[REDACTED]");
        safe = BEARER.matcher(safe).replaceAll("Bearer [REDACTED]");
        return NAMED_SECRET.matcher(safe).replaceAll("$1=[REDACTED]");
    }

    private String contentText(Object content) {
        return content instanceof String value ? value : requestTokenEstimator.sanitizedSerialization(content);
    }

    private String serialize(Object value) {
        return value == null ? "" : requestTokenEstimator.sanitizedSerialization(value);
    }

    private record PreviewInput(String key, String content) {
    }

    public record PromptContext(String projectContext, String workspaceMemory, String conversationMemory,
                                String runRecoveryContext, String compactionSummary,
                                String skillsAndInstructions, String fixedInstructions,
                                String initialContextMessage) {
        public PromptContext(String workspaceMemory, String skillsAndInstructions, String fixedInstructions,
                             String initialContextMessage) {
            this("", workspaceMemory, "", "", "", skillsAndInstructions, fixedInstructions, initialContextMessage);
        }

        /** 兼容旧调用：第三个参数只代表确实存在的压缩摘要，不能再混入普通恢复上下文。 */
        public PromptContext(String projectContext, String workspaceMemory, String compactedContext,
                             String skillsAndInstructions, String fixedInstructions,
                             String initialContextMessage) {
            this(projectContext, workspaceMemory, "", "", compactedContext,
                    skillsAndInstructions, fixedInstructions, initialContextMessage);
        }

        /** 兼容旧访问器，返回真正的压缩摘要。 */
        @Deprecated
        public String compactedContext() {
            return compactionSummary;
        }

        public static PromptContext of(String projectRules, String memoryContext, String sessionContext,
                                       String recentRunLog, String checkpoint, String globalSkills,
                                       String mcpContext, String modePolicy, String languagePolicy,
                                       String initialContextMessage) {
            TaggedSection workspaceSection = extractTaggedSection(sessionContext, "workspace_memory");
            String projectContext = join(projectRules, workspaceSection.remaining());
            String skillsAndInstructions = join(globalSkills, mcpContext);
            return new PromptContext(projectContext, workspaceSection.section(), memoryContext,
                    recentRunLog, checkpoint, skillsAndInstructions,
                    join(modePolicy, languagePolicy), initialContextMessage);
        }

        private static TaggedSection extractTaggedSection(String value, String tag) {
            String source = value == null ? "" : value;
            String opening = "<" + tag + ">";
            String closing = "</" + tag + ">";
            int start = source.indexOf(opening);
            if (start < 0) {
                return new TaggedSection("", source.trim());
            }
            int end = source.indexOf(closing, start + opening.length());
            if (end < 0) {
                return new TaggedSection("", source.trim());
            }
            int sectionEnd = end + closing.length();
            String section = source.substring(start, sectionEnd).trim();
            String remaining = (source.substring(0, start) + source.substring(sectionEnd)).trim();
            return new TaggedSection(section, remaining);
        }

        private static String join(String... values) {
            StringBuilder builder = new StringBuilder();
            for (String value : values) {
                if (value != null && !value.isBlank()) builder.append(value);
            }
            return builder.toString();
        }

        private record TaggedSection(String section, String remaining) {
        }
    }
}
