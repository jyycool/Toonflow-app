package com.toonflow;

import com.toonflow.util.JwtUtil;
import io.jsonwebtoken.Claims;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * JWT 工具单元测试（不依赖 Spring 上下文）
 */
class JwtUtilTest {

    private static final String SECRET = "toonflow-test-secret-key-must-be-long-enough-for-hs256";

    @Test
    void createAndParseToken_roundTrip() {
        Map<String, Object> claims = Map.of("id", 1, "name", "admin");
        String token = JwtUtil.createToken(claims, 3600, SECRET);
        assertNotNull(token);

        Claims parsed = JwtUtil.parseToken(token, SECRET);
        assertEquals(1, parsed.get("id", Integer.class));
        assertEquals("admin", parsed.get("name", String.class));
    }

    @Test
    void parseToken_withWrongSecret_throws() {
        String token = JwtUtil.createToken(Map.of("id", 1), 3600, SECRET);
        assertThrows(Exception.class,
                () -> JwtUtil.parseToken(token, "a-completely-different-secret-key-value-here"));
    }

    @Test
    void expiredToken_throws() throws InterruptedException {
        String token = JwtUtil.createToken(Map.of("id", 1), 1, SECRET);
        Thread.sleep(1100);
        assertThrows(Exception.class, () -> JwtUtil.parseToken(token, SECRET));
    }
}
