package com.labex.rag.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * RAG Module Configuration
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "rag")
public class RagConfig {

    private String embeddingServiceUrl = "http://localhost:5000";

    private String miniMaxApiKey;

    private String miniMaxBaseUrl = "https://api.minimaxi.com/v1";

    private String miniMaxApiHost;

    private String miniMaxModel = "MiniMax-M2.7";

    private boolean imageUnderstandingEnabled = true;

    private int maxContextChunks = 10;

    private int chunkSize = 500;

    private int chunkOverlap = 50;

    private String uploadPath = "./uploads";

    private String llmProvider = "minimax";

    private String ollamaBaseUrl = "http://localhost:11434";

    private String ollamaModel = "qwen2.5:3b";

    private String tavilyApiKey;
}
