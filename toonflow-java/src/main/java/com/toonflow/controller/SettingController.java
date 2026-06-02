package com.toonflow.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.toonflow.common.result.R;
import com.toonflow.entity.*;
import com.toonflow.mapper.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/setting")
@RequiredArgsConstructor
public class SettingController {

    private final OSettingMapper settingMapper;
    private final OVendorConfigMapper vendorConfigMapper;
    private final OAgentDeployMapper agentDeployMapper;
    private final OPromptMapper promptMapper;
    private final OUserMapper userMapper;

    @Value("${toonflow.data-dir}")
    private String dataDir;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final org.springframework.web.client.RestClient restClient =
            org.springframework.web.client.RestClient.create();

    // ========== 供应商配置 ==========

    @PostMapping("/vendorConfig/getVendorList")
    public R<List<Map<String, Object>>> getVendorList() {
        List<OVendorConfig> dbList = vendorConfigMapper.selectList(null);
        List<Map<String, Object>> result = new java.util.ArrayList<>();
        for (OVendorConfig item : dbList) {
            Map<String, Object> row = new java.util.LinkedHashMap<>();
            row.put("id", item.getId());
            row.put("enable", item.getEnable());
            // inputValues: 解析为对象
            try {
                row.put("inputValues", objectMapper.readValue(
                        item.getInputValues() != null ? item.getInputValues() : "{}", new TypeReference<Map<String,Object>>() {}));
            } catch (Exception e) { row.put("inputValues", Map.of()); }
            // models: 解析为数组
            try {
                row.put("models", objectMapper.readValue(
                        item.getModels() != null ? item.getModels() : "[]", new TypeReference<List<Object>>() {}));
            } catch (Exception e) { row.put("models", List.of()); }
            // 供应商元数据从 resources 读取
            String metaJson = loadVendorMeta(item.getId());
            if (!metaJson.isEmpty()) {
                try {
                    Map<String, Object> meta = objectMapper.readValue(metaJson, new TypeReference<>() {});
                    row.put("name", meta.getOrDefault("name", item.getId()));
                    row.put("author", meta.getOrDefault("author", ""));
                    row.put("description", meta.getOrDefault("description", ""));
                    row.put("version", meta.getOrDefault("version", "1.0"));
                    row.put("inputs", meta.getOrDefault("inputs", List.of()));
                    // code: 读取保存的 tsCode 文件
                    String code = loadVendorCode(item.getId());
                    row.put("code", code);
                } catch (Exception ignored) {}
            } else {
                row.put("name", item.getId());
                row.put("author", "");
                row.put("description", "");
                row.put("version", "1.0");
                row.put("inputs", List.of());
                row.put("code", "");
            }
            result.add(row);
        }
        // toonflow 排首位
        result.sort((a, b) -> "toonflow".equals(a.get("id")) ? -1 : "toonflow".equals(b.get("id")) ? 1 : 0);
        return R.ok(result);
    }

