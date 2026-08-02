package com.labex.labexagent.llm;

public enum CacheTelemetryStatus {
    DISABLED("disabled"),
    NOT_REPORTED("not_reported"),
    MISS("miss"),
    WRITE_ONLY("write_only"),
    HIT("hit");

    private final String value;

    CacheTelemetryStatus(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }

    public static CacheTelemetryStatus fromValue(String value) {
        if (value != null) {
            for (CacheTelemetryStatus status : values()) {
                if (status.value.equalsIgnoreCase(value)) return status;
            }
        }
        return NOT_REPORTED;
    }
}
