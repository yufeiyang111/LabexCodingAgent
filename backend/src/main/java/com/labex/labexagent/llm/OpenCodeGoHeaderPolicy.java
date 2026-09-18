package com.labex.labexagent.llm;

import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * OpenCode Go（opencode.ai/zen/go）托管网关要求的请求头。
 *
 * <p>官方要求（https://opencode.ai/docs/go/ 的 "Where can I use it?"）：
 * <ol>
 *   <li>请求形态像编码 Agent；</li>
 *   <li>用<b>客户端自己的 User-Agent</b> 标识自身（例 {@code my-coding-agent/1.0}），
 *       而不是通用 SDK / HTTP 库名——JDK 默认发的是 {@code Java-http-client/21}、{@code Java/21}；</li>
 *   <li>每个会话在 {@code x-opencode-session} 里携带<b>稳定</b>的 session ID，供网关做路由与
 *       prompt cache 亲和。</li>
 * </ol>
 *
 * <p>自 2026-09-05 起，缺少 {@code x-opencode-session} 的请求被网关直接拒绝（HTTP 400）；
 * 社区多个客户端（LobeHub、AxonHub、jcode、Kilo Code 等）都是靠补这个头恢复的。
 *
 * <p><b>作用范围</b>：只命中 OpenCode Go 端点——主机为 {@code opencode.ai} 或其子域，
 * 或者路径含 {@code /zen/go}（给在 opencode.ai 前面套了反向代理 / 自建网关的部署留出口）。
 * 其余服务商（OpenAI / Anthropic / Ollama / 自建网关）返回空 map，请求头完全不被改写，
 * 因此不会影响既有 provider 的行为。
 *
 * <p><b>已知取舍</b>：路径规则优先于域名判定，所以路径含 {@code /zen/go} 的第三方端点也会
 * 收到 {@code x-opencode-session}。该值是不可逆摘要（无用户明文 / 会话明文），无隐私影响；
 * 换来的是反代与自建网关部署无需额外配置即可生效。
 *
 * <p><b>实现参考</b>：本地 OpenCode 快照
 * {@code D:\opencode\opencode-dev}（1.17.4）{@code packages/opencode/src/session/llm/request.ts:177-191}。
 * 上游对 {@code providerID.startsWith("opencode")} 的模型发送
 * {@code x-opencode-project} / {@code x-opencode-session} / {@code x-opencode-request} /
 * {@code x-opencode-client} 与 {@code User-Agent: opencode/<version>}。
 * 本类<b>只复刻官方文档要求的两个头</b>（session + 自有 UA），未复制上游代码：
 * 其余三个 {@code x-opencode-*} 是 CLI 内部语义（project = 工作目录项目 ID、
 * request = 用户消息 ID、client = 客户端形态），本平台没有对应实体，
 * 编造取值（例如社区代理脚本那样谎报 {@code x-opencode-client: tui} 以规避免费档位限流）
 * 既不诚实、也可能被网关按未知客户端风控，故一律不发。
 * 上游同样给非 opencode 模型发 {@code x-session-affinity} / {@code X-Session-Id}，
 * 未来若需要跨 provider 的会话亲和再单独评估，不在本次范围内。
 * License：OpenCode 快照为 MIT，本类仅复刻设计思想，未复制实质代码。
 */
public final class OpenCodeGoHeaderPolicy {

    /** 官方要求的会话路由头：缺失即 HTTP 400。 */
    public static final String SESSION_HEADER = "x-opencode-session";

    /** 官方要求客户端以自有名字标识（不要用通用 HTTP 库名）。 */
    public static final String USER_AGENT_HEADER = "User-Agent";

    /** 与 WebFetch/MCP 等模块保持一致的客户端标识。 */
    public static final String CLIENT_USER_AGENT = "LabexAgent/1.0";

    private static final String OPEN_CODE_HOST = "opencode.ai";
    private static final String OPEN_CODE_GO_PATH = "/zen/go";
    private static final Map<String, String> NO_HEADERS = Map.of();

    /**
     * 无会话上下文的调用（连通性测试、模型列表等）仍必须带头，否则一样 400；
     * 这类调用没有会话可言，退化为进程内稳定的随机 ID——同进程内保持一致，
     * 重启后变化，不影响真正的会话路由（Agent 对话总是带 {@code withSessionId} 注入的会话 ID）。
     */
    private static final String PROCESS_SESSION_FALLBACK =
            "labex-" + UUID.randomUUID().toString().replace("-", "");

    private OpenCodeGoHeaderPolicy() {
    }

    /** 主机为 opencode.ai 子域、或路径含 /zen/go 时命中；无法解析或不符合一律 false。 */
    public static boolean targetsOpenCodeGo(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) {
            return false;
        }
        URI uri;
        try {
            uri = new URI(baseUrl.trim());
        } catch (URISyntaxException e) {
            return false;
        }
        String host = uri.getHost();
        if (host != null) {
            String normalizedHost = host.toLowerCase(Locale.ROOT);
            if (normalizedHost.equals(OPEN_CODE_HOST) || normalizedHost.endsWith("." + OPEN_CODE_HOST)) {
                return true;
            }
        }
        String path = uri.getPath();
        return path != null && path.toLowerCase(Locale.ROOT).contains(OPEN_CODE_GO_PATH);
    }

    /** 本次请求需附加的请求头。非 OpenCode Go 端点返回空 map。 */
    public static Map<String, String> headers(String baseUrl, String sessionId) {
        if (!targetsOpenCodeGo(baseUrl)) {
            return NO_HEADERS;
        }
        String effectiveSession = sessionId == null || sessionId.isBlank()
                ? PROCESS_SESSION_FALLBACK : sessionId.trim();
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put(USER_AGENT_HEADER, CLIENT_USER_AGENT);
        headers.put(SESSION_HEADER, effectiveSession);
        return headers;
    }

    /** HttpURLConnection 通道（流式请求）用。 */
    public static void apply(HttpURLConnection connection, String baseUrl, String sessionId) {
        if (connection == null) {
            return;
        }
        headers(baseUrl, sessionId).forEach(connection::setRequestProperty);
    }

    /**
     * 验收/排障用会话指纹：命中 OpenCode Go 时返回会话值的前 8 位（足够判断"同一会话内是否恒定"），
     * 未命中返回 {@code "-"}。
     *
     * <p>存在的理由：{@code x-opencode-session} 由<b>后端</b>注入，浏览器 DevTools 看不到它，
     * 因此没有这条日志就无法在真实环境验收"请求头是否生效 / 会话内是否恒定"。
     * 只取前 8 位是刻意的——完整值没有必要出现在日志里，且该值本身是不可逆摘要。
     */
    public static String sessionFingerprint(String baseUrl, String sessionId) {
        Map<String, String> headers = headers(baseUrl, sessionId);
        if (headers.isEmpty()) {
            return "-";
        }
        String effective = headers.get(SESSION_HEADER);
        return effective == null || effective.length() < 8 ? "-" : effective.substring(0, 8);
    }
}
