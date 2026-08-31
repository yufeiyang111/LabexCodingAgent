package com.labex.labexagent.llm;

import com.labex.labexagent.network.OutboundUrlPolicy;
import com.labex.labexagent.runtime.AgentCancellationRegistry;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class OpenAiCompatibleProviderUsageTest {

    @Test
    void chatStreamRequestsAndEmitsOfficialUsage() throws Exception {
        AtomicReference<String> requestBody = new AtomicReference<>("");
        HttpServer server = startServer(exchange -> {
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            sendSse(exchange,
                    "data: {\"choices\":[{\"delta\":{\"content\":\"hello\"}}],\"usage\":null}\n\n" +
                    "data: {\"choices\":[],\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":2,\"total_tokens\":12,\"prompt_tokens_details\":{\"cached_tokens\":4}}}\n\n" +
                    "data: [DONE]\n\n");
        });
        try {
            OpenAiCompatibleProvider provider = providerForLocalServer();
            List<LlmProvider.StreamChunk> chunks = new ArrayList<>();

            provider.chatStream("system", List.of(Map.of("role", "user", "content", "hi")), List.of(),
                    new LlmProvider.LlmConfig("test-key", baseUrl(server), "test-model", 32, 0.1),
                    chunks::add);

            assertTrue(requestBody.get().contains("\"stream_options\""));
            assertTrue(requestBody.get().contains("\"include_usage\":true"));

            LlmProvider.StreamChunk usageChunk = chunks.stream()
                    .filter(chunk -> "usage".equals(chunk.type()))
                    .findFirst()
                    .orElseThrow();
            assertEquals(10, usageChunk.usage().get("prompt_tokens"));
            assertEquals(2, usageChunk.usage().get("completion_tokens"));
            assertEquals(12, usageChunk.usage().get("total_tokens"));
            assertEquals(4, usageChunk.usage().get("cached_tokens"));
            assertEquals(4, usageChunk.usage().get("cache_hit_tokens"));
            assertEquals(6, usageChunk.usage().get("cache_miss_tokens"));
            assertEquals(true, usageChunk.usage().get("cache_usage_reported"));

            LlmProvider.StreamChunk doneChunk = chunks.stream()
                    .filter(LlmProvider.StreamChunk::done)
                    .findFirst()
                    .orElseThrow();
            assertEquals(usageChunk.usage(), doneChunk.usage());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void marksCacheUsageAsNotReportedWhenProviderOmitsCacheFields() throws Exception {
        HttpServer server = startServer(exchange -> {
            exchange.getRequestBody().readAllBytes();
            sendSse(exchange,
                    "data: {\"choices\":[],\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":2,\"total_tokens\":12}}\n\n" +
                    "data: [DONE]\n\n");
        });
        try {
            List<LlmProvider.StreamChunk> chunks = new ArrayList<>();
            providerForLocalServer().chatStream("system", List.of(), List.of(),
                    new LlmProvider.LlmConfig("test-key", baseUrl(server), "test-model", 32, 0.1), chunks::add);
            LlmProvider.StreamChunk usage = chunks.stream()
                    .filter(chunk -> "usage".equals(chunk.type())).findFirst().orElseThrow();
            assertEquals(false, usage.usage().get("cache_usage_reported"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void chatStreamFallsBackWhenProviderRejectsStreamOptions() throws Exception {
        AtomicReference<String> firstBody = new AtomicReference<>("");
        AtomicReference<String> secondBody = new AtomicReference<>("");
        int[] calls = {0};
        HttpServer server = startServer(exchange -> {
            calls[0]++;
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            if (calls[0] == 1) {
                firstBody.set(body);
                sendJson(exchange, 400, "{\"error\":{\"message\":\"unknown field stream_options\"}}");
                return;
            }
            secondBody.set(body);
            sendSse(exchange,
                    "data: {\"choices\":[{\"delta\":{\"content\":\"ok\"}}]}\n\n" +
                    "data: [DONE]\n\n");
        });
        try {
            OpenAiCompatibleProvider provider = providerForLocalServer();
            List<LlmProvider.StreamChunk> chunks = new ArrayList<>();

            provider.chatStream("system", List.of(Map.of("role", "user", "content", "hi")), List.of(),
                    new LlmProvider.LlmConfig("test-key", baseUrl(server), "test-model", 32, 0.1),
                    chunks::add);

            assertEquals(2, calls[0]);
            assertTrue(firstBody.get().contains("\"stream_options\""));
            assertFalse(secondBody.get().contains("\"stream_options\""));
            assertTrue(chunks.stream().noneMatch(chunk -> "error".equals(chunk.type())));
            assertTrue(chunks.stream().anyMatch(chunk -> "text_delta".equals(chunk.type())));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void chatWithToolsRejectsCloudMetadataEndpointBeforeOpeningConnection() {
        OpenAiCompatibleProvider provider = new OpenAiCompatibleProvider();

        Map<String, Object> response = provider.chatWithTools(
                "system", List.of(Map.of("role", "user", "content", "hi")), List.of(),
                new LlmProvider.LlmConfig("test-key", "http://metadata.google.internal/v1", "test-model", 32, 0.1));

        assertEquals("error", response.get("type"));
        assertTrue(String.valueOf(response.get("message")).toLowerCase().contains("blocked"));
    }

    @Test
    void chatStreamRejectsCloudMetadataEndpointBeforeOpeningConnection() {
        OpenAiCompatibleProvider provider = new OpenAiCompatibleProvider();
        List<LlmProvider.StreamChunk> chunks = new ArrayList<>();

        provider.chatStream("system", List.of(Map.of("role", "user", "content", "hi")), List.of(),
                new LlmProvider.LlmConfig("test-key", "http://metadata.google.internal/v1", "test-model", 32, 0.1),
                chunks::add);

        assertEquals(1, chunks.size());
        assertEquals("error", chunks.get(0).type());
        assertTrue(chunks.get(0).content().toLowerCase().contains("blocked"));
    }

    @Test
    void chatStreamDisconnectsWhenCancellationIsRequested() throws Exception {
        CountDownLatch streamOpened = new CountDownLatch(1);
        CountDownLatch releaseServer = new CountDownLatch(1);
        HttpServer server = startServer(exchange -> {
            exchange.getRequestBody().readAllBytes();
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            exchange.getResponseBody().flush();
            streamOpened.countDown();
            try {
                releaseServer.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            OpenAiCompatibleProvider provider = providerForLocalServer();
            AgentCancellationRegistry registry = new AgentCancellationRegistry();
            AgentCancellationRegistry.ActiveRun run = registry.register("session-4", 7, 12, 48L);
            List<LlmProvider.StreamChunk> chunks = new ArrayList<>();

            Future<?> stream = executor.submit(() -> provider.chatStream(
                    "system", List.of(Map.of("role", "user", "content", "hi")), List.of(),
                    new LlmProvider.LlmConfig("test-key", baseUrl(server), "test-model", 32, 0.1),
                    run,
                    chunks::add));

            assertTrue(streamOpened.await(1, TimeUnit.SECONDS));
            registry.cancel("session-4", 7, 12);

            stream.get(2, TimeUnit.SECONDS);
            assertTrue(chunks.stream().anyMatch(chunk -> "cancelled".equals(chunk.type())));
        } finally {
            releaseServer.countDown();
            executor.shutdownNow();
            server.stop(0);
        }
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

    private static String baseUrl(HttpServer server) {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private static OpenAiCompatibleProvider providerForLocalServer() {
        return new OpenAiCompatibleProvider(new OutboundUrlPolicy(host ->
                new InetAddress[] {InetAddress.getByName("8.8.8.8")}));
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
