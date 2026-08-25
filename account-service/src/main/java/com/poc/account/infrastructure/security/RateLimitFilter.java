package com.poc.account.infrastructure.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@Component
public class RateLimitFilter extends OncePerRequestFilter {

    private static final int DEFAULT_LIMIT_PER_MINUTE = 10;

    private final StringRedisTemplate valkey;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final int limitPerMinute;

    public RateLimitFilter(StringRedisTemplate valkey,
            @org.springframework.beans.factory.annotation.Value(
                    "${auth.rate-limit-per-minute:" + DEFAULT_LIMIT_PER_MINUTE + "}") int limitPerMinute) {
        this.valkey = valkey;
        this.limitPerMinute = limitPerMinute;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return !("POST".equals(request.getMethod())
                && ("/api/v1/auth/login".equals(path) || "/api/v1/auth/signup".equals(path)));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        try {
            String key = "ratelimit:" + request.getRequestURI() + ":" + clientIp(request);
            Long count = valkey.opsForValue().increment(key);
            if (count != null && count == 1L) {
                valkey.expire(key, Duration.ofMinutes(1));
            }
            if (count != null && count > limitPerMinute) {
                writeTooManyRequests(response);
                return;
            }
        } catch (org.springframework.data.redis.RedisConnectionFailureException e) {
            chain.doFilter(request, response);
            return;
        }
        chain.doFilter(request, response);
    }

    private static String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        return forwarded != null ? forwarded.split(",")[0].trim() : request.getRemoteAddr();
    }

    private void writeTooManyRequests(HttpServletResponse response) throws IOException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", "https://poc.example/problems/rate-limited");
        body.put("title", "Too many requests");
        body.put("status", 429);
        body.put("code", "RATE_LIMITED");
        body.put("detail", "Retry shortly.");
        body.put("correlationId", UUID.randomUUID().toString());
        response.setStatus(429);
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        objectMapper.writeValue(response.getWriter(), body);
    }
}
