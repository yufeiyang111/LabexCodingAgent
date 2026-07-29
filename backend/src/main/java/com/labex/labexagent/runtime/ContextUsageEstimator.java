package com.labex.labexagent.runtime;

import com.google.gson.Gson;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

@Service
public class ContextUsageEstimator {
    private static final String TOOL_RESULT_PREFIX = "[Tool ";
    private static final int MAX_PREVIEW_SECTION_CHARS = 8_000;
    private static final int MAX_PREVIEW_TOTAL_CHARS = 36_000;
    private static final Gson GSON = new Gson();
    private static final Pattern API_KEY = Pattern.compile("(?i)\\bsk-[a-z0-9_-]{10,}\\b");
    private static final Pattern BEARER = Pattern.compile("(?i)\\bbearer\\s+[a-z0-9._~-]{10,}");
    private static final Pattern NAMED_SECRET = Pattern.compile(
            "(?i)\\b(api[_ -]?key|authorization|token|password|secret)\\s*[:=]\\s*([^\\s,;\\]}]+)");

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
        categories.put("toolDefinitions", estimateTokens(serialize(tools)));
        categories.put("projectContext", estimateTokens(context.projectContext()));
        categories.put("workspaceMemory", estimateTokens(context.workspaceMemory()));
        categories.put("compactedContext", estimateTokens(context.compactedContext()));
        categories.put("skillsAndInstructions", estimateTokens(context.skillsAndInstructions()));
        categories.put("fixedInstructions", estimateTokens(context.fixedInstructions()));
        categories.put("conversationMessages", 0);
        categories.put("toolResults", 0);

        for (Map<String, Object> message : messages == null ? List.<Map<String, Object>>of() : messages) {
            Object content = message.get("content");
            String text = content instanceof String value ? value : serialize(content);
            if (text.equals(context.initialContextMessage())) continue;
            String category = text.startsWith(TOOL_RESULT_PREFIX) && text.contains("result]")
                    ? "toolResults" : "conversationMessages";
            categories.merge(category, estimateTokens(text), Integer::sum);
        }
        return Map.copyOf(categories);
    }

    public int estimateTokens(String text) {
        return text == null || text.isBlank() ? 0 : Math.max(1, text.length() / 3);
    }

    private List<ContextPreviewSection> buildPreview(String systemPrompt, Object tools,
                                                     PromptContext promptContext,
                                                     List<Map<String, Object>> messages) {
        StringBuilder conversation = new StringBuilder();
        StringBuilder toolResults = new StringBuilder();
        for (Map<String, Object> message : messages == null ? List.<Map<String, Object>>of() : messages) {
            Object content = message.get("content");
            String text = content instanceof String value ? value : serialize(content);
            if (text.equals(promptContext.initialContextMessage())) continue;
            String role = String.valueOf(message.getOrDefault("role", "user"));
            String entry = "[" + role + "] " + text + "\n";
            if (text.startsWith(TOOL_RESULT_PREFIX) && text.contains("result]")) {
                toolResults.append(entry);
            } else {
                conversation.append(entry);
            }
        }
        List<PreviewInput> inputs = List.of(
                new PreviewInput("systemPrompt", systemPrompt),
                new PreviewInput("projectContext", promptContext.projectContext()),
                new PreviewInput("workspaceMemory", promptContext.workspaceMemory()),
                new PreviewInput("compactedContext", promptContext.compactedContext()),
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

    private String serialize(Object value) {
        return value == null ? "" : GSON.toJson(value);
    }

    private record PreviewInput(String key, String content) {
    }

    public record PromptContext(String projectContext, String workspaceMemory, String compactedContext,
                                String skillsAndInstructions, String fixedInstructions,
                                String initialContextMessage) {
        public PromptContext(String workspaceMemory, String skillsAndInstructions, String fixedInstructions,
                             String initialContextMessage) {
            this("", workspaceMemory, "", skillsAndInstructions, fixedInstructions, initialContextMessage);
        }

        public static PromptContext of(String projectRules, String memoryContext, String sessionContext,
                                       String recentRunLog, String checkpoint, String globalSkills,
                                       String mcpContext, String modePolicy, String languagePolicy,
                                       String initialContextMessage) {
            TaggedSection workspaceSection = extractTaggedSection(sessionContext, "workspace_memory");
            String projectContext = join(projectRules, workspaceSection.remaining());
            String compactedContext = join(memoryContext, recentRunLog, checkpoint);
            String skillsAndInstructions = join(globalSkills, mcpContext);
            return new PromptContext(projectContext, workspaceSection.section(), compactedContext,
                    skillsAndInstructions, join(modePolicy, languagePolicy), initialContextMessage);
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
