package com.labex.labexagent.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class AgentToolDiscoveryPropertiesTest {

    @Test
    void clampsReadFileCandidateLimitToTheDocumentedSafeRange() {
        AgentToolDiscoveryProperties properties = new AgentToolDiscoveryProperties();

        properties.setReadFileCandidateLimit(0);
        assertEquals(1, properties.getReadFileCandidateLimit());

        properties.setReadFileCandidateLimit(99);
        assertEquals(10, properties.getReadFileCandidateLimit());
    }
}
