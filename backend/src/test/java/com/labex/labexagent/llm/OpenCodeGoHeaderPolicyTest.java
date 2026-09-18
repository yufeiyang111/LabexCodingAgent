package com.labex.labexagent.llm;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * OpenCode Go 请求头策略：只命中 opencode.ai / 含 /zen/go 的端点，
 * 其余服务商端点必须完全不被改写（不能影响既有 provider）。
 */
class OpenCodeGoHeaderPolicyTest {

    @Test
    void matchesOpenCodeHostsIncludingSubdomains() {
        assertThat(OpenCodeGoHeaderPolicy.targetsOpenCodeGo("https://opencode.ai/zen/go/v1")).isTrue();
        assertThat(OpenCodeGoHeaderPolicy.targetsOpenCodeGo("https://api.opencode.ai/zen/go/v1")).isTrue();
        assertThat(OpenCodeGoHeaderPolicy.targetsOpenCodeGo("  https://opencode.ai/zen/go/v1  ")).isTrue();
    }

    @Test
    void matchesProxyDeploymentsByGoPath() {
        assertThat(OpenCodeGoHeaderPolicy.targetsOpenCodeGo("http://127.0.0.1:8080/zen/go/v1")).isTrue();
        assertThat(OpenCodeGoHeaderPolicy.targetsOpenCodeGo("https://gateway.internal/proxy/zen/go/v1")).isTrue();
        // 已知取舍：/zen/go 路径优先于域名判定（为覆盖反代/自建网关），
        // 因此命中该路径的第三方端点也会收到会话摘要头——该值是不可逆摘要，无隐私影响。
        assertThat(OpenCodeGoHeaderPolicy.targetsOpenCodeGo("https://any-host.example/zen/go/v1")).isTrue();
    }

    @Test
    void doesNotMatchOtherProvidersOrHostLookalikes() {
        assertThat(OpenCodeGoHeaderPolicy.targetsOpenCodeGo("https://api.openai.com/v1")).isFalse();
        assertThat(OpenCodeGoHeaderPolicy.targetsOpenCodeGo("https://api.anthropic.com")).isFalse();
        assertThat(OpenCodeGoHeaderPolicy.targetsOpenCodeGo("http://localhost:11434/v1")).isFalse();
        assertThat(OpenCodeGoHeaderPolicy.targetsOpenCodeGo("https://api.minimaxi.com/v1")).isFalse();
        // 相似但不同的域名不能靠域名命中，否则会把会话头泄漏给无关服务商。
        assertThat(OpenCodeGoHeaderPolicy.targetsOpenCodeGo("https://evil-opencode.ai/v1")).isFalse();
        assertThat(OpenCodeGoHeaderPolicy.targetsOpenCodeGo("https://opencode.ai.evil.com/v1")).isFalse();
        assertThat(OpenCodeGoHeaderPolicy.targetsOpenCodeGo("https://notopencode.ai/v1")).isFalse();
        assertThat(OpenCodeGoHeaderPolicy.targetsOpenCodeGo("https://opencodeai.com/v1")).isFalse();
    }

    @Test
    void toleratesBlankAndMalformedBaseUrls() {
        assertThat(OpenCodeGoHeaderPolicy.targetsOpenCodeGo(null)).isFalse();
        assertThat(OpenCodeGoHeaderPolicy.targetsOpenCodeGo("")).isFalse();
        assertThat(OpenCodeGoHeaderPolicy.targetsOpenCodeGo("   ")).isFalse();
        assertThat(OpenCodeGoHeaderPolicy.targetsOpenCodeGo("not a url ::")).isFalse();
        assertThat(OpenCodeGoHeaderPolicy.headers(null, "s")).isEmpty();
        assertThat(OpenCodeGoHeaderPolicy.headers("https://api.openai.com/v1", "s")).isEmpty();
    }

    @Test
    void returnsEmptyHeadersForNonOpenCodeEndpoints() {
        assertThat(OpenCodeGoHeaderPolicy.headers("https://api.openai.com/v1", "labex-abc")).isEmpty();
        assertThat(OpenCodeGoHeaderPolicy.headers("http://localhost:11434/v1", null)).isEmpty();
    }

    @Test
    void sendsRequiredSessionHeaderAndOwnUserAgent() {
        Map<String, String> headers =
                OpenCodeGoHeaderPolicy.headers("https://opencode.ai/zen/go/v1", "labex-session-1");

        assertThat(headers)
                .containsEntry(OpenCodeGoHeaderPolicy.SESSION_HEADER, "labex-session-1")
                .containsEntry(OpenCodeGoHeaderPolicy.USER_AGENT_HEADER, OpenCodeGoHeaderPolicy.CLIENT_USER_AGENT);
        // 不自称 SDK / HTTP 库名。
        assertThat(headers.get(OpenCodeGoHeaderPolicy.USER_AGENT_HEADER)).doesNotContain("Java");
    }

    @Test
    void trimsSessionIdAndFallsBackToAStableValueWhenMissing() {
        assertThat(OpenCodeGoHeaderPolicy.headers("https://opencode.ai/zen/go/v1", "  labex-x  "))
                .containsEntry(OpenCodeGoHeaderPolicy.SESSION_HEADER, "labex-x");

        String first = OpenCodeGoHeaderPolicy.headers("https://opencode.ai/zen/go/v1", null)
                .get(OpenCodeGoHeaderPolicy.SESSION_HEADER);
        String second = OpenCodeGoHeaderPolicy.headers("https://opencode.ai/zen/go/v1", "  ")
                .get(OpenCodeGoHeaderPolicy.SESSION_HEADER);

        assertThat(first).isNotBlank();
        // 无会话上下文的调用（连通性测试 / 模型列表）也必须带头，且同进程内保持稳定。
        assertThat(second).isEqualTo(first);
    }

    @Test
    void sessionFingerprintMarksOpenCodeHitsAndStaysStableWithinAConversation() {
        // 验收用指纹：命中 opencode 端点才有值，且同会话多轮取到同一个指纹。
        assertThat(OpenCodeGoHeaderPolicy.sessionFingerprint("https://opencode.ai/zen/go/v1", "labex-abc123456"))
                .isEqualTo("labex-ab");
        assertThat(OpenCodeGoHeaderPolicy.sessionFingerprint("https://opencode.ai/zen/go/v1", "labex-abc123456"))
                .isEqualTo(OpenCodeGoHeaderPolicy.sessionFingerprint("https://opencode.ai/zen/go/v1", "labex-abc123456"));
        // 换会话 → 指纹必须不同，否则无法据日志判断会话隔离。
        assertThat(OpenCodeGoHeaderPolicy.sessionFingerprint("https://opencode.ai/zen/go/v1", "labex-abc123456"))
                .isNotEqualTo(OpenCodeGoHeaderPolicy.sessionFingerprint("https://opencode.ai/zen/go/v1", "labex-zzz999999"));
    }

    @Test
    void sessionFingerprintIsBlankedForNonOpenCodeEndpoints() {
        assertThat(OpenCodeGoHeaderPolicy.sessionFingerprint("https://api.openai.com/v1", "labex-abc123456"))
                .isEqualTo("-");
        assertThat(OpenCodeGoHeaderPolicy.sessionFingerprint(null, "labex-abc123456")).isEqualTo("-");
    }
}
