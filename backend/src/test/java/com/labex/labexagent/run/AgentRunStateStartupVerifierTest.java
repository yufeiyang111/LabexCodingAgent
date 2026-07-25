package com.labex.labexagent.run;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

import org.junit.jupiter.api.Test;

class AgentRunStateStartupVerifierTest {

    @Test
    void forcesStateMachineLinkageDuringStartup() {
        assertDoesNotThrow(() -> new AgentRunStateStartupVerifier().run(null));
    }
}
