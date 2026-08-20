package com.labex.monitor.geo;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** IP 归属地（离线 ip2region）可调参数。 */
@Data
@ConfigurationProperties(prefix = "labex-agent.monitor.geo")
public class IpLocationProperties {
    private boolean enabled = true;
    /** xdb 数据文件路径，支持 classpath: 前缀；默认打包进应用。 */
    private String xdbPath = "classpath:ip2region_v4.xdb";
    /** 内存缓存 IP 数上限（超出后整体重建）。 */
    private int cacheSize = 4096;
}