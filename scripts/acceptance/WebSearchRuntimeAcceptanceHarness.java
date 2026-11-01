import com.fasterxml.jackson.databind.ObjectMapper;
import com.labex.entity.StudentProject;
import com.labex.labexagent.runtime.AgentContext;
import com.labex.labexagent.websearch.*;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

public final class WebSearchRuntimeAcceptanceHarness {
    private static final String HOST = "127.0.0.1";
    private static final WebSearchRequest REQUEST = new WebSearchRequest("runtime acceptance query", 5, "fallback", "auto", null);
    private static int redisPort;
    private static int timeoutSeconds;
    private static String prefix;

    public static void main(String[] args) throws Exception {
        if (args.length < 4) throw new IllegalArgumentException("mode redisPort timeoutSeconds prefix");
        String mode = args[0];
        redisPort = Integer.parseInt(args[1]);
        timeoutSeconds = Integer.parseInt(args[2]);
        prefix = args[3];
        switch (mode) {
            case "writer" -> writer();
            case "reader" -> reader();
            case "shared" -> shared();
            case "isolation" -> isolation();
            case "parallel" -> parallel();
            case "corrupt" -> corrupt();
            case "ttl" -> ttl();
            case "timeout" -> timeout();
            default -> throw new IllegalArgumentException("unknown mode " + mode);
        }
    }

    private static void writer() throws Exception {
        WebSearchCache cache = cache(redis());
        cache.put(WebSearchProviderId.EXA, REQUEST, context(7, 42), response("writer-result"));
        System.out.println("MODE=writer");
        System.out.println("KEY=" + key());
        System.out.println("TTL_SECONDS=" + redis().getExpire(key()));
    }

    private static void reader() {
        WebSearchResponse value = cache(redis()).get(WebSearchProviderId.EXA, REQUEST, context(7, 42));
        System.out.println("MODE=reader");
        System.out.println("HIT=" + (value != null));
        System.out.println("CONTENT=" + (value == null ? "none" : value.content()));
    }

    private static void shared() {
        WebSearchCache a = cache(redis());
        WebSearchCache b = cache(redis());
        a.put(WebSearchProviderId.EXA, REQUEST, context(7, 42), response("shared-result"));
        WebSearchResponse hit = b.get(WebSearchProviderId.EXA, REQUEST, context(7, 42));
        System.out.println("MODE=shared");
        System.out.println("HIT=" + (hit != null));
        System.out.println("CONTENT=" + (hit == null ? "none" : hit.content()));
    }

    private static void isolation() {
        WebSearchCache cache = cache(redis());
        cache.put(WebSearchProviderId.EXA, REQUEST, context(7, 42), response("isolated-result"));
        WebSearchResponse userMismatch = cache.get(WebSearchProviderId.EXA, REQUEST, context(8, 42));
        WebSearchResponse projectMismatch = cache.get(WebSearchProviderId.EXA, REQUEST, context(7, 43));
        WebSearchResponse ownerMismatch = cache.get(WebSearchProviderId.EXA, REQUEST, contextWithOwner(7, 42, 8));
        WebSearchResponse match = cache.get(WebSearchProviderId.EXA, REQUEST, context(7, 42));
        System.out.println("MODE=isolation");
        System.out.println("MATCH_HIT=" + (match != null));
        System.out.println("USER_MISMATCH_HIT=" + (userMismatch != null));
        System.out.println("PROJECT_MISMATCH_HIT=" + (projectMismatch != null));
        System.out.println("OWNER_MISMATCH_HIT=" + (ownerMismatch != null));
    }

