package com.labex.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.labex.entity.AccessAggMarker;
import java.time.LocalDateTime;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface AccessAggMarkerMapper extends BaseMapper<AccessAggMarker> {

    @Select("SELECT COUNT(*) FROM t_access_agg_marker WHERE hour_start = #{hourStart}")
    int countByHour(@Param("hourStart") LocalDateTime hourStart);

    @Insert("INSERT INTO t_access_agg_marker (hour_start) VALUES (#{hourStart})")
    int insertHour(@Param("hourStart") LocalDateTime hourStart);
}