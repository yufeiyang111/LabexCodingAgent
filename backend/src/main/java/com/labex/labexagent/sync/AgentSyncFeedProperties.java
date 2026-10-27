package com.labex.labexagent.sync;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * B1 同步供给（只读投影）的读取上限。
 *
 * 集中管理：默认值写在字段初始化处，可用 application.yml / 环境变量
 * `labex-agent.sync-feed.*` 覆盖，禁止在服务里散落魔法数字。
 */
@Configuration
@ConfigurationProperties(prefix = "labex-agent.sync-feed")
public class AgentSyncFeedProperties {

    /** 单页返回的 change-set 数量上限；超出即 hasMore=true。 */
    private int maxChangeSetsPerPage = 50;

    /** 单页返回的"游标之内、状态或内容发生变化"的文件上限。 */
    private int maxChangedFilesPerPage = 200;

    /**
     * 尾巴扫描的重叠窗口（秒）。
     *
     * 为什么需要它：{@code update_time} 是秒级 DATETIME，而 MySQL 对超精度写入**四舍五入**，
     * JVM 写入时钟与 DB 时钟也可能有偏差。扫完一页后把续拉点回退若干秒重扫，
     * 使同秒边界行是"重复交付"而不是"永久丢失"（客户端按 changeId+状态幂等去重）。
     * 取值应大于"写入事务最长持续时间 + 两时钟偏差"的常识上界。
     */
    private int tailOverlapSeconds = 2;

    public int getMaxChangeSetsPerPage() {
        return maxChangeSetsPerPage;
    }

    public void setMaxChangeSetsPerPage(int value) {
        maxChangeSetsPerPage = Math.max(1, Math.min(500, value));
    }

    public int getMaxChangedFilesPerPage() {
        return maxChangedFilesPerPage;
    }

    public void setMaxChangedFilesPerPage(int value) {
        maxChangedFilesPerPage = Math.max(1, Math.min(2000, value));
    }

    public int getTailOverlapSeconds() {
        return tailOverlapSeconds;
    }

    public void setTailOverlapSeconds(int value) {
        tailOverlapSeconds = Math.max(0, Math.min(60, value));
    }

    /** 请求参数夹取：null/非正数回落到默认值，越界夹到上限（不接受无上限分页）。 */
    public int clampPageSize(Integer requested) {
        if (requested == null || requested <= 0) {
            return maxChangeSetsPerPage;
        }
        return Math.min(requested, maxChangeSetsPerPage);
    }
}