    private static void parallel() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        WebSearchProvider provider = provider(WebSearchProviderId.PARALLEL, calls);
        WebSearchCache cache = cache(redis());
        WebSearchProviderSelector selector = new WebSearchProviderSelector(properties("parallel"), List.of(provider), cache);
        selector.search(REQUEST, context(7, 42));
        selector.search(REQUEST, context(7, 42));
        System.out.println("MODE=parallel");
        System.out.println("PROVIDER_CALLS=" + calls.get());
        var keys = redis().keys(prefix + ":*");
        System.out.println("MATCHING_KEYS=" + (keys == null ? 0 : keys.size()));
    }

    private static void corrupt() throws Exception {
        StringRedisTemplate redis = redis();
        redis.opsForValue().set(key(), "{not-json", Duration.ofSeconds(60));
        WebSearchResponse value = cache(redis).get(WebSearchProviderId.EXA, REQUEST, context(7, 42));
        System.out.println("MODE=corrupt");
        System.out.println("RESULT_NULL=" + (value == null));
        System.out.println("KEY_EXISTS_AFTER_READ=" + Boolean.TRUE.equals(redis.hasKey(key())));
    }

    private static void ttl() throws Exception {
        WebSearchProperties properties = properties("exa");
        properties.setCacheTtlSeconds(1);
        StringRedisTemplate redis = redis();
        cache(properties, redis).put(WebSearchProviderId.EXA, REQUEST, context(7, 42), response("ttl-result"));
        Long before = redis.getExpire(key());
        Thread.sleep(2200L);
        String after = redis.opsForValue().get(key());
        System.out.println("MODE=ttl");
        System.out.println("TTL_BEFORE=" + before);
        System.out.println("VALUE_AFTER_2200MS=" + after);
    }

    private static void timeout() {
        WebSearchProperties properties = properties("exa");
        LettuceConnectionFactory factory = factory(redisPort + 10, Duration.ofSeconds(timeoutSeconds), Duration.ofSeconds(timeoutSeconds));
        factory.afterPropertiesSet();
        StringRedisTemplate redis = new StringRedisTemplate(factory);
        redis.afterPropertiesSet();
        long started = System.nanoTime();
        WebSearchResponse value = new WebSearchCache(properties, redis, new ObjectMapper())
                .get(WebSearchProviderId.EXA, REQUEST, context(7, 42));
        long elapsedMs = (System.nanoTime() - started) / 1_000_000L;
        System.out.println("MODE=timeout");
        System.out.println("RESULT_NULL=" + (value == null));
        System.out.println("ELAPSED_MS=" + elapsedMs);
        factory.destroy();
    }

    private static WebSearchProvider provider(WebSearchProviderId id, AtomicInteger calls) {
        return new WebSearchProvider() {
            public WebSearchProviderId id() { return id; }
            public boolean isEnabled() { return true; }
            public WebSearchResponse search(WebSearchRequest request, AgentContext context) {
                calls.incrementAndGet();
                return response("provider-result");
            }
        };
    }

    private static WebSearchCache cache(StringRedisTemplate redis) {
        return cache(properties("exa"), redis);
    }

    private static WebSearchCache cache(WebSearchProperties properties, StringRedisTemplate redis) {
        return new WebSearchCache(properties, redis, new ObjectMapper());
    }

    private static WebSearchProperties properties(String provider) {
        WebSearchProperties properties = new WebSearchProperties();
        properties.setProvider(provider);
        properties.setCacheEnabled(true);
        properties.setCacheRedisEnabled(true);
        properties.setCacheTtlSeconds(60);
        properties.setCacheRedisKeyPrefix(prefix);
        return properties;
    }

    private static WebSearchResponse response(String content) {
        return new WebSearchResponse(WebSearchProviderId.EXA, content);
    }

    private static StringRedisTemplate redis() {
        LettuceConnectionFactory factory = factory(redisPort, Duration.ofSeconds(timeoutSeconds), Duration.ofSeconds(timeoutSeconds));
        factory.afterPropertiesSet();
        StringRedisTemplate template = new StringRedisTemplate(factory);
        template.afterPropertiesSet();
        return template;
    }

    private static LettuceConnectionFactory factory(int port, Duration commandTimeout, Duration connectTimeout) {
        RedisStandaloneConfiguration standalone = new RedisStandaloneConfiguration(HOST, port);
        LettuceClientConfiguration client = LettuceClientConfiguration.builder()
                .commandTimeout(commandTimeout)
                .clientOptions(io.lettuce.core.ClientOptions.builder()
                        .socketOptions(io.lettuce.core.SocketOptions.builder()
                                .connectTimeout(connectTimeout)
                                .build())
                        .build())
                .build();
        return new LettuceConnectionFactory(standalone, client);
    }

    private static AgentContext context(int studentId, int projectId) {
        return contextWithOwner(studentId, projectId, studentId);
    }

    private static AgentContext contextWithOwner(int studentId, int projectId, int ownerId) {
        StudentProject project = new StudentProject();
        project.setProjectId(projectId);
        project.setStudentId(ownerId);
        return new AgentContext("runtime-session", studentId, project, "runtime-conversation", 1L, null, List.of(), 0);
    }

    private static String key() throws Exception {
        WebSearchCache cache = new WebSearchCache(properties("exa"), null, new ObjectMapper());
        var method = WebSearchCache.class.getDeclaredMethod("cacheKey", WebSearchProviderId.class, WebSearchRequest.class, AgentContext.class);
        method.setAccessible(true);
        Object key = method.invoke(cache, WebSearchProviderId.EXA, REQUEST, context(7, 42));
        var redisKey = key.getClass().getDeclaredMethod("redisKey");
        redisKey.setAccessible(true);
        return (String) redisKey.invoke(key);
    }
}
