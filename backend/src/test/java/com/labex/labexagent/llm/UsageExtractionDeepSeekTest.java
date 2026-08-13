package com.labex.labexagent.llm;

import com.google.gson.Gson;
import com.google.gson.JsonParser;
import com.labex.labexagent.network.OutboundUrlPolicy;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UsageExtractionDeepSeekTest {

    @Test
    void extractsDeepSeekHitFieldsFromOfficialFixture() throws Exception {
        Map<String, Object> usage = extractUsageFromFixture("deepseek-usage-hit.json");
        assertEquals(550, usage.get("cached_tokens"));
        assertEquals(450, usage.get("cache_write_tokens"));
        assertEquals(550, usage.get("cache_hit_tokens"));
        assertEquals(450, usage.get("cache_miss_tokens"));
        assertEquals(1000, usage.get("prompt_tokens"));
        assertEquals(true, usage.get("cache_usage_reported"));
    }

    @Test
    void deepSeekMissTokensBecomeCacheWrite() throws Exception {
        Map<String, Object> usage = extractUsageFromFixture("deepseek-usage-miss.json");
        assertEquals(0, usage.get("cached_tokens"));
        assertEquals(800, usage.get("cache_write_tokens"));
        assertEquals(0, usage.get("cache_hit_tokens"));
        assertEquals(800, usage.get("cache_miss_tokens"));
        assertEquals(true, usage.get("cache_usage_reported"));
        assertEquals(CacheTelemetryStatus.WRITE_ONLY, CacheTelemetry.status(true, usage));
    }

    @Test
    void anthropicCacheFieldsAreNormalizedIntoTokens() throws Exception {
        Map<String, Object> usage = extractUsageFromFixture("anthropic-usage-cache.json");
        assertEquals(300, usage.get("cached_tokens"));
        assertEquals(400, usage.get("cache_write_tokens"));
        assertEquals(1400, usage.get("prompt_tokens"));
        assertEquals(true, usage.get("cache_usage_reported"));
    }

    @Test
    void openAiCachedDetailsStillExtracted() throws Exception {
        Map<String, Object> usage = extractUsageFromFixture("openai-usage-cached.json");
        assertEquals(400, usage.get("cached_tokens"));
        assertEquals(900, usage.get("prompt_tokens"));
        assertEquals(true, usage.get("cache_usage_reported"));
    }

    @Test
    void hitRateUsesMissAwareDenominatorWhenProviderExcludesCacheFromPrompt() {
        assertEquals(40.0, CacheTelemetry.hitRate(true, Map.of(
                "prompt_tokens", 60, "cached_tokens", 40, "cache_miss_tokens", 60,
                "cache_usage_reported", true)));
    }

    private static Map<String, Object> extractUsageFromFixture(String fixture) throws Exception {
        String json = new Gson().toJson(JsonParser.parseString(fixtureJson(fixture)));
        HttpServer server = startServer(exchange -> {
            exchange.getRequestBody().readAllBytes();
            sendSse(exchange,
                    "data: {\"choices\":[{\"delta\":{\"content\":\"ok\"}}],\"usage\":null}\n\n" +
                    "data: {\"choices\":[],\"usage\":" + json + "}\n\n" +
                    "data: [DONE]\n\n");
        });
        try {
            List<LlmProvider.StreamChunk> chunks = new ArrayList<>();
            new OpenAiCompatibleProvider(new OutboundUrlPolicy(host ->
                    new InetAddress[] {InetAddress.getByName("8.8.8.8")}))
                    .chatStream("system", List.of(Map.of("role", "user", "content", "hi")), List.of(),
                            new LlmProvider.LlmConfig("test-key", baseUrl(server), "test-model", 32, 0.1),
                            chunks::add);
            LlmProvider.StreamChunk usageChunk = chunks.stream()
                    .filter(chunk -> "usage".equals(chunk.type()))
                    .findFirst()
                    .orElseThrow();
            assertTrue(usageChunk.usage().size() > 0);
            return usageChunk.usage();
        } finally {
            server.stop(0);
        }
    }

    private static String fixtureJson(String name) throws IOException {
        try (var in = UsageExtractionDeepSeekTest.class.getResourceAsStream("/fixtures/usage/" + name)) {
            if (in == null) throw new IOException("missing fixture " + name);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static HttpServer startServer(Handler handler) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", exchange -> {
            try {
                handler.handle(exchange);
            } finally {
                exchange.close();
            }
        });
        server.start();
        return server;
    }

    private static String baseUrl(HttpServer server) {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private static void sendSse(HttpExchange exchange, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
    }

    @FunctionalInterface
    private interface Handler {
        void handle(HttpExchange exchange) throws IOException;
    }
}
