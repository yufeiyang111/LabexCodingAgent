package com.labex.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.labex.entity.AccessStats;
import java.time.LocalDateTime;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface AccessStatsMapper extends BaseMapper<AccessStats> {

    @Delete("DELETE FROM t_access_stats WHERE stat_hour < #{cutoff} LIMIT 5000")
    int deleteBefore(@Param("cutoff") LocalDateTime cutoff);
}