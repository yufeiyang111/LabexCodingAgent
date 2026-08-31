package com.labex.labexagent.workspace;

import com.labex.auth.redis.AuthRedisStore;
import com.labex.auth.redis.AuthRedisUnavailableException;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 用户侧工作区文件操作的统一限流与并发守卫。
 * 频控优先复用认证域 Redis 原子计数（跨实例生效）；Redis 暂不可用时退化为
 * 进程内固定窗口计数（单实例仍受控，多实例短暂放宽），不因限流器故障阻断正常使用。
 * 并发槽是全局信号量而非按用户分配：防止用户数增长带来的内存泄漏，
 * 每用户公平性由分钟级频控保证。
 */
@Service
public class WorkspaceOperationGuard {
    public static final String REASON_RATE_LIMITED = "RATE_LIMITED";
    public static final String REASON_BUSY = "BUSY";

    private static final Logger log = LoggerFactory.getLogger(WorkspaceOperationGuard.class);
    private static final String RATE_KEY_PREFIX = "labex:fileops:rate:";
    private static final Duration RATE_WINDOW = Duration.ofMinutes(1);
    private static final int LOCAL_COUNTER_MAX_ENTRIES = 20_000;

    private final AuthRedisStore redisStore;
    private final WorkspaceFileOperationProperties properties;
    private final Map<String, AtomicLong> localWindows = new ConcurrentHashMap<>();
    private final Map<FileOp, Semaphore> slots = new ConcurrentHashMap<>();

    public WorkspaceOperationGuard(AuthRedisStore redisStore, WorkspaceFileOperationProperties properties) {
        this.redisStore = redisStore;
        this.properties = properties;
        for (FileOp op : FileOp.values()) {
            int permits = op.concurrency(properties);
            if (permits > 0) {
                slots.put(op, new Semaphore(permits));
            }
        }
    }

    /** 校验频控额度；超限抛出带 {@link #REASON_RATE_LIMITED} 的拒绝异常。 */
    public void checkRate(FileOp op, Integer studentId) {
        String discriminator = studentId == null ? "anonymous" : String.valueOf(studentId);
        long count;
        try {
            count = redisStore.increment(RATE_KEY_PREFIX + op.name().toLowerCase() + ":" + discriminator, RATE_WINDOW);
        } catch (AuthRedisUnavailableException unavailable) {
            count = incrementLocal(op, discriminator);
        }
        if (count > Math.max(1, op.ratePerMinute(properties))) {
            throw new FileOpsRejectedException(REASON_RATE_LIMITED,
                    "操作过于频繁，请稍后再试");
        }
    }

    /** 尝试占用一个全局并发槽；无空闲槽时抛出带 {@link #REASON_BUSY} 的拒绝异常。 */
    public Slot acquireSlot(FileOp op) {
        Semaphore semaphore = slots.get(op);
        if (semaphore == null) {
            return () -> { };
        }
        if (!semaphore.tryAcquire()) {
            throw new FileOpsRejectedException(REASON_BUSY, "当前操作繁忙，请稍后重试");
        }
        return semaphore::release;
    }

    private long incrementLocal(FileOp op, String discriminator) {
        long window = Instant.now().getEpochSecond() / RATE_WINDOW.toSeconds();
        String key = op.name().toLowerCase() + ":" + discriminator + ":" + window;
        AtomicLong counter = localWindows.computeIfAbsent(key, ignored -> new AtomicLong());
        if (localWindows.size() > LOCAL_COUNTER_MAX_ENTRIES) {
            evictStaleWindows(window);
        }
        return counter.incrementAndGet();
    }

    private void evictStaleWindows(long currentWindow) {
        String suffix = ":" + currentWindow;
        localWindows.keySet().removeIf(key -> !key.endsWith(suffix));
        log.warn("FILEOPS_RATE_FALLBACK size_after_evict={}", localWindows.size());
    }

    /** 并发槽句柄；关闭时归还。 */
    public interface Slot extends AutoCloseable {
        @Override
        void close();
    }

    public enum FileOp {
        UPLOAD, TRANSFER, SEARCH, IMAGE, DOWNLOAD, HISTORY, EXPORT;

        int ratePerMinute(WorkspaceFileOperationProperties properties) {
            return switch (this) {
                case UPLOAD -> properties.getUploadRatePerMinute();
                case TRANSFER -> properties.getTransferRatePerMinute();
                case SEARCH -> properties.getSearchRatePerMinute();
                case IMAGE -> properties.getImageRatePerMinute();
                case DOWNLOAD -> properties.getDownloadRatePerMinute();
                case EXPORT -> properties.getExportRatePerMinute();
                case HISTORY -> Integer.MAX_VALUE;
            };
        }

        int concurrency(WorkspaceFileOperationProperties properties) {
            return switch (this) {
                case TRANSFER -> properties.getTransferConcurrency();
                case SEARCH -> properties.getSearchConcurrency();
                case DOWNLOAD -> properties.getDownloadConcurrency();
                case EXPORT -> properties.getExportConcurrency();
                default -> 0;
            };
        }
    }

    /** 限流/并发超限的统一拒绝异常；code 用于前端区分提示。 */
    public static class FileOpsRejectedException extends RuntimeException {
        private final String reason;

        public FileOpsRejectedException(String reason, String message) {
            super(message);
            this.reason = reason;
        }

        public String getReason() {
            return reason;
        }
    }
}
