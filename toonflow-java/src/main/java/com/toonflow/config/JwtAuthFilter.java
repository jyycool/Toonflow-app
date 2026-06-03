package com.toonflow.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.toonflow.common.result.R;
import com.toonflow.entity.OSetting;
import com.toonflow.mapper.OSettingMapper;
import com.toonflow.util.JwtUtil;
import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Collections;

@Slf4j
@Component
@RequiredArgsConstructor
public class JwtAuthFilter extends OncePerRequestFilter {

    private final OSettingMapper settingMapper;
    private final ObjectMapper objectMapper;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String path = request.getServletPath();

        // 白名单
        if (path.equals("/api/login/login") || path.startsWith("/socket.io")) {
            chain.doFilter(request, response);
            return;
        }

        OSetting tokenKeySetting = settingMapper.selectById("tokenKey");
        if (tokenKeySetting == null) {
            writeError(response, 444, "服务器秘钥未配置，请联系管理员");
            return;
        }

        String raw = request.getHeader("Authorization");
        if (raw == null) raw = request.getParameter("token");
        if (raw == null) {
            writeError(response, 401, "未提供token");
            return;
        }
        String token = raw.replace("Bearer ", "").trim();

        try {
            Claims claims = JwtUtil.parseToken(token, tokenKeySetting.getValue());
            UsernamePasswordAuthenticationToken auth =
                    new UsernamePasswordAuthenticationToken(claims, null, Collections.emptyList());
            SecurityContextHolder.getContext().setAuthentication(auth);
            chain.doFilter(request, response);
        } catch (Exception e) {
            writeError(response, 401, "无效的token");
        }
    }

    private void writeError(HttpServletResponse response, int status, String message) throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE + ";charset=UTF-8");
        response.getWriter().write(objectMapper.writeValueAsString(R.fail(status, message)));
    }
}
