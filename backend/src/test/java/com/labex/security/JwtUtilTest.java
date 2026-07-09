package com.labex.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class JwtUtilTest {
    @Test
    void generatesHs512TokenWhenConfiguredSecretIsShorterThanSixtyFourBytes() {
        JwtUtil jwtUtil = new JwtUtil();
        ReflectionTestUtils.setField(jwtUtil, "secret", "change-me-to-a-long-random-secret-at-least-64-bytes");
        ReflectionTestUtils.setField(jwtUtil, "expiration", 86400000L);
        ReflectionTestUtils.setField(jwtUtil, "prefix", "Bearer ");
        jwtUtil.init();

        String token = jwtUtil.generateToken(7, "7", "USER");

        assertThat(jwtUtil.validateToken(token)).isTrue();
        assertThat(jwtUtil.getUserIdFromToken(token)).isEqualTo(7);
        assertThat(jwtUtil.getUsernameFromToken(token)).isEqualTo("7");
        assertThat(jwtUtil.getRoleFromToken(token)).isEqualTo("USER");
    }
}
