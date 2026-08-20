package com.labex.monitor.geo;

import jakarta.annotation.PostConstruct;
import java.io.InputStream;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.lionsoul.ip2region.xdb.Searcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

/** 离线 IP 归属地查询：ip2region xdb 本地查询，不外发访客 IP，Searcher 只读线程安全。 */
@Service
public class IpLocationService {

    private static final Logger log = LoggerFactory.getLogger(IpLocationService.class);

    private final IpLocationProperties properties;
    private volatile Searcher searcher;
    private final Map<String, IpLocation> cache = new ConcurrentHashMap<>();

    public IpLocationService(IpLocationProperties properties) {
        this.properties = properties;
    }

    @PostConstruct
    void init() {
        if (!properties.isEnabled()) {
            log.info("IP 归属地解析已禁用");
            return;
        }
        try {
            byte[] data = loadXdb(properties.getXdbPath());
            searcher = Searcher.newWithBuffer(data);
            log.info("IP 归属地库加载完成，xdb={}", properties.getXdbPath());
        } catch (Exception e) {
            searcher = null;
            log.warn("IP 归属地库加载失败，定位功能禁用: {}", e.getMessage());
        }
    }

    private static byte[] loadXdb(String path) throws Exception {
        if (path != null && path.startsWith("classpath:")) {
            String resource = path.substring("classpath:".length());
            try (InputStream in = new ClassPathResource(resource).getInputStream()) {
                return in.readAllBytes();
            }
        }
        return Searcher.loadContentFromFile(path);
    }

    /** 查询 IP 归属地；内网/保留地址或查询失败返回 null。 */
    public IpLocation lookup(String ip) {
        if (searcher == null || !properties.isEnabled() || isLocalOrInvalid(ip)) {
            return null;
        }
        return cache.computeIfAbsent(ip, this::search);
    }

    private IpLocation search(String ip) {
        try {
            String region = searcher.search(ip);
            return parse(region);
        } catch (Exception e) {
            return null;
        }
    }

    static IpLocation parse(String region) {
        if (region == null || region.isBlank()) {
            return null;
        }
        String[] parts = region.split("\\|");
        IpLocation location = new IpLocation();
        location.setCountry(clean(parts, 0));
        location.setProvince(clean(parts, 1));
        location.setCity(clean(parts, 2));
        location.setIsp(clean(parts, 3));
        return location;
    }

    private static String clean(String[] parts, int index) {
        if (index >= parts.length) {
            return null;
        }
        String value = parts[index];
        if (value == null || value.isBlank() || "0".equals(value)) {
            return null;
        }
        return value.trim();
    }

    /** 仅处理公网 IPv4；本机/私网/保留段/IPv6 不查库（xdb v4 只覆盖公网 IPv4）。 */
    static boolean isLocalOrInvalid(String ip) {
        if (ip == null || ip.isBlank()) {
            return true;
        }
        if (!ip.contains(".")) {
            return true; // 包含 IPv6 与非 IPv4
        }
        String[] parts = ip.split("\\.");
        if (parts.length != 4) {
            return true;
        }
        int a = parseInt(parts[0]);
        int b = parseInt(parts[1]);
        if (a < 0) {
            return true;
        }
        if (a == 0 || a == 10 || a == 127) {
            return true;
        }
        if (a == 169 && b == 254) {
            return true;
        }
        if (a == 172 && b >= 16 && b <= 31) {
            return true;
        }
        if (a == 192 && b == 168) {
            return true;
        }
        return false;
    }

    private static int parseInt(String value) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}