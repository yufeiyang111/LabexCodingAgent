package com.labex.labexagent.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;

class AgentInteractionServiceCancellationTest {

    @Test
    void durableQuestionServiceHasNoInProcessFutureWaiter() {
        assertThat(Arrays.stream(AgentInteractionService.class.getDeclaredMethods())
                .map(method -> method.getName()))
                .doesNotContain("awaitQuestion");
        assertThat(Arrays.stream(AgentInteractionService.class.getDeclaredFields())
                .map(field -> field.getType().getName()))
                .doesNotContain(CompletableFuture.class.getName());
    }
}
