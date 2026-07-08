package com.labex.rag.llm;

import java.util.List;
import java.util.Map;

public interface LLMChat {
    public String chat(String var1, String var2, String var3);

    default public String chatWithHistory(String prompt, String context, String question, List<Map<String, String>> history) {
        return this.chat(prompt, context, question);
    }
}

