package com.labex.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.labex.entity.OpsAlertNotification;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface OpsAlertNotificationMapper extends BaseMapper<OpsAlertNotification> {

    @Select("SELECT * FROM t_ops_alert_notification WHERE status IN ('pending', 'failed') "
            + "AND next_retry_at IS NOT NULL AND next_retry_at <= #{now} LIMIT #{limit}")
    List<OpsAlertNotification> selectDueForRetry(@Param("now") LocalDateTime now, @Param("limit") int limit);

    @Select("SELECT * FROM t_ops_alert_notification WHERE alert_id = #{alertId} ORDER BY id DESC LIMIT 1")
    OpsAlertNotification selectLatestForAlert(@Param("alertId") Long alertId);
}