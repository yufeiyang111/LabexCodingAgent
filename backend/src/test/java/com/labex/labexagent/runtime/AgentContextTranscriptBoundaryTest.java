package com.labex.labexagent.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Field;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

class AgentContextTranscriptBoundaryTest {
    @Test
    void runtimeContextDoesNotRetainAnInMemoryProviderTranscript() {
        assertThat(Arrays.stream(AgentContext.class.getDeclaredFields())
                .map(Field::getName))
                .doesNotContain("transcript");
    }
}
