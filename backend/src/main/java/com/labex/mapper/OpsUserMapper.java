package com.labex.mapper;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface OpsUserMapper {

    @Select("SELECT COUNT(DISTINCT user_id) FROM t_access_log WHERE user_id IS NOT NULL AND request_time >= #{since}")
    int countActiveUsersSince(@Param("since") LocalDateTime since);

    @Select("SELECT COUNT(DISTINCT user_id) FROM t_access_log WHERE user_id IS NOT NULL AND request_time >= #{start} AND request_time < #{end}")
    int countActiveUsersBetween(@Param("start") LocalDateTime start, @Param("end") LocalDateTime end);

    @Select("SELECT COALESCE(SUM(total_tokens), 0) FROM t_agent_token_usage WHERE create_time >= #{since}")
    long sumTokensSince(@Param("since") LocalDateTime since);

    @Select("SELECT COUNT(*) FROM t_agent_task WHERE submitted_at >= #{since}")
    int countTasksSince(@Param("since") LocalDateTime since);

    @Select("""
            SELECT DATE_FORMAT(request_time, '%Y-%m-%d %H:00:00') AS time_bucket,
                   COUNT(DISTINCT user_id) AS active_users,
                   COUNT(*) AS request_count
            FROM t_access_log
            WHERE request_time >= #{since}
            GROUP BY time_bucket
            ORDER BY time_bucket
            """)
    List<Map<String, Object>> accessTrendByHour(@Param("since") LocalDateTime since);

    @Select("""
            SELECT DATE_FORMAT(request_time, '%Y-%m-%d') AS time_bucket,
                   COUNT(DISTINCT user_id) AS active_users,
                   COUNT(*) AS request_count
            FROM t_access_log
            WHERE request_time >= DATE_SUB(NOW(), INTERVAL #{days} DAY)
            GROUP BY time_bucket
            ORDER BY time_bucket
            """)
    List<Map<String, Object>> accessTrendByDay(@Param("days") int days);

    @Select("""
            SELECT DATE_FORMAT(submitted_at, '%Y-%m-%d %H:00:00') AS time_bucket,
                   COUNT(*) AS task_count
            FROM t_agent_task
            WHERE submitted_at >= #{since}
            GROUP BY time_bucket
            ORDER BY time_bucket
            """)
    List<Map<String, Object>> taskTrendByHour(@Param("since") LocalDateTime since);

    @Select("""
            SELECT DATE_FORMAT(submitted_at, '%Y-%m-%d') AS time_bucket,
                   COUNT(*) AS task_count
            FROM t_agent_task
            WHERE submitted_at >= DATE_SUB(NOW(), INTERVAL #{days} DAY)
            GROUP BY time_bucket
            ORDER BY time_bucket
            """)
    List<Map<String, Object>> taskTrendByDay(@Param("days") int days);

    @Select("""
            SELECT student_id AS user_id, COUNT(*) AS project_count
            FROM t_student_project
            WHERE student_id IN (${userIdsCsv})
            GROUP BY student_id
            """)
    List<Map<String, Object>> selectProjectCountsByUserIds(@Param("userIdsCsv") String userIdsCsv);

    @Select("""
            SELECT student_id AS user_id,
                   COUNT(*) AS task_total,
                   SUM(CASE WHEN status = 'completed' THEN 1 ELSE 0 END) AS task_completed
            FROM t_agent_task
            WHERE student_id IN (${userIdsCsv})
            GROUP BY student_id
            """)
    List<Map<String, Object>> selectTaskStatsByUserIds(@Param("userIdsCsv") String userIdsCsv);

    @Select("""
            SELECT student_id AS user_id,
                   COALESCE(SUM(total_tokens), 0) AS total_tokens
            FROM t_agent_token_usage
            WHERE student_id IN (${userIdsCsv})
            GROUP BY student_id
            """)
    List<Map<String, Object>> selectTokenTotalsByUserIds(@Param("userIdsCsv") String userIdsCsv);

    @Select("""
            SELECT user_id,
                   MAX(request_time) AS last_active_time,
                   SUBSTRING_INDEX(GROUP_CONCAT(ip ORDER BY request_time DESC), ',', 1) AS last_ip
            FROM t_access_log
            WHERE user_id IN (${userIdsCsv})
            GROUP BY user_id
            """)
    List<Map<String, Object>> selectLastActiveByUserIds(@Param("userIdsCsv") String userIdsCsv);

    @Select("""
            SELECT provider, model,
                   COALESCE(SUM(total_tokens), 0) AS total_tokens
            FROM t_agent_token_usage
            WHERE student_id = #{userId}
            GROUP BY provider, model
            ORDER BY total_tokens DESC
            """)
    List<Map<String, Object>> selectUserTokenBreakdown(@Param("userId") Integer userId);

    @Select("""
            SELECT status, COUNT(*) AS count_val
            FROM t_agent_task
            WHERE student_id = #{userId}
            GROUP BY status
            """)
    List<Map<String, Object>> selectUserTaskStatusCounts(@Param("userId") Integer userId);

    @Select("""
            SELECT ip, request_time
            FROM t_access_log
            WHERE user_id = #{userId}
            ORDER BY request_time DESC
            LIMIT 1
            """)
    Map<String, Object> selectLastAccessByUserId(@Param("userId") Integer userId);
}
