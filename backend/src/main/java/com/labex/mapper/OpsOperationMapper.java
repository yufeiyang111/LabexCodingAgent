package com.labex.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.labex.entity.OpsOperation;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface OpsOperationMapper extends BaseMapper<OpsOperation> {

    @Select("SELECT * FROM t_ops_operation WHERE idempotency_key = #{idempotencyKey} LIMIT 1")
    OpsOperation selectByIdempotencyKey(@Param("idempotencyKey") String idempotencyKey);
}