    private String loadVendorMeta(String vendorId) {
        try (java.io.InputStream is = getClass().getClassLoader()
                .getResourceAsStream("default-data/vendor-meta/" + vendorId + ".json")) {
            if (is == null) return "";
            return new String(is.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) { return ""; }
    }

    private String loadVendorCode(String vendorId) {
        try {
            java.nio.file.Path p = java.nio.file.Paths.get(dataDir, "vendor", vendorId + ".ts");
            if (java.nio.file.Files.exists(p))
                return java.nio.file.Files.readString(p, java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception ignored) {}
        // 回退：读取原始 vendor 目录
        try {
            java.nio.file.Path p = java.nio.file.Paths.get("/home/user/Toonflow-app/data/vendor", vendorId + ".ts");
            if (java.nio.file.Files.exists(p))
                return java.nio.file.Files.readString(p, java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception ignored) {}
        return "";
    }

    @PostMapping("/vendorConfig/addVendor")
    public R<Map<String, String>> addVendor(@RequestBody Map<String, Object> body) {
        // 前端传 tsCode，用正则提取 vendor.id
        String tsCode = (String) body.get("tsCode");
        String id = (String) body.get("id");
        if (id == null && tsCode != null) {
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("id\\s*:\\s*[\"']([^\"']+)[\"']").matcher(tsCode);
            if (m.find()) id = m.group(1);
        }
        if (id == null) throw new com.toonflow.common.exception.BusinessException("无法解析供应商id");
        if (id.contains(":")) throw new com.toonflow.common.exception.BusinessException("id不能包含英文冒号");
        if (vendorConfigMapper.selectById(id) != null)
            throw new com.toonflow.common.exception.BusinessException("供应商id已存在");
        OVendorConfig config = new OVendorConfig();
        config.setId(id);
        config.setEnable("toonflow".equals(id) ? 1 : 0);
        config.setInputValues("{}");
        config.setModels("[]");
        vendorConfigMapper.insert(config);
        // 保存 tsCode 到 vendor 目录
        if (tsCode != null) {
            try {
                java.nio.file.Path vendorDir = java.nio.file.Paths.get(dataDir, "vendor");
                java.nio.file.Files.createDirectories(vendorDir);
                java.nio.file.Files.writeString(vendorDir.resolve(id + ".ts"), tsCode,
                        java.nio.charset.StandardCharsets.UTF_8);
            } catch (Exception e) {
                log.warn("保存供应商代码失败: {}", e.getMessage());
            }
        }
        return R.ok(Map.of("message", "添加供应商成功"));
    }

    @PostMapping("/vendorConfig/enableVendor")
    public R<Map<String, String>> enableVendor(@RequestBody Map<String, Object> body) {
        String id = (String) body.get("id");
        Integer enable = (Integer) body.get("enable");
        OVendorConfig config = vendorConfigMapper.selectById(id);
        if (config != null) {
            config.setEnable(enable);
            vendorConfigMapper.updateById(config);
        }
        return R.ok(Map.of("message", "更新成功"));
    }

    @PostMapping("/vendorConfig/deleteVendor")
    public R<Map<String, String>> deleteVendor(@RequestBody Map<String, String> body) {
        vendorConfigMapper.deleteById(body.get("id"));
        return R.ok(Map.of("message", "删除成功"));
    }

    @PostMapping("/vendorConfig/updateVendorInputs")
    public R<Map<String, String>> updateVendorInputs(@RequestBody OVendorConfig vendor) {
        vendorConfigMapper.updateById(vendor);
        return R.ok(Map.of("message", "更新成功"));
    }

    @PostMapping("/vendorConfig/addVendorModel")
    public R<Map<String, String>> addVendorModel(@RequestBody Map<String, Object> body) {
        String vendorId = (String) body.get("vendorId");
        OVendorConfig config = vendorConfigMapper.selectById(vendorId);
        if (config != null) {
            // 更新 models JSON
            config.setModels(body.get("models").toString());
            vendorConfigMapper.updateById(config);
        }
        return R.ok(Map.of("message", "添加模型成功"));
    }

    @PostMapping("/vendorConfig/delVendorModel")
    public R<Map<String, String>> delVendorModel(@RequestBody Map<String, Object> body) {
        return R.ok(Map.of("message", "删除模型成功"));
    }

    @PostMapping("/vendorConfig/upVendorModel")
    public R<Map<String, String>> upVendorModel(@RequestBody Map<String, Object> body) {
        return R.ok(Map.of("message", "更新模型成功"));
    }

    /**
     * 通过链接获取厂商配置代码
     */
    @PostMapping("/vendorConfig/getCodeByLink")
    public R<String> getCodeByLink(@RequestBody Map<String, String> body) {
        String link = body.get("link");
        try {
            String text = restClient.get().uri(link).retrieve().body(String.class);
            return R.ok(text);
        } catch (Exception e) {
            return R.fail("获取代码失败: " + e.getMessage());
        }
    }

    /**
     * 更新厂商配置代码（保存结构化配置到 o_vendorConfig）
     */
    @PostMapping("/vendorConfig/updateCode")
    public R<Map<String, String>> updateCode(@RequestBody Map<String, Object> body) {
        String id = (String) body.get("id");
        if (id == null) throw new com.toonflow.common.exception.BusinessException("id不能为空");
        OVendorConfig config = vendorConfigMapper.selectById(id);
        if (config == null) {
            config = new OVendorConfig();
            config.setId(id);
            config.setEnable(0);
            config.setInputValues("{}");
            config.setModels("[]");
            vendorConfigMapper.insert(config);
        }
        // 保存 tsCode 到 vendor 目录
        String tsCode = (String) body.get("tsCode");
        if (tsCode != null) {
            try {
                java.nio.file.Path vendorDir = java.nio.file.Paths.get(dataDir, "vendor");
                java.nio.file.Files.createDirectories(vendorDir);
                java.nio.file.Files.writeString(vendorDir.resolve(id + ".ts"), tsCode,
                        java.nio.charset.StandardCharsets.UTF_8);
            } catch (Exception e) {
                log.warn("保存供应商代码失败: {}", e.getMessage());
            }
        }
        return R.ok(Map.of("message", "更新配置成功"));
    }

    // ========== Agent 部署配置 ==========

    @PostMapping("/agentDeploy/getAgentDeploy")
    public R<List<OAgentDeploy>> getAgentDeploy() {
        return R.ok(agentDeployMapper.selectList(null));
    }

    @PostMapping("/agentDeploy/deployAgentModel")
    public R<Map<String, String>> deployAgentModel(@RequestBody OAgentDeploy deploy) {
        agentDeployMapper.updateById(deploy);
        return R.ok(Map.of("message", "部署成功"));
    }

    @GetMapping("/agentDeploy/getAgentUseMode")
    public R<Map<String, String>> getAgentUseMode() {
        OSetting setting = settingMapper.selectById("agentUseMode");
        return R.ok(Map.of("mode", setting != null ? setting.getValue() : "0"));
    }

    @PostMapping("/agentDeploy/updateUseMode")
    public R<Map<String, String>> updateUseMode(@RequestBody Map<String, String> body) {
        OSetting setting = new OSetting();
        setting.setKey("agentUseMode");
        setting.setValue(body.get("mode"));
        settingMapper.updateById(setting);
        return R.ok(Map.of("message", "更新成功"));
    }

    @PostMapping("/agentDeploy/agentSetKey")
    public R<Map<String, String>> agentSetKey(@RequestBody OAgentDeploy deploy) {
        agentDeployMapper.updateById(deploy);
        return R.ok(Map.of("message", "设置成功"));
    }

    // ========== 提示词管理 ==========

    @PostMapping("/promptManage/getPrompt")
    public R<List<OPrompt>> getPrompt(@RequestBody(required = false) Map<String, String> body) {
        String type = body != null ? body.get("type") : null;
        LambdaQueryWrapper<OPrompt> wrapper = new LambdaQueryWrapper<>();
        if (type != null) wrapper.eq(OPrompt::getType, type);
        return R.ok(promptMapper.selectList(wrapper));
    }

    @PostMapping("/promptManage/updatePrompt")
    public R<Map<String, String>> updatePrompt(@RequestBody OPrompt prompt) {
        promptMapper.updateById(prompt);
        return R.ok(Map.of("message", "更新成功"));
    }

    // ========== 登录配置 ==========

    @GetMapping("/loginConfig/getUser")
    public R<OUser> getUser() {
        return R.ok(userMapper.selectById(1));
    }

    @PostMapping("/loginConfig/updateUserPwd")
    public R<Map<String, String>> updateUserPwd(@RequestBody Map<String, String> body) {
        OUser user = userMapper.selectById(1);
        if (user != null) {
            user.setPassword(body.get("password"));
            userMapper.updateById(user);
        }
        return R.ok(Map.of("message", "修改密码成功"));
    }

    // ========== DB 配置见 DataConfigController ==========

    // ========== 版本 ==========

    @PostMapping("/getTextModel")
    public R<List<OAgentDeploy>> getTextModel() {
        return R.ok(agentDeployMapper.selectList(
                new LambdaQueryWrapper<OAgentDeploy>().eq(OAgentDeploy::getType, "text")));
    }
}
