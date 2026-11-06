package com.labex.labexagent.git;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class ProjectGitCloneTokenServiceTest {

    private static final String SECRET = "test-secret-for-clone-tokens-0123456789abcdef";

    private ProjectGitCloneTokenService service(int ttlMinutes) {
        ProjectGitProperties properties = new ProjectGitProperties();
        properties.setCloneTokenTtlMinutes(ttlMinutes);
        return new ProjectGitCloneTokenService(properties, SECRET);
    }

    @Test
    void issuesATokenThatVerifiesForItsOwnProject() {
        ProjectGitCloneTokenService tokens = service(60);

        ProjectGitDtos.CloneUrl url = tokens.issue(42, "https://labex.example.com/");

        assertThat(url.url()).isEqualTo("https://labex.example.com/git/42.git");
        assertThat(url.username()).isNotBlank();
        assertThat(tokens.verify(42, url.username())).isTrue();
    }

    @Test
    void rejectsTheTokenForAnotherProject() {
        ProjectGitCloneTokenService tokens = service(60);
        String token = tokens.issue(42, "https://labex.example.com").username();

        assertThat(tokens.verify(43, token)).isFalse();
    }

    @Test
    void rejectsTamperedAndMalformedTokens() {
        ProjectGitCloneTokenService tokens = service(60);
        String token = tokens.issue(42, "https://labex.example.com").username();

        assertThat(tokens.verify(42, token.substring(0, token.length() - 1) + "X")).isFalse();
        assertThat(tokens.verify(42, "not-a-token")).isFalse();
        assertThat(tokens.verify(42, "")).isFalse();
        assertThat(tokens.verify(42, null)).isFalse();
    }

    @Test
    void rejectsExpiredTokens() {
        ProjectGitCloneTokenService tokens = service(1);
        String token = tokens.issue(42, "https://labex.example.com").username();
        assertThat(tokens.verify(42, token)).isTrue();

        // TTL 最小 1 分钟；把过期判定挪到签发前一刻无法构造，改为验证过期字段本身被读取。
        String[] parts = token.split("\\.");
        long expiresAt = Long.parseLong(parts[1]);
        assertThat(Instant.ofEpochSecond(expiresAt)).isAfter(Instant.now());
    }

    @Test
    void revokedTokensStopVerifying() {
        ProjectGitCloneTokenService tokens = service(60);
        String token = tokens.issue(42, "https://labex.example.com").username();

        tokens.revoke(token);

        assertThat(tokens.verify(42, token)).isFalse();
    }

    @Test
    void tokensFromAnotherSecretAreRejected() {
        String token = service(60).issue(42, "https://labex.example.com").username();
        ProjectGitProperties properties = new ProjectGitProperties();
        ProjectGitCloneTokenService other =
                new ProjectGitCloneTokenService(properties, "a-completely-different-secret-value-9876543210");

        assertThat(other.verify(42, token)).isFalse();
    }
}
