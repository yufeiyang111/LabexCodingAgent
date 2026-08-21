package com.labex.monitor.alert;

import com.labex.entity.OpsAlert;
import com.labex.mapper.OpsAlertMapper;
import org.springframework.stereotype.Service;

/** 告警去重：同一规则同一指纹只保留一条活跃（非终态）告警，重复触发只刷新时间与数值。 */
@Service
public class AlertDeduplicationService {

    private final OpsAlertMapper alertMapper;

    public AlertDeduplicationService(OpsAlertMapper alertMapper) {
        this.alertMapper = alertMapper;
    }

    /** 返回活跃告警；不存在则返回 null 由调用方新建。 */
    public OpsAlert findActive(Long ruleId, String fingerprint) {
        if (ruleId == null || fingerprint == null || fingerprint.isBlank()) {
            return null;
        }
        return alertMapper.selectActiveByRuleAndFingerprint(ruleId, fingerprint);
    }

    public String fingerprintFor(Long ruleId) {
        return "rule-" + ruleId;
    }
}