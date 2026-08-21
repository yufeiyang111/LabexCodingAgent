package com.labex.monitor.alert;

import com.labex.entity.OpsAlert;
import com.labex.entity.OpsAlertNotification;
import com.labex.mapper.OpsAlertMapper;
import com.labex.mapper.OpsAlertNotificationMapper;
import com.labex.monitor.config.OpsAlertProperties;
import com.labex.monitor.event.OpsEventRecordingService;
import java.time.LocalDateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/** 告警通知：Webhook 渠道（可配置），失败按指数退避重试；未配置渠道时只落库标记 skipped。 */
@Service
public class AlertNotificationService {

    private static final Logger log = LoggerFactory.getLogger(AlertNotificationService.class);
    private static final int RETRY_BATCH = 50;

    private final OpsAlertMapper alertMapper;
    private final OpsAlertNotificationMapper notificationMapper;
    private final OpsAlertProperties properties;
    private final OpsEventRecordingService eventService;

    public AlertNotificationService(OpsAlertMapper alertMapper, OpsAlertNotificationMapper notificationMapper,
                                    OpsAlertProperties properties, OpsEventRecordingService eventService) {
        this.alertMapper = alertMapper;
        this.notificationMapper = notificationMapper;
        this.properties = properties;
        this.eventService = eventService;
    }

    /** 新告警触发通知（幂等：同告警同规则已有通知则不重复发送）。 */
    public OpsAlertNotification notifyFiring(OpsAlert alert) {
        OpsAlertNotification latest = notificationMapper.selectLatestForAlert(alert.getAlertId());
        if (latest != null) {
            return latest;
        }
        OpsAlertNotification notification = new OpsAlertNotification();
        notification.setAlertId(alert.getAlertId());
        notification.setRuleId(alert.getRuleId());
        notification.setChannel("webhook");
        notification.setAttempt(0);
        notification.setStatus("pending");
        notification.setNextRetryAt(LocalDateTime.now());
        notificationMapper.insert(notification);
        dispatch(notification);
        return notification;
    }

    @Scheduled(fixedDelayString = "${labex-agent.monitor.alert.notification-retry-interval-ms:300000}")
    public void retryScheduled() {
        LocalDateTime now = LocalDateTime.now();
        int remaining = RETRY_BATCH;
        for (OpsAlertNotification notification : notificationMapper.selectDueForRetry(now, RETRY_BATCH)) {
            if (remaining-- <= 0) {
                break;
            }
            dispatch(notification);
        }
    }

    private void dispatch(OpsAlertNotification notification) {
        String webhookUrl = properties.getWebhookUrl();
        if (webhookUrl == null || webhookUrl.isBlank()) {
            notification.setStatus("skipped");
            notification.setErrorMessage("no webhook configured");
            notificationMapper.updateById(notification);
            return;
        }
        int attempt = notification.getAttempt() == null ? 0 : notification.getAttempt() + 1;
        notification.setAttempt(attempt);
        try {
            sendWebhook(webhookUrl, notification);
            notification.setStatus("sent");
            notification.setErrorMessage(null);
            notification.setNextRetryAt(null);
            notificationMapper.updateById(notification);
        } catch (Exception failure) {
            boolean lastAttempt = attempt >= properties.getNotificationMaxAttempts();
            notification.setStatus(lastAttempt ? "failed" : "failed");
            notification.setErrorMessage(truncate(failure.getMessage(), 512));
            notification.setNextRetryAt(LocalDateTime.now()
                    .plusMinutes(properties.getNotificationRetryDelayMinutes()));
            notificationMapper.updateById(notification);
            if (lastAttempt) {
                eventService.record("ALERT_NOTIFY_FAILED", "warning", "notification", "alert",
                        String.valueOf(notification.getAlertId()),
                        "notification delivery failed after " + attempt + " attempts", null, "system");
            }
        }
    }

    private void sendWebhook(String url, OpsAlertNotification notification) throws java.io.IOException, InterruptedException {
        // 通知负载只含告警摘要，不含任何凭证；投递失败由上层捕获并重试。
        OpsAlert alert = alertMapper.selectById(notification.getAlertId());
        if (alert == null) {
            return;
        }
        String payload = "{\"alertId\":" + alert.getAlertId() + ",\"status\":\"" + alert.getStatus()
                + "\",\"severity\":\"" + alert.getSeverity() + "\",\"metricKey\":\"" + alert.getMetricKey()
                + "\",\"currentValue\":" + alert.getCurrentValue() + ",\"threshold\":" + alert.getThreshold()
                + ",\"message\":\"" + escapeJson(alert.getMessage()) + "\"}";
        notification.setPayload(truncate(payload, 2048));
        java.net.http.HttpClient client = java.net.http.HttpClient.newBuilder()
                .connectTimeout(java.time.Duration.ofSeconds(5))
                .build();
        java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder()
                .uri(java.net.URI.create(url))
                .header("Content-Type", "application/json")
                .timeout(java.time.Duration.ofSeconds(5))
                .POST(java.net.http.HttpRequest.BodyPublishers.ofString(payload))
                .build();
        java.net.http.HttpResponse<String> response = client.send(request,
                java.net.http.HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("webhook returned HTTP " + response.statusCode());
        }
    }

    private String escapeJson(String input) {
        if (input == null) {
            return "";
        }
        return input.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
    }

    private String truncate(String input, int max) {
        if (input == null) {
            return null;
        }
        return input.length() <= max ? input : input.substring(0, max);
    }
}