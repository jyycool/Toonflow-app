package com.toonflow.ai.vendor;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.toonflow.common.exception.BusinessException;
import com.toonflow.entity.OVendorConfig;
import com.toonflow.mapper.OVendorConfigMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 供应商配置服务
 * 对应原项目 src/utils/vendor.ts
 *
 * 注：原项目将供应商请求逻辑以 TypeScript 代码存储并在 vm2 沙箱执行；
 * Java 版本改为读取结构化配置（apiKey/baseUrl/models），由具体的
 * MediaGenerationService 通过 HTTP 直连各厂商 API。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VendorService {

    private final OVendorConfigMapper vendorConfigMapper;
    private final ObjectMapper objectMapper;

    /**
     * 获取供应商配置
     */
    public OVendorConfig getVendor(String vendorId) {
        OVendorConfig config = vendorConfigMapper.selectById(vendorId);
        if (config == null) throw new BusinessException("供应商配置不存在: " + vendorId);
        return config;
    }

    /**
     * 获取供应商输入参数（apiKey、baseUrl 等）
     */
    public Map<String, String> getInputs(String vendorId) {
        OVendorConfig config = getVendor(vendorId);
        try {
            return objectMapper.readValue(
                    config.getInputValues() != null ? config.getInputValues() : "{}",
                    new TypeReference<>() {});
        } catch (Exception e) {
            return Map.of();
        }
    }

    /**
     * 获取供应商模型列表
     */
    public List<Map<String, Object>> getModelList(String vendorId) {
        OVendorConfig config = getVendor(vendorId);
        if (config.getModels() == null) return new ArrayList<>();
        try {
            return objectMapper.readValue(config.getModels(), new TypeReference<>() {});
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    /**
     * 获取供应商显示名称（读取 vendor-meta 资源，缺省回退为 id）
     */
    public String getVendorName(String vendorId) {
        try (var in = getClass().getClassLoader()
                .getResourceAsStream("default-data/vendor-meta/" + vendorId + ".json")) {
            if (in != null) {
                Map<String, Object> meta = objectMapper.readValue(in, new TypeReference<>() {});
                Object name = meta.get("name");
                if (name != null) return name.toString();
            }
        } catch (Exception ignored) {}
        return vendorId;
    }

    /**
     * 解析模型详情（type=image/video/text/tts）
     */
    public Map<String, Object> getModelDetail(String vendorId, String modelId) {
        return getModelList(vendorId).stream()
                .filter(m -> modelId.equals(m.get("modelId")))
                .findFirst()
                .orElseThrow(() -> new BusinessException("模型不存在: " + vendorId + ":" + modelId));
    }
}
