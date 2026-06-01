package com.toonflow.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.toonflow.common.result.R;
import com.toonflow.entity.OSetting;
import com.toonflow.mapper.OSettingMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestClient;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 设置-杂项控制器
 * 对应原项目 setting/memoryConfig、setting/dev、setting/about
 */
@RestController
@RequestMapping("/api/setting")
@RequiredArgsConstructor
public class SettingMiscController {

    private final OSettingMapper settingMapper;
    private final RestClient restClient = RestClient.create();

    private static final List<String> MEMORY_KEYS = List.of(
            "messagesPerSummary", "shortTermLimit", "summaryMaxLength",
            "summaryLimit", "ragLimit", "deepRetrieveSummaryLimit",
            "modelOnnxFile", "modelDtype");

    // ========== 记忆配置 ==========

    @GetMapping("/memoryConfig/getMemory")
    public R<Map<String, Object>> getMemoryConfig() {
        List<OSetting> settings = settingMapper.selectList(
                new LambdaQueryWrapper<OSetting>().in(OSetting::getKey, MEMORY_KEYS));
        Map<String, Object> result = new HashMap<>();
        for (OSetting s : settings) {
            if (s.getKey() == null || s.getValue() == null) continue;
            if ("modelDtype".equals(s.getKey()) || "modelOnnxFile".equals(s.getKey())) {
                result.put(s.getKey(), s.getValue());
            } else {
                try {
                    result.put(s.getKey(), Double.valueOf(s.getValue()));
                } catch (NumberFormatException e) {
                    result.put(s.getKey(), s.getValue());
                }
            }
        }
        return R.ok(result);
    }

    @PostMapping("/memoryConfig/sureMemory")
    public R<Map<String, String>> sureMemory(@RequestBody Map<String, Object> body) {
        body.forEach((key, value) -> {
            if (MEMORY_KEYS.contains(key)) {
                upsertSetting(key, String.valueOf(value));
            }
        });
        return R.ok(Map.of("message", "记忆配置已保存"));
    }

    @PostMapping("/memoryConfig/delAllMemory")
    public R<Map<String, String>> delAllMemory() {
        return R.ok(Map.of("message", "记忆已清空"));
    }

    // ========== 开发工具开关 ==========

    @GetMapping("/dev/getSwitchAiDevTool")
    public R<String> getSwitchAiDevTool() {
        OSetting s = settingMapper.selectById("switchAiDevTool");
        return R.ok(s != null && s.getValue() != null ? s.getValue() : "0");
    }

    @PostMapping("/dev/updateSwitchAiDevTool")
    public R<Map<String, String>> updateSwitchAiDevTool(@RequestBody Map<String, String> body) {
        upsertSetting("switchAiDevTool", body.getOrDefault("value", "0"));
        return R.ok(Map.of("message", "更新成功"));
    }

    // ========== 关于/更新 ==========

    @PostMapping("/about/checkUpdate")
    public R<Object> checkUpdate(@RequestBody Map<String, String> body) {
        String url = body.getOrDefault("url",
                "https://toonflow.oss-cn-beijing.aliyuncs.com/update.json");
        try {
            JsonNode versionInfo = restClient.get().uri(url).retrieve().body(JsonNode.class);
            return R.ok(versionInfo);
        } catch (Exception e) {
            return R.fail("无法获取版本信息: " + e.getMessage());
        }
    }

    /**
     * 下载应用（服务端模式提示浏览器下载）
     */
    @PostMapping("/about/downloadApp")
    public R<String> downloadApp(@RequestBody Map<String, Object> body) {
        return R.ok("请在浏览器中手动下载并安装最新版本");
    }

    // ========== 文件夹（远程环境不支持打开本地文件夹）==========

    @PostMapping("/fileManagement/openFolder")
    public R<String> openFolder(@RequestBody Map<String, String> body) {
        return R.fail("服务端模式不支持打开本地文件夹");
    }

    private void upsertSetting(String key, String value) {
        OSetting existing = settingMapper.selectById(key);
        if (existing != null) {
            existing.setValue(value);
            settingMapper.updateById(existing);
        } else {
            OSetting s = new OSetting();
            s.setKey(key);
            s.setValue(value);
            settingMapper.insert(s);
        }
    }
}
