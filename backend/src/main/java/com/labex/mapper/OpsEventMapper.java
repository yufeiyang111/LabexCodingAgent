package com.labex.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.labex.entity.OpsEvent;
import java.time.LocalDateTime;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface OpsEventMapper extends BaseMapper<OpsEvent> {

    @Select("SELECT COUNT(*) FROM t_ops_event WHERE create_time >= #{since}")
    long countSince(@Param("since") LocalDateTime since);
}