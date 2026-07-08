package com.labex.labexagent.llm;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

public interface LlmProvider {

    String getProviderId();

    String getProviderName();

    boolean supportsStreaming();

    boolean supportsToolCalling();

    Map<String, Object> chatWithTools(String sysPrompt, List<Map<String, Object>> msgs,
                                       List<Map<String, Object>> tools, LlmConfig config);

    void chatStream(String sysPrompt, List<Map<String, Object>> msgs,
                     List<Map<String, Object>> tools, LlmConfig config,
                     Consumer<StreamChunk> onChunk);

    record LlmConfig(String apiKey, String baseUrl, String modelName,
                      Integer maxTokens, Double temperature) {}

    record StreamChunk(String type, String content, String toolName,
                        String toolArgs, String thinking, boolean done) {}
}
