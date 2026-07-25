package com.labex.labexagent.tool.impl;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.labex.labexagent.network.OutboundUrlPolicy;
import com.labex.labexagent.tool.ToolResult;
import java.net.InetAddress;
import org.springframework.beans.factory.annotation.Autowired;
import org.junit.jupiter.api.Test;

class WebFetchToolPolicyTest {

    @Test
    void marksTheProductionConstructorForSpringInjection() throws Exception {
        assertTrue(WebFetchTool.class
                .getConstructor(OutboundUrlPolicy.class)
                .isAnnotationPresent(Autowired.class));
    }

    @Test
    void rejectsBlockedDestinationsBeforeSendingTheFetchRequest() throws Exception {
        OutboundUrlPolicy policy = new OutboundUrlPolicy(host ->
                new InetAddress[] {InetAddress.getByName("127.0.0.1")});
        WebFetchTool tool = new WebFetchTool(policy);
        JsonObject args = new JsonObject();
        args.addProperty("url", "http://private.example.test/internal");

        ToolResult result = tool.execute(null, args);

        assertFalse(result.isSuccess());
        assertTrue(result.getContent().contains("Outbound request blocked"));
    }
}
