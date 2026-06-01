package com.toonflow.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.toonflow.common.exception.BusinessException;
import com.toonflow.common.result.R;
import com.toonflow.entity.OSetting;
import com.toonflow.entity.OUser;
import com.toonflow.mapper.OSettingMapper;
import com.toonflow.mapper.OUserMapper;
import com.toonflow.util.JwtUtil;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/login")
@RequiredArgsConstructor
public class LoginController {

    private final OUserMapper userMapper;
    private final OSettingMapper settingMapper;

    @PostMapping("/login")
    public R<Map<String, Object>> login(@Valid @RequestBody LoginRequest req) {
        OUser user = userMapper.selectOne(
                new LambdaQueryWrapper<OUser>().eq(OUser::getName, req.getUsername()));
        if (user == null) throw new BusinessException("登录失败");

        if (!req.getPassword().equals(user.getPassword()) || !req.getUsername().equals(user.getName())) {
            throw new BusinessException("用户名或密码错误");
        }

        OSetting tokenKeySetting = settingMapper.selectById("tokenKey");
        if (tokenKeySetting == null) throw new BusinessException("未找到tokenKey");

        Map<String, Object> claims = new HashMap<>();
        claims.put("id", user.getId());
        claims.put("name", user.getName());
        String token = JwtUtil.createToken(claims, 15552000L, tokenKeySetting.getValue());

        Map<String, Object> result = new HashMap<>();
        result.put("token", "Bearer " + token);
        result.put("name", user.getName());
        result.put("id", user.getId());
        return R.ok(result, "登录成功");
    }

    @Data
    public static class LoginRequest {
        @NotBlank
        private String username;
        @NotBlank
        private String password;
    }
}
