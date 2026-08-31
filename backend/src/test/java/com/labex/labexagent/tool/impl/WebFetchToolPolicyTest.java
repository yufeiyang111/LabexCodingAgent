package com.labex.labexagent.tool.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.labex.labexagent.network.OutboundUrlPolicy;
import com.labex.labexagent.network.WebFetchProperties;
import com.labex.labexagent.tool.ToolResult;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
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
}
