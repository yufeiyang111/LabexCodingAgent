package com.labex.auth.redis;

/** Redis 不可用时的内部异常，避免把底层连接信息返回给客户端。 */
public class AuthRedisUnavailableException extends RuntimeException {
    public AuthRedisUnavailableException(Throwable cause) {
        super(cause);
    }
}
