package com.labex.labexagent.commandsecurity;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class CommandRedactorTest {

    @Test
    void removesCommonSecretFormsBeforePublicSerialization() {
        String source = "curl --token=top-secret Authorization Bearer abc123 "
                + "\"apiKey\": \"json-secret\" ghp_abcdefgh";

        String redacted = CommandRedactor.redact(source);

        assertThat(redacted)
                .doesNotContain("top-secret")
                .doesNotContain("abc123")
                .doesNotContain("json-secret")
                .doesNotContain("ghp_abcdefgh")
                .contains("<redacted>");
    }

    @Test
    void boundsOversizedPublicOutput() {
        String redacted = CommandRedactor.redact("x".repeat(8_100));

        assertThat(redacted).endsWith("[output truncated]");
        assertThat(redacted.length()).isLessThanOrEqualTo(8_030);
    }
}
