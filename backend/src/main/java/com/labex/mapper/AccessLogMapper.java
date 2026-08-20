package com.labex.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.labex.entity.AccessLog;
import com.labex.monitor.dto.AccessSummary;
import com.labex.monitor.dto.PathStat;
import com.labex.monitor.dto.StatusStat;
import com.labex.monitor.dto.TotalSummary;
import com.labex.monitor.dto.TrafficPoint;
import com.labex.monitor.dto.VisitorAgg;
import com.labex.monitor.dto.VisitorHit;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface AccessLogMapper extends BaseMapper<AccessLog> {

    /** 把指定小时窗口内的明细聚合进 t_access_stats（path 粒度）。 */
    @Insert("""
            INSERT INTO t_access_stats (stat_hour, path, pv, uv, error_count, avg_duration_ms)
            SELECT #{hourStart}, path, COUNT(*),
                   COUNT(DISTINCT CONCAT(COALESCE(CAST(user_id AS CHAR), ''), ':', COALESCE(ip, ''))),
                   SUM(CASE WHEN status >= 400 THEN 1 ELSE 0 END),
                   ROUND(AVG(duration_ms), 2)
            FROM t_access_log
            WHERE request_time >= #{hourStart} AND request_time < #{hourEnd}
            GROUP BY path
            """)
    int insertHourlyStats(@Param("hourStart") LocalDateTime hourStart, @Param("hourEnd") LocalDateTime hourEnd);

    @Select("""
            SELECT COUNT(*) AS pv,
                   COUNT(DISTINCT CONCAT(COALESCE(CAST(user_id AS CHAR), ''), ':', COALESCE(ip, ''))) AS uv,
                   SUM(CASE WHEN status >= 400 THEN 1 ELSE 0 END) AS errorCount,
                   ROUND(AVG(duration_ms), 2) AS avgDurationMs
            FROM t_access_log
            WHERE request_time >= #{since}
            """)
    AccessSummary summarizeSince(@Param("since") LocalDateTime since);

    /** 全量累计：聚合表总和 + 最后一次聚合之后未聚合的明细补差。 */
    @Select("""
            SELECT
              (SELECT COALESCE(SUM(pv), 0) FROM t_access_stats)
              + (SELECT COUNT(*) FROM t_access_log
                 WHERE request_time >= COALESCE((SELECT MAX(stat_hour) FROM t_access_stats), '1970-01-01 00:00:00')) AS pv,
              (SELECT COALESCE(SUM(uv), 0) FROM t_access_stats)
              + (SELECT COUNT(DISTINCT CONCAT(COALESCE(CAST(user_id AS CHAR), ''), ':', COALESCE(ip, ''))) FROM t_access_log
                 WHERE request_time >= COALESCE((SELECT MAX(stat_hour) FROM t_access_stats), '1970-01-01 00:00:00')) AS uv
            """)
    TotalSummary totalSummary();

    /** 小时粒度流量（近 24 小时等短窗口，明细实时）。 */
    @Select("""
            SELECT DATE_FORMAT(request_time, '%Y-%m-%d %H:00:00') AS time,
                   COUNT(*) AS pv,
                   COUNT(DISTINCT CONCAT(COALESCE(CAST(user_id AS CHAR), ''), ':', COALESCE(ip, ''))) AS uv,
                   SUM(CASE WHEN status >= 400 THEN 1 ELSE 0 END) AS errorCount,
                   ROUND(AVG(duration_ms), 2) AS avgDurationMs
            FROM t_access_log
            WHERE request_time >= #{since}
            GROUP BY time
            ORDER BY time
            """)
    List<TrafficPoint> trafficByHour(@Param("since") LocalDateTime since);

    /** 天粒度流量（7d/30d，明细按天去重 UV 精确）。 */
    @Select("""
            SELECT DATE_FORMAT(request_time, '%Y-%m-%d') AS time,
                   COUNT(*) AS pv,
                   COUNT(DISTINCT CONCAT(COALESCE(CAST(user_id AS CHAR), ''), ':', COALESCE(ip, ''))) AS uv,
                   SUM(CASE WHEN status >= 400 THEN 1 ELSE 0 END) AS errorCount,
                   ROUND(AVG(duration_ms), 2) AS avgDurationMs
            FROM t_access_log
            WHERE request_time >= DATE_SUB(NOW(), INTERVAL #{days} DAY)
            GROUP BY time
            ORDER BY time
            """)
    List<TrafficPoint> trafficByDay(@Param("days") int days);

    @Select("""
            SELECT path, COUNT(*) AS pv,
                   COUNT(DISTINCT CONCAT(COALESCE(CAST(user_id AS CHAR), ''), ':', COALESCE(ip, ''))) AS uv,
                   SUM(CASE WHEN status >= 400 THEN 1 ELSE 0 END) AS errorCount,
                   ROUND(AVG(duration_ms), 2) AS avgDurationMs
            FROM t_access_log
            WHERE request_time >= DATE_SUB(NOW(), INTERVAL #{days} DAY)
            GROUP BY path
            ORDER BY pv DESC, uv DESC
            LIMIT #{limit}
            """)
    List<PathStat> topPaths(@Param("days") int days, @Param("limit") int limit);

    @Select("""
            SELECT status, COUNT(*) AS count
            FROM t_access_log
            WHERE request_time >= DATE_SUB(NOW(), INTERVAL #{days} DAY)
            GROUP BY status
            ORDER BY count DESC
            """)
    List<StatusStat> statusDistribution(@Param("days") int days);

    @Select("""
            SELECT request_time AS requestTime, method, path, status, duration_ms AS durationMs,
                   ip, user_id AS userId
            FROM t_access_log
            ORDER BY request_time DESC, id DESC
            LIMIT #{limit}
            """)
    List<VisitorHit> recentVisitors(@Param("limit") int limit);

    @Select("""
            SELECT ip, COUNT(*) AS pv, MAX(request_time) AS lastSeen
            FROM t_access_log
            WHERE ip IS NOT NULL AND ip <> ''
              AND request_time >= DATE_SUB(NOW(), INTERVAL #{days} DAY)
            GROUP BY ip
            ORDER BY pv DESC, lastSeen DESC
            LIMIT #{limit}
            """)
    List<VisitorAgg> selectVisitors(@Param("days") int days, @Param("limit") int limit);

    @Delete("DELETE FROM t_access_log WHERE request_time < #{cutoff} LIMIT 5000")
    int deleteBefore(@Param("cutoff") LocalDateTime cutoff);
}