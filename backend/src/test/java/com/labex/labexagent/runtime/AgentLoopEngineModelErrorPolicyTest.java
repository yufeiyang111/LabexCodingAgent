package com.labex.labexagent.runtime;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;

class AgentLoopEngineModelErrorPolicyTest {

    @Test
    void doesNotHideRateLimitFailuresBehindOuterAgentRetries() throws Exception {
        Method method = AgentLoopEngine.class.getDeclaredMethod("isRecoverableModelError", String.class);
        method.setAccessible(true);
        AgentLoopEngine engine = newEngine();

        assertFalse((Boolean) method.invoke(engine, "HTTP 429 Too Many Requests (rate limited): retry later"));
        assertTrue((Boolean) method.invoke(engine, "HTTP 503 Service Unavailable"));
    }

    private AgentLoopEngine newEngine() throws Exception {
        Constructor<?> constructor = AgentLoopEngine.class.getConstructors()[0];
        return (AgentLoopEngine) constructor.newInstance(new Object[constructor.getParameterCount()]);
    }
}
