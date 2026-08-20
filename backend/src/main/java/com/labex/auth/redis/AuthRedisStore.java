package com.labex.auth.redis;

import java.time.Duration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

/** 认证域 Redis 访问适配器，封装原子计数和一次性消费。 */
@Component
public class AuthRedisStore {
    private static final RedisScript<Long> INCREMENT_SCRIPT = new DefaultRedisScript<>(
            "local n=redis.call('incr',KEYS[1]); "
                    + "if n == 1 then redis.call('expire',KEYS[1],ARGV[1]); end; return n",
            Long.class);
    private static final RedisScript<String> CONSUME_SCRIPT = new DefaultRedisScript<>(
            "local v=redis.call('get',KEYS[1]); "
                    + "if v then redis.call('del',KEYS[1]); end; return v",
            String.class);

    private final StringRedisTemplate redis;

    public AuthRedisStore(StringRedisTemplate redis) {
        this.redis = redis;
    }

    public long increment(String key, Duration ttl) {
        try {
            Long count = redis.execute(INCREMENT_SCRIPT, java.util.List.of(key),
                    String.valueOf(Math.max(1, ttl.toSeconds())));
            return count == null ? 0 : count;
        } catch (RuntimeException failure) {
            throw new AuthRedisUnavailableException(failure);
        }
    }

    public String get(String key) {
        try {
            return redis.opsForValue().get(key);
        } catch (RuntimeException failure) {
            throw new AuthRedisUnavailableException(failure);
        }
    }

    public void put(String key, String value, Duration ttl) {
        try {
            redis.opsForValue().set(key, value, ttl);
        } catch (RuntimeException failure) {
            throw new AuthRedisUnavailableException(failure);
        }
    }

    public String consume(String key) {
        try {
            return redis.execute(CONSUME_SCRIPT, java.util.List.of(key));
        } catch (RuntimeException failure) {
            throw new AuthRedisUnavailableException(failure);
        }
    }

    public void delete(String key) {
        try {
            redis.delete(key);
        } catch (RuntimeException failure) {
            throw new AuthRedisUnavailableException(failure);
        }
    }

    public boolean ping() {
        try (var connection = redis.getConnectionFactory().getConnection()) {
            return "PONG".equalsIgnoreCase(connection.ping());
        } catch (RuntimeException failure) {
            return false;
        }
    }
}
