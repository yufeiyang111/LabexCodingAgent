package com.labex.monitor.alert;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.labex.entity.OpsAlertRule;
import com.labex.mapper.OpsAlertRuleMapper;
import com.labex.monitor.dto.AlertRuleRequest;
import java.util.List;
import org.springframework.stereotype.Service;

/** 告警规则 CRUD；删除采用禁用而非物理删除，保留历史告警归属。 */
@Service
public class AlertRuleService {

    private final OpsAlertRuleMapper ruleMapper;
    private final AlertRuleValidator validator;

    public AlertRuleService(OpsAlertRuleMapper ruleMapper, AlertRuleValidator validator) {
        this.ruleMapper = ruleMapper;
        this.validator = validator;
    }

    public List<OpsAlertRule> list(boolean enabledOnly) {
        return ruleMapper.selectList(new LambdaQueryWrapper<OpsAlertRule>()
                .eq(enabledOnly, OpsAlertRule::getEnabled, 1)
                .orderByAsc(OpsAlertRule::getRuleId));
    }

    public OpsAlertRule get(Long ruleId) {
        return ruleId == null ? null : ruleMapper.selectById(ruleId);
    }

    public OpsAlertRule create(AlertRuleRequest request) {
        validator.validate(request);
        OpsAlertRule rule = new OpsAlertRule();
        apply(rule, request);
        rule.setEnabled(request.getEnabled() == null || request.getEnabled() ? 1 : 0);
        ruleMapper.insert(rule);
        return rule;
    }

    public OpsAlertRule update(Long ruleId, AlertRuleRequest request) {
        validator.validate(request);
        OpsAlertRule existing = ruleMapper.selectById(ruleId);
        if (existing == null) {
            return null;
        }
        apply(existing, request);
        if (request.getEnabled() != null) {
            existing.setEnabled(request.getEnabled() ? 1 : 0);
        }
        ruleMapper.updateById(existing);
        return existing;
    }

    public boolean setEnabled(Long ruleId, boolean enabled) {
        OpsAlertRule existing = ruleMapper.selectById(ruleId);
        if (existing == null) {
            return false;
        }
        existing.setEnabled(enabled ? 1 : 0);
        ruleMapper.updateById(existing);
        return true;
    }

    private void apply(OpsAlertRule rule, AlertRuleRequest request) {
        rule.setName(request.getName());
        rule.setMetricKey(request.getMetricKey());
        rule.setOperator(request.getOperator());
        rule.setThreshold(request.getThreshold());
        rule.setDurationMinutes(request.getDurationMinutes());
        rule.setCooldownMinutes(request.getCooldownMinutes());
        rule.setSeverity(request.getSeverity());
        rule.setDescription(request.getDescription());
    }
}