package com.labex.labexagent.workspace;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.labex.auth.redis.AuthRedisStore;
import com.labex.auth.redis.AuthRedisUnavailableException;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class WorkspaceOperationGuardTest {

    private AuthRedisStore redisStore;
    private WorkspaceFileOperationProperties properties;
    private WorkspaceOperationGuard guard;

    @BeforeEach
    void setUp() {
        redisStore = mock(AuthRedisStore.class);
        properties = new WorkspaceFileOperationProperties();
        guard = new WorkspaceOperationGuard(redisStore, properties);
    }

    @Test
    void allowsTrafficBelowRateLimitAndRejectsAboveIt() {
        // 第一次调用计数=1（放行），第二次返回 limit+1（拒绝）。
        when(redisStore.increment(anyString(), any(Duration.class)))
                .thenReturn(1L)
                .thenReturn((long) properties.getUploadRatePerMinute() + 1L);

        assertDoesNotThrow(() -> guard.checkRate(WorkspaceOperationGuard.FileOp.UPLOAD, 42));
        var rejected = assertThrows(WorkspaceOperationGuard.FileOpsRejectedException.class,
                () -> guard.checkRate(WorkspaceOperationGuard.FileOp.UPLOAD, 42));

        assertEquals(WorkspaceOperationGuard.REASON_RATE_LIMITED, rejected.getReason());
    }

    @Test
    void fallsBackToLocalWindowWhenRedisIsUnavailable() {
        when(redisStore.increment(anyString(), any(Duration.class)))
                .thenThrow(new AuthRedisUnavailableException(new RuntimeException("redis down")));

        for (int i = 0; i < properties.getUploadRatePerMinute(); i++) {
            assertDoesNotThrow(() -> guard.checkRate(WorkspaceOperationGuard.FileOp.UPLOAD, 7),
                    "第 " + (i + 1) + " 次不应被限流");
        }
        var rejected = assertThrows(WorkspaceOperationGuard.FileOpsRejectedException.class,
                () -> guard.checkRate(WorkspaceOperationGuard.FileOp.UPLOAD, 7));
        assertEquals(WorkspaceOperationGuard.REASON_RATE_LIMITED, rejected.getReason());
    }

    @Test
    void rejectsConcurrentTransferWhenGlobalSlotExhausted() {
        // 并发槽在 guard 构造时按配置创建，必须先改配置再建实例。
        properties.setTransferConcurrency(1);
        WorkspaceOperationGuard singleSlotGuard = new WorkspaceOperationGuard(redisStore, properties);

        try (WorkspaceOperationGuard.Slot first = singleSlotGuard.acquireSlot(WorkspaceOperationGuard.FileOp.TRANSFER)) {
            var busy = assertThrows(WorkspaceOperationGuard.FileOpsRejectedException.class,
                    () -> singleSlotGuard.acquireSlot(WorkspaceOperationGuard.FileOp.TRANSFER));
            assertEquals(WorkspaceOperationGuard.REASON_BUSY, busy.getReason());
        }
        assertDoesNotThrow(() -> singleSlotGuard.acquireSlot(WorkspaceOperationGuard.FileOp.TRANSFER).close());
    }

    @Test
    void operationsWithoutConcurrencyLimitAlwaysAcquire() {
        assertDoesNotThrow(() -> guard.acquireSlot(WorkspaceOperationGuard.FileOp.IMAGE).close());
        assertDoesNotThrow(() -> guard.acquireSlot(WorkspaceOperationGuard.FileOp.UPLOAD).close());
    }
}
