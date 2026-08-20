package com.labex.monitor.access;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 访问日志聚合与清理的定时触发入口。 */
@Component
public class AccessLogAggregator {

    private static final Logger log = LoggerFactory.getLogger(AccessLogAggregator.class);

    private final AccessLogService accessLogService;

    public AccessLogAggregator(AccessLogService accessLogService) {
        this.accessLogService = accessLogService;
    }

    /** 每小时第 10 分钟聚合上一个完整小时（含停机补跑）。 */
    @Scheduled(cron = "0 10 * * * *")
    public void aggregateHourly() {
        try {
            accessLogService.aggregatePastHours();
        } catch (RuntimeException failure) {
            log.error("访问日志聚合失败", failure);
        }
    }

    /** 每天凌晨 3:15 清理过期明细与聚合。 */
    @Scheduled(cron = "0 15 3 * * *")
    public void cleanupDaily() {
        try {
            accessLogService.cleanupExpired();
        } catch (RuntimeException failure) {
            log.error("访问日志清理失败", failure);
        }
    }
}