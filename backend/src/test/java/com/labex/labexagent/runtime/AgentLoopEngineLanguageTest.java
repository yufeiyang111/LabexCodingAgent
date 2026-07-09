package com.labex.labexagent.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;

class AgentLoopEngineLanguageTest {
    @Test
    void finalResponseSummaryFollowsVisibleLanguage() throws Exception {
        AgentLoopEngine engine = new AgentLoopEngine(
                null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null,
                null);
        Method method = AgentLoopEngine.class.getDeclaredMethod("finalResponseSummary", String.class);
        method.setAccessible(true);

        assertThat(method.invoke(engine, "zh")).isEqualTo("已生成最终回答");
        assertThat(method.invoke(engine, "en")).isEqualTo("Generated final response");
    }
}
