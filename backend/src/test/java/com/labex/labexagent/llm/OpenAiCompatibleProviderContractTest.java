package com.labex.labexagent.llm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.Gson;
import com.labex.labexagent.network.OutboundUrlPolicy;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class OpenAiCompatibleProviderContractTest {
    private static final Gson GSON = new Gson();

    @Test
    void advertisesAndNegotiatesOpenAiCompatibleCapabilities() {
        OpenAiCompatibleProvider provider = providerForLocalServer();

        ProviderCapabilities capabilities = provider.capabilities();
        ProviderCapabilities negotiated = capabilities.negotiate(
                new ProviderCapabilities.Requirements(true, true, true, true, true));

        assertTrue(capabilities.streaming());
        assertTrue(capabilities.toolCalling());
        assertTrue(capabilities.parallelToolCalls());
        assertTrue(negotiated.satisfies(new ProviderCapabilities.Requirements(true, true, true, true, true)));
    }

    @Test
    void capsInitialStreamResponseWaitBeforeLongStreamingIdleTimeout() {
        LlmProvider.LlmConfig config = new LlmProvider.LlmConfig(
                "test-key", "https://example.com", "test-model", 32, 0.1, 15_000, 900_000, 2);

        assertEquals(30_000, OpenAiCompatibleProvider.initialStreamResponseTimeoutMs(config));
    }

    @Test
    void preservesInterleavedToolCallsByCallIdAndIndex() throws Exception {
        HttpServer server = startServer(exchange -> {
            exchange.getRequestBody().readAllBytes();
            String listArguments = GSON.toJson(java.util.Map.of("path", "src"));
            String readArguments = GSON.toJson(java.util.Map.of("path", "README"));
            sendSse(exchange,
                    "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"call-read\",\"function\":{\"name\":\"read_file\"}},{\"index\":1,\"id\":\"call-list\",\"function\":{\"name\":\"list_files\"}}]}}]}\n\n" +
                    "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":1,\"function\":{\"arguments\":" + GSON.toJson(listArguments) + "}},{\"index\":0,\"function\":{\"arguments\":" + GSON.toJson(readArguments) + "}}]}}]}\n\n" +
                    "data: [DONE]\n\n");
        });
        try {
            List<LlmProvider.StreamChunk> chunks = new ArrayList<>();
            providerForLocalServer().chatStream("system", List.of(), List.of(), config(server), chunks::add);

            List<LlmProvider.StreamChunk> calls = chunks.stream()
                    .filter(chunk -> "tool_call".equals(chunk.type()))
                    .sorted(Comparator.comparing(LlmProvider.StreamChunk::toolCallIndex))
                    .toList();

            assertEquals(2, calls.size());
            assertEquals("call-read", calls.get(0).toolCallId());
            assertEquals(0, calls.get(0).toolCallIndex());
            assertEquals("read_file", calls.get(0).toolName());
            assertEquals("{\"path\":\"README\"}", calls.get(0).toolArgs());
            assertEquals("call-list", calls.get(1).toolCallId());
            assertEquals(1, calls.get(1).toolCallIndex());
            assertEquals("list_files", calls.get(1).toolName());
            assertEquals("{\"path\":\"src\"}", calls.get(1).toolArgs());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void stripsSplitProtocolTagsFromDedicatedReasoningChannel() throws Exception {
        HttpServer server = startServer(exchange -> {
            exchange.getRequestBody().readAllBytes();
            sendSse(exchange,
                    "data: {\"choices\":[{\"delta\":{\"reasoning_content\":\"<thi\"}}]}\n\n" +
                    "data: {\"choices\":[{\"delta\":{\"reasoning_content\":\"nk>private plan</THINK\"}}]}\n\n" +
                    "data: {\"choices\":[{\"delta\":{\"reasoning_content\":\"ING>\",\"content\":\"visible answer\"}}]}\n\n" +
                    "data: [DONE]\n\n");
        });
        try {
            List<LlmProvider.StreamChunk> chunks = new ArrayList<>();
            providerForLocalServer().chatStream("system", List.of(), List.of(), config(server), chunks::add);

            String thinking = chunks.stream()
                    .filter(chunk -> "thinking_delta".equals(chunk.type()))
                    .map(LlmProvider.StreamChunk::content)
                    .reduce("", String::concat);
            String visible = chunks.stream()
                    .filter(chunk -> "text_delta".equals(chunk.type()))
                    .map(LlmProvider.StreamChunk::content)
                    .reduce("", String::concat);

            assertEquals("private plan", thinking);
            assertEquals("visible answer", visible);
            assertFalse(thinking.toLowerCase().contains("think"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void routesCaseInsensitiveThinkingTagsWithoutExposingDelimiterText() throws Exception {
        HttpServer server = startServer(exchange -> {
            exchange.getRequestBody().readAllBytes();
            sendSse(exchange,
                    "data: {\"choices\":[{\"delta\":{\"content\":\"Visible <TH\"}}]}\n\n" +
                    "data: {\"choices\":[{\"delta\":{\"content\":\"INKING>internal plan</THINKING> answer\"}}]}\n\n" +
                    "data: [DONE]\n\n");
        });
        try {
            List<LlmProvider.StreamChunk> chunks = new ArrayList<>();
            providerForLocalServer().chatStream("system", List.of(), List.of(), config(server), chunks::add);

            String visible = chunks.stream()
                    .filter(chunk -> "text_delta".equals(chunk.type()))
                    .map(LlmProvider.StreamChunk::content)
                    .reduce("", String::concat);
            String thinking = chunks.stream()
                    .filter(chunk -> "thinking_delta".equals(chunk.type()))
                    .map(LlmProvider.StreamChunk::content)
                    .reduce("", String::concat);

            assertEquals("Visible  answer", visible);
            assertEquals("internal plan", thinking);
            assertFalse(chunks.stream().map(LlmProvider.StreamChunk::content)
                    .anyMatch(content -> content != null && content.toLowerCase().contains("<think")));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void normalizesNonStreamingReasoningBeforeReturningProviderResult() throws Exception {
        HttpServer server = startServer(exchange -> {
            exchange.getRequestBody().readAllBytes();
            sendJson(exchange, 200, "{\"choices\":[{\"message\":{"
                    + "\"content\":\"Visible <THINK data-kind='hidden'>private plan</THINKING> answer\","
                    + "\"reasoning_content\":\"&lt;think&gt;explicit plan&lt;/think&gt;\"}}]}");
        });
        try {
            java.util.Map<String, Object> result = providerForLocalServer()
                    .chatWithTools("system", List.of(), List.of(), config(server));

            assertEquals("text", result.get("type"));
            assertEquals("Visible  answer", result.get("content"));
            assertEquals("explicit plan", result.get("thinking"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void neverPromotesReasoningOnlyResponseIntoVisibleContent() throws Exception {
        HttpServer server = startServer(exchange -> {
            exchange.getRequestBody().readAllBytes();
            sendJson(exchange, 200,
                    "{\"choices\":[{\"message\":{\"content\":\"<think>private plan</think>\"}}]}");
        });
        try {
            java.util.Map<String, Object> result = providerForLocalServer()
                    .chatWithTools("system", List.of(), List.of(), config(server));

            assertEquals("text", result.get("type"));
            assertEquals("", result.get("content"));
            assertEquals("private plan", result.get("thinking"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void routesAttributedReasoningTagsSplitAcrossStreamingChunks() throws Exception {
        HttpServer server = startServer(exchange -> {
            exchange.getRequestBody().readAllBytes();
            sendSse(exchange,
                    "data: {\"choices\":[{\"delta\":{\"content\":\"Visible <TH\"}}]}\n\n" +
                    "data: {\"choices\":[{\"delta\":{\"content\":\"INK data-kind='hidden'>private plan</THINK\"}}]}\n\n" +
                    "data: {\"choices\":[{\"delta\":{\"content\":\"ING> answer\"}}]}\n\n" +
                    "data: [DONE]\n\n");
        });
        try {
            List<LlmProvider.StreamChunk> chunks = new ArrayList<>();
            providerForLocalServer().chatStream("system", List.of(), List.of(), config(server), chunks::add);

            String visible = chunks.stream()
                    .filter(chunk -> "text_delta".equals(chunk.type()))
                    .map(LlmProvider.StreamChunk::content)
                    .reduce("", String::concat);
            String thinking = chunks.stream()
                    .filter(chunk -> "thinking_delta".equals(chunk.type()))
                    .map(LlmProvider.StreamChunk::content)
                    .reduce("", String::concat);

            assertEquals("Visible  answer", visible);
            assertEquals("private plan", thinking);
            assertFalse(chunks.stream().map(LlmProvider.StreamChunk::content)
                    .anyMatch(content -> content != null && content.toLowerCase().contains("think")));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void disablesParallelToolCallsInOpenAiCompatibleRequests() throws Exception {
        HttpServer server = startServer(exchange -> {
            String request = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(request.contains("\"parallel_tool_calls\":false"));
            sendSse(exchange, "data: {\"choices\":[{\"delta\":{\"content\":\"ok\"}}]}\n\ndata: [DONE]\n\n");
        });
        try {
            List<LlmProvider.StreamChunk> chunks = new ArrayList<>();
            providerForLocalServer().chatStream("system", List.of(),
                    List.of(java.util.Map.of("type", "function", "function", java.util.Map.of("name", "read_file"))),
                    config(server), chunks::add);
            assertTrue(chunks.stream().anyMatch(chunk -> "text_delta".equals(chunk.type())));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void omitsPromptCacheKeyUnlessTheModelConfigurationExplicitlyEnablesIt() throws Exception {
        HttpServer server = startServer(exchange -> {
            String request = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            assertFalse(request.contains("\"prompt_cache_key\""));
            sendSse(exchange, "data: {\"choices\":[{\"delta\":{\"content\":\"ok\"}}]}\n\ndata: [DONE]\n\n");
        });
        try {
            providerForLocalServer().chatStream("system", List.of(), List.of(), config(server), ignored -> { });
        } finally {
            server.stop(0);
        }
    }

    @Test
    void sendsPromptCacheKeyWhenTheModelConfigurationExplicitlyEnablesIt() throws Exception {
        HttpServer server = startServer(exchange -> {
            String request = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(request.contains("\"prompt_cache_key\":\"labex-cache-key\""));
            sendSse(exchange, "data: {\"choices\":[{\"delta\":{\"content\":\"ok\"}}]}\n\ndata: [DONE]\n\n");
        });
        try {
            LlmProvider.LlmConfig enabled = promptCacheConfig(server);
            providerForLocalServer().chatStream("system", List.of(), List.of(), enabled, ignored -> { });
        } finally {
            server.stop(0);
        }
    }

    @Test
    void retriesWithoutPromptCacheKeyWhenAnOptedInCompatibleGatewayRejectsTheField() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        List<String> bodies = new ArrayList<>();
        HttpServer server = startServer(exchange -> {
            String request = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            bodies.add(request);
            if (requests.incrementAndGet() == 1) {
                sendJson(exchange, 400, "{\"error\":{\"message\":\"unknown field prompt_cache_key\"}}");
                return;
            }
            sendSse(exchange, "data: {\"choices\":[{\"delta\":{\"content\":\"ok\"}}]}\n\ndata: [DONE]\n\n");
        });
        try {
            List<LlmProvider.StreamChunk> chunks = new ArrayList<>();
            providerForLocalServer().chatStream("system", List.of(), List.of(),
                    promptCacheConfig(server), chunks::add);
            assertEquals(2, requests.get());
            assertTrue(bodies.get(0).contains("\"prompt_cache_key\""));
            assertFalse(bodies.get(1).contains("\"prompt_cache_key\""));
            assertTrue(chunks.stream().anyMatch(chunk -> "text_delta".equals(chunk.type())));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void normalizesTextReasoningUsageAndTypedProviderFailures() throws Exception {
        HttpServer server = startServer(exchange -> {
            exchange.getRequestBody().readAllBytes();
            sendSse(exchange,
                    "data: {\"choices\":[{\"delta\":{\"reasoning_content\":\"plan first\",\"content\":\"hello\"}}]}\n\n" +
                    "data: {\"choices\":[],\"usage\":{\"prompt_tokens\":3,\"completion_tokens\":2,\"total_tokens\":5}}\n\n" +
                    "data: [DONE]\n\n");
        });
        try {
            List<LlmProvider.StreamChunk> chunks = new ArrayList<>();
            providerForLocalServer().chatStream("system", List.of(), List.of(), config(server), chunks::add);

            assertTrue(chunks.stream().anyMatch(chunk -> chunk.eventType() == ProviderEventType.THINKING_DELTA));
            assertTrue(chunks.stream().anyMatch(chunk -> chunk.eventType() == ProviderEventType.TEXT_DELTA));
            assertTrue(chunks.stream().anyMatch(chunk -> chunk.eventType() == ProviderEventType.USAGE));
        } finally {
            server.stop(0);
        }

        HttpServer errorServer = startServer(exchange -> {
            exchange.getRequestBody().readAllBytes();
            sendJson(exchange, 401, "{\"error\":{\"message\":\"bad key\"}}");
        });
        try {
            List<LlmProvider.StreamChunk> chunks = new ArrayList<>();
            providerForLocalServer().chatStream("system", List.of(), List.of(), config(errorServer), chunks::add);
            LlmProvider.StreamChunk failure = chunks.stream()
                    .filter(chunk -> chunk.eventType() == ProviderEventType.ERROR)
                    .findFirst()
                    .orElseThrow();
            assertEquals(ProviderFailureType.AUTHENTICATION, failure.failure().type());
            assertFalse(failure.failure().retryable());
        } finally {
            errorServer.stop(0);
        }
    }

    @Test
    void retriesRateLimitedRequestsBeforeAnyStreamEvent() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        HttpServer server = startServer(exchange -> {
            exchange.getRequestBody().readAllBytes();
            if (requests.incrementAndGet() == 1) {
                sendJson(exchange, 429, "{\"error\":{\"message\":\"rate limited\"}}");
                return;
            }
            sendSse(exchange, "data: {\"choices\":[{\"delta\":{\"content\":\"ok\"}}]}\n\ndata: [DONE]\n\n");
        });
        try {
            List<LlmProvider.StreamChunk> chunks = new ArrayList<>();
            LlmProvider.LlmConfig config = new LlmProvider.LlmConfig(
                    "test-key", baseUrl(server), "test-model", 32, 0.1, 1_000, 1_000, 1);

            providerForLocalServer().chatStream("system", List.of(), List.of(), config, chunks::add);

            assertEquals(2, requests.get());
            assertTrue(chunks.stream().anyMatch(chunk -> "text_delta".equals(chunk.type())));
            assertFalse(chunks.stream().anyMatch(chunk -> "error".equals(chunk.type())));
        } finally {
            server.stop(0);
        }
    }

    private static LlmProvider.LlmConfig config(HttpServer server) {
        return new LlmProvider.LlmConfig("test-key", baseUrl(server), "test-model", 32, 0.1, 1_000, 1_000, 0);
    }

    private static LlmProvider.LlmConfig promptCacheConfig(HttpServer server) {
        return new LlmProvider.LlmConfig(
                "test-key", baseUrl(server), "test-model", 32, 0.1, 1_000, 1_000, 0, true, "labex-cache-key");
    }

    private static HttpServer startServer(ExchangeHandler handler) throws IOException {
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

    private static OpenAiCompatibleProvider providerForLocalServer() {
        return new OpenAiCompatibleProvider(new OutboundUrlPolicy(host ->
                new InetAddress[] {InetAddress.getByName("8.8.8.8")}));
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

    private static void sendJson(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }

    @FunctionalInterface
    private interface ExchangeHandler {
        void handle(HttpExchange exchange) throws IOException;
    }
}
