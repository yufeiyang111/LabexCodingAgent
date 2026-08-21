package com.labex.monitor.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.labex.entity.OpsAuditLog;
import com.labex.mapper.OpsAuditLogMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AuditRecordingServiceTest {

    private final OpsAuditLogMapper auditLogMapper = mock(OpsAuditLogMapper.class);
    private AuditRecordingService service;

    @BeforeEach
    void setUp() {
        service = new AuditRecordingService(auditLogMapper);
    }

    @Test
    void recordPersistsSanitizedFields() {
        service.record("ALERT_ACKNOWLEDGE", "alert", "5", "OPS_OPERATOR", "OPS_OPERATOR",
                "127.0.0.1", "acknowledged", "FIRING", "ACKNOWLEDGED", "SUCCESS", null);

        verify(auditLogMapper).insert(any(OpsAuditLog.class));
    }

    @Test
    void sensitiveKeyValuePairsAreRedacted() {
        String redacted = service.redact("reason=ok secret=abc123 token=xyz password=p@ss");
        assertThat(redacted).contains("[REDACTED]").doesNotContain("abc123", "xyz", "p@ss");
    }

    @Test
    void redactionIsCaseInsensitive() {
        String redacted = service.redact("Authorization: Bearer abcdef, api_key=12345");
        assertThat(redacted).contains("[REDACTED]").doesNotContain("abcdef", "12345");
    }

    @Test
    void nullInputSurvivesRedaction() {
        assertThat(service.redact(null)).isNull();
    }

    @Test
    void longFieldsAreTruncated() {
        String longReason = "x".repeat(5000);
        service.record("OP", "task", "1", "op", "role", "ip", longReason, null, null, "SUCCESS", null);

        verify(auditLogMapper).insert(any(OpsAuditLog.class));
    }
}