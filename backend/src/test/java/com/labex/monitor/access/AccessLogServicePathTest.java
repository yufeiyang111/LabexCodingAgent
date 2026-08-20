package com.labex.monitor.access;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class AccessLogServicePathTest {

    @Test
    void normalizesNumericSegmentsToIdPlaceholder() {
        assertThat(AccessLogService.normalizePath("/student/projects/12/files"))
                .isEqualTo("/student/projects/:id/files");
        assertThat(AccessLogService.normalizePath("/student/projects/12/agent/ask"))
                .isEqualTo("/student/projects/:id/agent/ask");
        assertThat(AccessLogService.normalizePath("/auth/login")).isEqualTo("/auth/login");
    }

    @Test
    void keepsMixedAndNonNumericSegments() {
        assertThat(AccessLogService.normalizePath("/student/projects/abc/readme.md"))
                .isEqualTo("/student/projects/abc/readme.md");
        assertThat(AccessLogService.normalizePath("/ws/chat/123")).isEqualTo("/ws/chat/:id");
    }

    @Test
    void handlesNullAndEmpty() {
        assertThat(AccessLogService.normalizePath(null)).isEqualTo("/");
        assertThat(AccessLogService.normalizePath("")).isEqualTo("/");
        assertThat(AccessLogService.normalizePath("/")).isEqualTo("/");
    }
}