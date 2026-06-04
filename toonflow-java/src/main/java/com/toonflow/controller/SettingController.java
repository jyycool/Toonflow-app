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

import java.util.ArrayList;
import java.util.LinkedHashMap;
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
    private final com.toonflow.mapper.MemoriesMapper memoriesMapper;
    private final com.toonflow.ai.AiService aiService;

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
            // models: 解析为数组，将 modelId 字段别名为 modelName 以匹配 TS 原版
            try {
                List<Map<String, Object>> rawModels = objectMapper.readValue(
                        item.getModels() != null ? item.getModels() : "[]",
                        new TypeReference<List<Map<String, Object>>>() {});
                List<Map<String, Object>> models = rawModels.stream().map(m -> {
                    Map<String, Object> nm = new java.util.LinkedHashMap<>(m);
                    if (!nm.containsKey("modelName") && nm.containsKey("modelId")) {
                        nm.put("modelName", nm.get("modelId"));
                    }
                    return nm;
                }).collect(java.util.stream.Collectors.toList());
                row.put("models", models);
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
        Integer enable = body.get("enable") != null ? ((Number) body.get("enable")).intValue() : null;
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
    public R<Map<String, String>> updateVendorInputs(@RequestBody Map<String, Object> body) {
        String id = (String) body.get("id");
        OVendorConfig vendor = vendorConfigMapper.selectById(id);
        if (vendor == null) {
            vendor = new OVendorConfig();
            vendor.setId(id);
            vendor.setEnable(1);
            vendor.setModels("[]");
        }
        Object inputValues = body.get("inputValues");
        if (inputValues != null) {
            try {
                vendor.setInputValues(inputValues instanceof String
                        ? (String) inputValues
                        : objectMapper.writeValueAsString(inputValues));
            } catch (Exception e) {
                return R.fail("inputValues 序列化失败: " + e.getMessage());
            }
        }
        if (body.get("enable") != null) {
            Object en = body.get("enable");
            vendor.setEnable(en instanceof Boolean ? ((Boolean) en ? 1 : 0)
                    : Integer.parseInt(en.toString()));
        }
        if (vendorConfigMapper.selectById(id) == null) {
            vendorConfigMapper.insert(vendor);
        } else {
            vendorConfigMapper.updateById(vendor);
        }
        return R.ok(Map.of("message", "更新成功"));
    }

    @PostMapping("/vendorConfig/addVendorModel")
    public R<Map<String, String>> addVendorModel(@RequestBody Map<String, Object> body) {
        String id = (String) body.get("id");
        Object model = body.get("model");
        OVendorConfig config = vendorConfigMapper.selectById(id);
        if (config != null && model != null) {
            try {
                List<Object> existingModels = objectMapper.readValue(
                        config.getModels() != null ? config.getModels() : "[]",
                        new TypeReference<List<Object>>() {});
                existingModels.add(model);
                config.setModels(objectMapper.writeValueAsString(existingModels));
                vendorConfigMapper.updateById(config);
            } catch (Exception e) {
                return R.fail("更新模型失败: " + e.getMessage());
            }
        }
        return R.ok(Map.of("message", "添加模型成功"));
    }

    @PostMapping("/vendorConfig/delVendorModel")
    public R<Map<String, String>> delVendorModel(@RequestBody Map<String, Object> body) {
        String id = (String) body.get("id");
        String modelName = (String) body.get("modelName");
        OVendorConfig config = vendorConfigMapper.selectById(id);
        if (config != null && modelName != null) {
            try {
                List<Map<String, Object>> existingModels = objectMapper.readValue(
                        config.getModels() != null ? config.getModels() : "[]",
                        new TypeReference<List<Map<String, Object>>>() {});
                existingModels.removeIf(m -> modelName.equals(m.get("modelId")) || modelName.equals(m.get("modelName")));
                config.setModels(objectMapper.writeValueAsString(existingModels));
                vendorConfigMapper.updateById(config);
            } catch (Exception e) {
                return R.fail("删除模型失败: " + e.getMessage());
            }
        }
        return R.ok(Map.of("message", "删除模型成功"));
    }

    @PostMapping("/vendorConfig/upVendorModel")
    public R<Map<String, String>> upVendorModel(@RequestBody Map<String, Object> body) {
        String id = (String) body.get("id");
        String modelName = (String) body.get("modelName");
        Object model = body.get("model");
        OVendorConfig config = vendorConfigMapper.selectById(id);
        if (config != null && modelName != null && model != null) {
            try {
                List<Object> existingModels = objectMapper.readValue(
                        config.getModels() != null ? config.getModels() : "[]",
                        new TypeReference<List<Object>>() {});
                boolean found = false;
                for (int i = 0; i < existingModels.size(); i++) {
                    Map<?, ?> m = (Map<?, ?>) existingModels.get(i);
                    if (modelName.equals(m.get("modelName")) || modelName.equals(m.get("modelId"))) {
                        existingModels.set(i, model);
                        found = true;
                        break;
                    }
                }
                if (!found) existingModels.add(model);
                config.setModels(objectMapper.writeValueAsString(existingModels));
                vendorConfigMapper.updateById(config);
            } catch (Exception e) {
                return R.fail("更新模型失败: " + e.getMessage());
            }
        }
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
    public R<Map<String, Object>> getAgentDeploy() {
        List<OAgentDeploy> allData = agentDeployMapper.selectList(null);
        List<OAgentDeploy> qrdinaryData = new ArrayList<>();
        List<OAgentDeploy> advancedData = new ArrayList<>();
        for (OAgentDeploy item : allData) {
            if (item.getKey() != null && item.getKey().contains(":")) {
                advancedData.add(item);
            } else {
                qrdinaryData.add(item);
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("qrdinaryData", qrdinaryData);
        result.put("advancedData", advancedData);
        return R.ok(result);
    }

    @PostMapping("/agentDeploy/deployAgentModel")
    public R<Map<String, String>> deployAgentModel(@RequestBody OAgentDeploy deploy) {
        agentDeployMapper.updateById(deploy);
        return R.ok(Map.of("message", "部署成功"));
    }

    @GetMapping("/agentDeploy/getAgentUseMode")
    public R<String> getAgentUseMode() {
        OSetting setting = settingMapper.selectById("agentUseMode");
        return R.ok(setting != null ? setting.getValue() : "0");
    }

    @PostMapping("/agentDeploy/updateUseMode")
    public R<Map<String, String>> updateUseMode(@RequestBody Map<String, String> body) {
        OSetting setting = new OSetting();
        setting.setKey("agentUseMode");
        setting.setValue(body.get("agentUseMode"));
        settingMapper.updateById(setting);
        return R.ok(Map.of("message", "更新成功"));
    }

    @PostMapping("/agentDeploy/agentSetKey")
    public R<String> agentSetKey(@RequestBody Map<String, Object> body) {
        String key = body.get("key") != null ? body.get("key").toString() : "";
        OVendorConfig vendor = vendorConfigMapper.selectById("toonflow");
        if (vendor == null) return R.fail("未找到toonflow供应商配置");
        try {
            Map<String, Object> inputValues = objectMapper.readValue(
                    vendor.getInputValues() != null ? vendor.getInputValues() : "{}",
                    new TypeReference<Map<String, Object>>() {});
            inputValues.put("apiKey", key);
            vendor.setInputValues(objectMapper.writeValueAsString(inputValues));
            vendorConfigMapper.updateById(vendor);

            // Test key and auto-configure default models
            try {
                aiService.generateText("universalAi", List.of(
                        new com.toonflow.ai.AiService.ChatMessage("user", "1+1等于几？请直接回答2")));
                // Key works — auto-configure agent models
                updateAgentDeploy("scriptAgent", "claude-sonnet-4-6", "toonflow:claude-sonnet-4-6", "toonflow");
                updateAgentDeploy("productionAgent", "claude-sonnet-4-6", "toonflow:claude-sonnet-4-6", "toonflow");
                updateAgentDeploy("universalAi", "claude-haiku-4-5", "toonflow:claude-haiku-4-5-20251001", "toonflow");
            } catch (Exception ignored) {}
        } catch (Exception e) {
            return R.fail("设置失败: " + e.getMessage());
        }
        return R.ok("一键填入成功");
    }

    private void updateAgentDeploy(String agentKey, String model, String modelName, String vendorId) {
        OAgentDeploy deploy = agentDeployMapper.selectOne(
                new LambdaQueryWrapper<OAgentDeploy>().eq(OAgentDeploy::getKey, agentKey).last("LIMIT 1"));
        if (deploy != null) {
            deploy.setModel(model);
            deploy.setModelName(modelName);
            deploy.setVendorId(vendorId);
            agentDeployMapper.updateById(deploy);
        }
    }

    // ========== 提示词管理 ==========

    @PostMapping("/promptManage/getPrompt")
    public R<List<Map<String, Object>>> getPrompt(@RequestBody(required = false) Map<String, String> body) {
        String type = body != null ? body.get("type") : null;
        LambdaQueryWrapper<OPrompt> wrapper = new LambdaQueryWrapper<>();
        if (type != null) wrapper.eq(OPrompt::getType, type);
        List<OPrompt> list = promptMapper.selectList(wrapper);
        List<Map<String, Object>> result = new ArrayList<>();
        for (OPrompt item : list) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", item.getId());
            row.put("name", item.getName());
            row.put("type", item.getType());
            row.put("useData", item.getUseData());
            // Return useData if present, otherwise fall back to data
            String displayData = (item.getUseData() != null && !item.getUseData().isEmpty())
                    ? item.getUseData() : item.getData();
            row.put("data", displayData);
            result.add(row);
        }
        return R.ok(result);
    }

    @PostMapping("/promptManage/updatePrompt")
    public R<Integer> updatePrompt(@RequestBody Map<String, Object> body) {
        Object idObj = body.get("id");
        if (idObj == null) return R.fail("id不能为空");
        String id = idObj.toString();
        OPrompt prompt = promptMapper.selectById(id);
        if (prompt == null) return R.fail("提示词不存在");
        prompt.setUseData(body.get("data") != null ? body.get("data").toString() : null);
        promptMapper.updateById(prompt);
        return R.ok(123);
    }

    // ========== 登录配置 ==========

    @GetMapping("/loginConfig/getUser")
    public R<OUser> getUser() {
        return R.ok(userMapper.selectById(1));
    }

    @PostMapping("/loginConfig/updateUserPwd")
    public R<Map<String, String>> updateUserPwd(@RequestBody Map<String, String> body) {
        String idStr = body.get("id");
        Integer id = idStr != null ? Integer.parseInt(idStr) : 1;
        OUser user = userMapper.selectById(id);
        if (user != null) {
            if (body.get("name") != null) user.setName(body.get("name"));
            if (body.get("password") != null) user.setPassword(body.get("password"));
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
