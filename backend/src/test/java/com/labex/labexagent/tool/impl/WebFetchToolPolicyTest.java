package com.labex.labexagent.tool.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.google.gson.JsonObject;
import com.labex.labexagent.network.OutboundUrlPolicy;
import com.labex.labexagent.network.WebFetchProperties;
import com.labex.labexagent.tool.ToolResult;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class WebFetchToolPolicyTest {

    @Test
    void marksTheProductionConstructorForSpringInjection() throws Exception {
        assertTrue(WebFetchTool.class
                .getConstructor(OutboundUrlPolicy.class, WebFetchProperties.class)
                .isAnnotationPresent(Autowired.class));
    }

    @Test
    void rejectsInvalidUrlSchemes() {
        OutboundUrlPolicy policy = new OutboundUrlPolicy(host -> new InetAddress[] {InetAddress.getLoopbackAddress()});
        WebFetchTool tool = new WebFetchTool(policy, new WebFetchProperties());
        JsonObject args = new JsonObject();
        args.addProperty("url", "ftp://example.com/file");

        ToolResult result = tool.execute(null, args);

        assertFalse(result.isSuccess());
        assertTrue(result.getContent().contains("url must start with http:// or https://"));
    }

    @Test
    void rejectsBlockedDestinationsBeforeSendingTheFetchRequest() {
        OutboundUrlPolicy policy = new OutboundUrlPolicy(host ->
                new InetAddress[] {InetAddress.getByName("127.0.0.1")});
        WebFetchTool tool = new WebFetchTool(policy, new WebFetchProperties());
        JsonObject args = new JsonObject();
        args.addProperty("url", "http://private.example.test/internal");

        ToolResult result = tool.execute(null, args);

        assertFalse(result.isSuccess());
        assertTrue(result.getContent().contains("Outbound request blocked"));
    }

    @Test
    void rejectsDeclaredOversizedResponseBeforeReadingItsBody() {
        InputStream body = new InputStream() {
            @Override
            public int read() {
                throw new AssertionError("Declared oversized response must not be read");
            }
        };

        WebFetchTool.ResponseTooLargeException failure = assertThrows(
                WebFetchTool.ResponseTooLargeException.class,
                () -> WebFetchTool.readBoundedResponse(body, 9L, 8));

        assertTrue(failure.getMessage().contains("maximum size"));
    }

    @Test
    void rejectsUnknownLengthResponseAfterTheConfiguredByteBoundary() {
        byte[] response = "123456789".getBytes(StandardCharsets.UTF_8);

        assertThrows(WebFetchTool.ResponseTooLargeException.class,
                () -> WebFetchTool.readBoundedResponse(new ByteArrayInputStream(response), -1L, 8));
    }

    @Test
    void preservesAResponseThatFitsTheConfiguredByteBoundary() throws Exception {
        assertEquals("hello", WebFetchTool.readBoundedResponse(
                new ByteArrayInputStream("hello".getBytes(StandardCharsets.UTF_8)), 5L, 5));
    }

    @Test
    void fallsBackToReaderWhenDirectConnectTimesOut() throws Exception {
        OutboundUrlPolicy policy = new OutboundUrlPolicy(host -> new InetAddress[] {InetAddress.getByName("93.184.216.34")});
        WebFetchProperties properties = new WebFetchProperties();
        properties.setReaderFallbackEnabled(true);
        properties.setReaderEndpoint("https://r.jina.ai/");

        HttpClient mockClient = mock(HttpClient.class);
        @SuppressWarnings("unchecked")
        HttpResponse<InputStream> mockResponse = mock(HttpResponse.class);
        when(mockResponse.statusCode()).thenReturn(200);
        when(mockResponse.headers()).thenReturn(HttpHeaders.of(
                Map.of("Content-Type", List.of("text/markdown")), (k, v) -> true));
        when(mockResponse.body()).thenReturn(new ByteArrayInputStream("# Sample Title\nContent from fallback".getBytes(StandardCharsets.UTF_8)));

        // 模拟第一次直连超时，第二次通过 reader 获取成功
        when(mockClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenAnswer(invocation -> {
                    HttpRequest req = invocation.getArgument(0);
                    if (req.uri().toString().contains("r.jina.ai")) {
                        return mockResponse;
                    }
                    throw new HttpConnectTimeoutException("HTTP connect timed out");
                });

        WebFetchTool tool = new WebFetchTool(policy, properties, mockClient);
        JsonObject args = new JsonObject();
        args.addProperty("url", "https://example.com/docs");

        ToolResult result = tool.execute(null, args);

        assertTrue(result.isSuccess());
        assertTrue(result.getContent().contains("(via reader fallback)"));
        assertTrue(result.getContent().contains("Sample Title"));
    }

    @Test
    void doesNotTriggerReaderFallbackWhenReaderDisabled() throws Exception {
        OutboundUrlPolicy policy = new OutboundUrlPolicy(host -> new InetAddress[] {InetAddress.getByName("93.184.216.34")});
        WebFetchProperties properties = new WebFetchProperties();
        properties.setReaderFallbackEnabled(false);

        HttpClient mockClient = mock(HttpClient.class);
        when(mockClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenThrow(new HttpConnectTimeoutException("HTTP connect timed out"));

        WebFetchTool tool = new WebFetchTool(policy, properties, mockClient);
        JsonObject args = new JsonObject();
        args.addProperty("url", "https://example.com/docs");

        ToolResult result = tool.execute(null, args);

        assertFalse(result.isSuccess());
        assertTrue(result.getContent().contains("HTTP connect timed out"));
        assertFalse(result.getContent().contains("via reader fallback"));
    }
}
