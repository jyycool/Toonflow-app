package com.toonflow.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.toonflow.common.result.R;
import com.toonflow.entity.*;
import com.toonflow.mapper.*;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/setting")
@RequiredArgsConstructor
public class SettingController {

    private final OSettingMapper settingMapper;
    private final OVendorConfigMapper vendorConfigMapper;
    private final OAgentDeployMapper agentDeployMapper;
    private final OPromptMapper promptMapper;
    private final OUserMapper userMapper;

    private final org.springframework.web.client.RestClient restClient =
            org.springframework.web.client.RestClient.create();

    // ========== 供应商配置 ==========

    @GetMapping("/vendorConfig/getVendorList")
    public R<List<OVendorConfig>> getVendorList() {
        return R.ok(vendorConfigMapper.selectList(null));
    }

    @PostMapping("/vendorConfig/addVendor")
    public R<Map<String, String>> addVendor(@RequestBody OVendorConfig vendor) {
        vendorConfigMapper.insert(vendor);
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
            vendorConfigMapper.insert(config);
        }
        return R.ok(Map.of("message", "更新配置成功"));
    }

    // ========== Agent 部署配置 ==========

    @GetMapping("/agentDeploy/getAgentDeploy")
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

    @GetMapping("/promptManage/getPrompt")
    public R<List<OPrompt>> getPrompt(@RequestParam(required = false) String type) {
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

    @GetMapping("/getTextModel")
    public R<List<OAgentDeploy>> getTextModel() {
        return R.ok(agentDeployMapper.selectList(
                new LambdaQueryWrapper<OAgentDeploy>().eq(OAgentDeploy::getType, "text")));
    }
}
