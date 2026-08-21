package com.labex.monitor.controller;

import com.labex.common.Result;
import com.labex.entity.OpsAlertRule;
import com.labex.monitor.alert.AlertRuleService;
import com.labex.monitor.dto.AlertRuleRequest;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 告警规则管理。 */
@RestController
@RequestMapping("/ops/alerts/rules")
public class MonitorAlertRuleController {

    private final AlertRuleService ruleService;

    public MonitorAlertRuleController(AlertRuleService ruleService) {
        this.ruleService = ruleService;
    }

    @GetMapping
    public Result<List<OpsAlertRule>> list(@RequestParam(defaultValue = "false") boolean enabledOnly) {
        return Result.success(ruleService.list(enabledOnly));
    }

    @GetMapping("/{ruleId}")
    public Result<OpsAlertRule> get(@PathVariable Long ruleId) {
        OpsAlertRule rule = ruleService.get(ruleId);
        return rule == null ? Result.error(404, "Alert rule not found") : Result.success(rule);
    }

    @PostMapping
    public ResponseEntity<Result<OpsAlertRule>> create(@RequestBody AlertRuleRequest request) {
        try {
            return ResponseEntity.ok(Result.success(ruleService.create(request)));
        } catch (IllegalArgumentException failure) {
            return ResponseEntity.badRequest().body(Result.error(-1, failure.getMessage()));
        }
    }

    @PutMapping("/{ruleId}")
    public ResponseEntity<Result<OpsAlertRule>> update(@PathVariable Long ruleId,
                                                       @RequestBody AlertRuleRequest request) {
        try {
            OpsAlertRule updated = ruleService.update(ruleId, request);
            if (updated == null) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND.value()).body(Result.error(404, "Alert rule not found"));
            }
            return ResponseEntity.ok(Result.success(updated));
        } catch (IllegalArgumentException failure) {
            return ResponseEntity.badRequest().body(Result.error(-1, failure.getMessage()));
        }
    }

    @DeleteMapping("/{ruleId}")
    public ResponseEntity<Result<Void>> delete(@PathVariable Long ruleId) {
        if (!ruleService.setEnabled(ruleId, false)) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND.value()).body(Result.error(404, "Alert rule not found"));
        }
        return ResponseEntity.ok(Result.success());
    }
}