package com.labex.monitor.access;

import com.labex.entity.AccessLog;
import com.labex.mapper.AccessAggMarkerMapper;
import com.labex.mapper.AccessLogMapper;
import com.labex.mapper.AccessStatsMapper;
import com.labex.monitor.config.MonitorProperties;
import jakarta.annotation.PreDestroy;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.IntSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 访问明细异步落库、小时聚合（幂等补跑）与过期清理。 */
@Service
public class AccessLogService {

    private static final Logger log = LoggerFactory.getLogger(AccessLogService.class);

    private final AccessLogMapper accessLogMapper;
    private final AccessStatsMapper accessStatsMapper;
    private final AccessAggMarkerMapper markerMapper;
    private final MonitorProperties properties;
    private final AtomicLong droppedCounter = new AtomicLong();

    private final ExecutorService executor = new ThreadPoolExecutor(
            1, 2, 60, TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(10_000),
            runnable -> {
                Thread thread = new Thread(runnable, "labex-access-log");
                thread.setDaemon(true);
                return thread;
            },
            rejectedHandler());

    public AccessLogService(AccessLogMapper accessLogMapper, AccessStatsMapper accessStatsMapper,
                            AccessAggMarkerMapper markerMapper, MonitorProperties properties) {
        this.accessLogMapper = accessLogMapper;
        this.accessStatsMapper = accessStatsMapper;
        this.markerMapper = markerMapper;
        this.properties = properties;
    }

    /** 异步写入明细；队列满时丢弃并计数，绝不阻塞业务请求。 */
    public void record(AccessLog entry) {
        executor.execute(() -> {
            try {
                accessLogMapper.insert(entry);
            } catch (RuntimeException failure) {
                log.warn("写入访问明细失败: {}", failure.getMessage());
            }
        });
    }

    /** 从最近一个完整小时往回补跑聚合，遇到已聚合小时即停止（正常逐小时调度为 O(1)）。 */
    public void aggregatePastHours() {
        LocalDateTime lastHour = LocalDateTime.now().truncatedTo(ChronoUnit.HOURS).minusHours(1);
        LocalDateTime oldest = lastHour.minusHours(properties.getAggCatchupMaxHours());
        LocalDateTime cursor = lastHour;
        int processed = 0;
        while (cursor.isAfter(oldest) && processed < properties.getAggCatchupMaxHours()) {
            boolean aggregated;
            try {
                aggregated = aggregateHour(cursor);
            } catch (DuplicateKeyException duplicate) {
                aggregated = false;
            }
            if (!aggregated) {
                break;
            }
            processed++;
            cursor = cursor.minusHours(1);
        }
        if (processed > 0) {
            log.info("访问日志聚合完成：{} 个小时", processed);
        }
    }

    @Transactional
    public boolean aggregateHour(LocalDateTime hourStart) {
        if (markerMapper.countByHour(hourStart) > 0) {
            return false;
        }
        markerMapper.insertHour(hourStart);
        accessLogMapper.insertHourlyStats(hourStart, hourStart.plusHours(1));
        return true;
    }

    public void cleanupExpired() {
        LocalDateTime detailCutoff = LocalDateTime.now().minusDays(properties.getDetailRetentionDays());
        LocalDateTime statsCutoff = LocalDateTime.now().minusDays(properties.getStatsRetentionDays());
        int detailDeleted = deleteInBatches(() -> accessLogMapper.deleteBefore(detailCutoff));
        int statsDeleted = deleteInBatches(() -> accessStatsMapper.deleteBefore(statsCutoff));
        if (detailDeleted > 0 || statsDeleted > 0) {
            log.info("访问日志清理完成：明细 {} 行，聚合 {} 行", detailDeleted, statsDeleted);
        }
    }

    private int deleteInBatches(IntSupplier batch) {
        int total = 0;
        int deleted;
        do {
            deleted = batch.getAsInt();
            total += deleted;
        } while (deleted > 0);
        return total;
    }

    /** 把路径中的纯数字段归一化为 :id，便于按路由聚合。 */
    public static String normalizePath(String path) {
        if (path == null || path.isBlank()) {
            return "/";
        }
        String[] segments = path.split("/");
        StringBuilder sb = new StringBuilder();
        for (String segment : segments) {
            if (segment.isEmpty()) {
                continue;
            }
            sb.append('/').append(segment.matches("\\d+") ? ":id" : segment);
        }
        return sb.isEmpty() ? "/" : sb.toString();
    }

    private RejectedExecutionHandler rejectedHandler() {
        return (runnable, pool) -> {
            droppedCounter.incrementAndGet();
            log.warn("访问日志队列已满，丢弃 1 条（累计 {} 条）", droppedCounter.get());
        };
    }

    @PreDestroy
    public void shutdown() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}