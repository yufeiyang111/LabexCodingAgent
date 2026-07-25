package com.labex.labexagent.permission;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;

class PermissionServiceCancellationTest {

    @Test
    void durablePermissionServiceHasNoInProcessFutureWaiter() {
        assertThat(Arrays.stream(PermissionService.class.getDeclaredMethods())
                .map(method -> method.getName()))
                .doesNotContain("awaitApproval");
        assertThat(Arrays.stream(PermissionService.class.getDeclaredFields())
                .map(field -> field.getType().getName()))
                .doesNotContain(CompletableFuture.class.getName());
    }
}
