package com.labex.monitor.alert;

import com.labex.monitor.dto.AlertRuleRequest;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/** 初始告警规则种子：仅在规则表为空时写入默认规则；之后完全以用户数据为准，不回填、不覆盖删除。 */
@Component
public class AlertRuleSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AlertRuleSeeder.class);

    private final AlertRuleService ruleService;

    public AlertRuleSeeder(AlertRuleService ruleService) {
        this.ruleService = ruleService;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!ruleService.list(false).isEmpty()) {
            return;
        }
        int created = 0;
        for (AlertRuleRequest request : defaults()) {
            try {
                ruleService.create(request);
                created++;
            } catch (IllegalArgumentException failure) {
                log.warn("Skip invalid default alert rule [{}]: {}", request.getName(), failure.getMessage());
            }
        }
        if (created > 0) {
            log.info("Seeded {} default alert rules", created);
        }
    }

    static List<AlertRuleRequest> defaults() {
        return List.of(
                request("CPU 使用率过高", "cpu_percent", "GT", 90.0, 5, 30, "warning",
                        "CPU 使用率持续 5 分钟超过 90%"),
                request("内存使用率过高", "memory_percent", "GT", 85.0, 5, 30, "warning",
                        "内存使用率持续 5 分钟超过 85%"),
                request("磁盘使用率过高", "disk_percent", "GT", 90.0, 5, 60, "critical",
                        "磁盘使用率持续 5 分钟超过 90%"));
    }

    private static AlertRuleRequest request(String name, String metricKey, String operator, Double threshold,
                                            Integer durationMinutes, Integer cooldownMinutes,
                                            String severity, String description) {
        AlertRuleRequest request = new AlertRuleRequest();
        request.setName(name);
        request.setMetricKey(metricKey);
        request.setOperator(operator);
        request.setThreshold(threshold);
        request.setDurationMinutes(durationMinutes);
        request.setCooldownMinutes(cooldownMinutes);
        request.setSeverity(severity);
        request.setDescription(description);
        request.setEnabled(true);
        return request;
    }
}