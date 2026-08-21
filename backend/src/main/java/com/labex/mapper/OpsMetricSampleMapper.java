package com.labex.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.labex.entity.OpsMetricSample;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface OpsMetricSampleMapper extends BaseMapper<OpsMetricSample> {

    /** 删除指定时间之前的采样（LIMIT 批量，防长锁；由保留策略分批调用）。 */
    @Delete("DELETE FROM t_ops_metric_sample WHERE sample_time < #{cutoff} LIMIT 5000")
    int deleteBefore(@Param("cutoff") LocalDateTime cutoff);

    /** 时间范围内的采样序列（升序），供趋势图使用。 */
    @Select("""
            SELECT id, sample_time, cpu_percent, memory_percent, memory_used_bytes, disk_percent,
                   disk_used_bytes, heap_used_bytes, heap_max_bytes, system_load_average,
                   task_total, task_running, task_waiting, task_completed, task_failed, task_cancelled,
                   token_prompt_total, token_completion_total, token_total, create_time
            FROM t_ops_metric_sample
            WHERE sample_time >= #{since}
            ORDER BY sample_time ASC
            """)
    List<OpsMetricSample> selectSince(@Param("since") LocalDateTime since);

    /** 最近一次采样。 */
    @Select("""
            SELECT id, sample_time, cpu_percent, memory_percent, memory_used_bytes, disk_percent,
                   disk_used_bytes, heap_used_bytes, heap_max_bytes, system_load_average,
                   task_total, task_running, task_waiting, task_completed, task_failed, task_cancelled,
                   token_prompt_total, token_completion_total, token_total, create_time
            FROM t_ops_metric_sample
            ORDER BY sample_time DESC LIMIT 1
            """)
    OpsMetricSample selectLatest();
}
