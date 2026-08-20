package com.labex.monitor.service;

import com.labex.mapper.AccessLogMapper;
import com.labex.monitor.dto.AccessSummary;
import com.labex.monitor.dto.PathStat;
import com.labex.monitor.dto.StatusStat;
import com.labex.monitor.dto.TotalSummary;
import com.labex.monitor.dto.TrafficPoint;
import com.labex.monitor.dto.VisitorAgg;
import com.labex.monitor.dto.VisitorHit;
import com.labex.monitor.geo.IpLocationService;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/** 监控统计查询。 */
@Service
public class MonitorQueryService {

    private final AccessLogMapper accessLogMapper;
    private final IpLocationService ipLocationService;

    public MonitorQueryService(AccessLogMapper accessLogMapper, IpLocationService ipLocationService) {
        this.accessLogMapper = accessLogMapper;
        this.ipLocationService = ipLocationService;
    }

    public Map<String, Object> summary() {
        LocalDateTime todayStart = LocalDate.now().atStartOfDay();
        LocalDateTime yesterdayStart = todayStart.minusDays(1);
        AccessSummary today = accessLogMapper.summarizeSince(todayStart);
        AccessSummary yesterday = accessLogMapper.summarizeSince(yesterdayStart);
        TotalSummary total = accessLogMapper.totalSummary();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("today", today);
        result.put("yesterday", yesterday);
        result.put("total", total);
        result.put("serverTime", LocalDateTime.now().toString());
        return result;
    }

    public List<TrafficPoint> traffic(String range) {
        return switch (range) {
            case "24h" -> accessLogMapper.trafficByHour(LocalDateTime.now().minusHours(24));
            case "7d" -> accessLogMapper.trafficByDay(7);
            case "30d" -> accessLogMapper.trafficByDay(30);
            default -> throw new IllegalArgumentException("不支持的统计区间: " + range);
        };
    }

    public List<PathStat> topPaths(int days, int limit) {
        return accessLogMapper.topPaths(Math.max(1, days), Math.min(100, Math.max(1, limit)));
    }

    public List<StatusStat> statusDistribution(int days) {
        return accessLogMapper.statusDistribution(Math.max(1, days));
    }

    public List<VisitorHit> recentVisitors(int limit) {
        List<VisitorHit> hits = accessLogMapper.recentVisitors(Math.min(200, Math.max(1, limit)));
        hits.forEach(hit -> hit.setLocation(resolveLocation(hit.getIp())));
        return hits;
    }

    public List<VisitorAgg> visitors(int days, int limit) {
        List<VisitorAgg> visitors = accessLogMapper.selectVisitors(Math.max(1, days), Math.min(200, Math.max(1, limit)));
        visitors.forEach(visitor -> visitor.setLocation(resolveLocation(visitor.getIp())));
        return visitors;
    }

    private String resolveLocation(String ip) {
        com.labex.monitor.geo.IpLocation location = ipLocationService.lookup(ip);
        return location == null ? null : location.display();
    }
}