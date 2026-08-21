package com.labex.monitor.audit;

import com.labex.entity.OpsAuditLog;
import com.labex.mapper.OpsAuditLogMapper;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

/** 审计记录写入；reason/before/after 中的敏感键值对统一脱敏，不落原始凭证。 */
@Service
public class AuditRecordingService {

    private static final Pattern SENSITIVE_KEYS = Pattern.compile(
            "(?i)(\\b(?:password|secret|token|authorization|api[_-]?key|access[_-]?code|private[_-]?key"
                    + "|db[_-]?password|webhook[_-]?url)\\b\\s*[=:]\\s*)([^,\\n\"'}]+)");
    private static final String REDACTED = "$1[REDACTED]";

    private final OpsAuditLogMapper auditLogMapper;

    public AuditRecordingService(OpsAuditLogMapper auditLogMapper) {
        this.auditLogMapper = auditLogMapper;
    }

    public void record(String actionType, String targetType, String targetId, String operatorId,
                       String operatorRole, String sourceIp, String reason, String beforeState,
                       String afterState, String result, String failureReason) {
        OpsAuditLog log = new OpsAuditLog();
        log.setActionType(actionType);
        log.setTargetType(targetType);
        log.setTargetId(targetId);
        log.setOperatorId(truncate(operatorId, 160));
        log.setOperatorRole(truncate(operatorRole, 32));
        log.setSourceIp(truncate(sourceIp, 64));
        log.setReason(truncate(redact(reason), 1024));
        log.setBeforeState(truncate(redact(beforeState), 2048));
        log.setAfterState(truncate(redact(afterState), 2048));
        log.setResult(result == null || result.isBlank() ? "SUCCESS" : result);
        log.setFailureReason(truncate(redact(failureReason), 1024));
        auditLogMapper.insert(log);
    }

    public String redact(String input) {
        if (input == null) {
            return null;
        }
        return SENSITIVE_KEYS.matcher(input).replaceAll(REDACTED);
    }

    private String truncate(String input, int max) {
        if (input == null) {
            return null;
        }
        return input.length() <= max ? input : input.substring(0, max);
    }
}