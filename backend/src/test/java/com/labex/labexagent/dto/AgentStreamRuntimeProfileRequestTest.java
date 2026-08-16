package com.labex.labexagent.dto;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class AgentStreamRuntimeProfileRequestTest {

    @Test
    void carriesTheSelectedRuntimeProfileAcrossThePublicRequestBoundary() {
        AgentStreamHttpRequest httpRequest = new AgentStreamHttpRequest();
        httpRequest.setRuntimeProfile("labex-native");

        AgentStreamRequest internalRequest = httpRequest.toInternalRequest();

        assertEquals("labex-native", httpRequest.getRuntimeProfile());
        assertEquals("labex-native", internalRequest.getRuntimeProfile());
    }
}
