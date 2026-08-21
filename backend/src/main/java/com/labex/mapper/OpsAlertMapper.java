package com.labex.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.labex.entity.OpsAlert;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface OpsAlertMapper extends BaseMapper<OpsAlert> {

    @Select("SELECT * FROM t_ops_alert WHERE rule_id = #{ruleId} AND fingerprint = #{fingerprint} "
            + "AND status IN ('FIRING', 'ACKNOWLEDGED', 'SILENCED') LIMIT 1")
    OpsAlert selectActiveByRuleAndFingerprint(@Param("ruleId") Long ruleId, @Param("fingerprint") String fingerprint);

    @Select("SELECT * FROM t_ops_alert WHERE rule_id = #{ruleId} AND fingerprint = #{fingerprint} "
            + "AND status = 'FIRING' LIMIT 1")
    OpsAlert selectFiringByRuleAndFingerprint(@Param("ruleId") Long ruleId, @Param("fingerprint") String fingerprint);

    @Select("SELECT * FROM t_ops_alert WHERE status IN ('FIRING', 'ACKNOWLEDGED', 'SILENCED') "
            + "AND (silenced_until IS NULL OR silenced_until < NOW()) AND resolved_at IS NULL LIMIT #{limit}")
    List<OpsAlert> selectActiveUnsilenced(@Param("limit") int limit);
}