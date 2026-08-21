package com.labex.monitor.event;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/** 应用启动事件落库，供运维事件流回溯启动历史。 */
@Component
public class OpsStartupEventListener {

    private final OpsEventRecordingService eventService;

    public OpsStartupEventListener(OpsEventRecordingService eventService) {
        this.eventService = eventService;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() {
        eventService.record("APP_STARTED", "info", "application", null, null,
                "application started", null, "system");
    }
}