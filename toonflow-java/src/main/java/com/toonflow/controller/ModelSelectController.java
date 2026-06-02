package com.toonflow.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.toonflow.common.result.R;
import com.toonflow.ai.vendor.VendorService;
import com.toonflow.entity.OVendorConfig;
import com.toonflow.mapper.OVendorConfigMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/modelSelect")
@RequiredArgsConstructor
public class ModelSelectController {

    private final OVendorConfigMapper vendorConfigMapper;
    private final ObjectMapper objectMapper;
    private final VendorService vendorService;

    @PostMapping("/getModelList")
    public R<List<Map<String, Object>>> getModelList(
            @RequestBody(required = false) Map<String, Object> body) {
        String type = body != null ? (String) body.get("type") : null;
        List<OVendorConfig> vendors = vendorConfigMapper.selectList(
                new LambdaQueryWrapper<OVendorConfig>().eq(OVendorConfig::getEnable, 1));
        List<Map<String, Object>> result = new ArrayList<>();
        for (OVendorConfig vendor : vendors) {
            try {
                if (vendor.getModels() == null) continue;
                List<Map<String, Object>> models = objectMapper.readValue(
                        vendor.getModels(), new TypeReference<>() {});
                for (Map<String, Object> model : models) {
                    String modelType = (String) model.get("type");
                    // type=all 时排除 video（与原始逻辑一致）
                    if ("all".equals(type) && "video".equals(modelType)) continue;
                    if (type != null && !"all".equals(type) && !type.equals(modelType)) continue;
                    Map<String, Object> item = new java.util.HashMap<>();
                    item.put("id", vendor.getId());
                    item.put("label", model.get("name"));
                    item.put("value", model.get("modelId"));
                    item.put("type", modelType);
                    item.put("name", vendorService.getVendorName(vendor.getId()));   // 供应商显示名（原始 vendorData.name）
                    result.add(item);
                }
            } catch (Exception ignored) {}
        }
        return R.ok(result);
    }

    @PostMapping("/getModelDetail")
    public R<Map<String, Object>> getModelDetail(@RequestBody Map<String, String> body) {
        // 原项目请求字段为 modelId，格式 vendorId:modelId（按第一个冒号切分）
        String fullModelId = body.get("modelId");
        if (fullModelId == null) fullModelId = body.get("modelName"); // 兼容旧字段
        if (fullModelId == null) return R.ok(null);
        String[] parts = fullModelId.split(":", 2);
        if (parts.length < 2) return R.ok(null);
        String vendorId = parts[0];
        String modelId = parts[1];
        OVendorConfig vendor = vendorConfigMapper.selectById(vendorId);
        if (vendor == null) return R.ok(null);
        try {
            List<Map<String, Object>> models = objectMapper.readValue(
                    vendor.getModels() != null ? vendor.getModels() : "[]",
                    new TypeReference<>() {});
            return R.ok(models.stream()
                    .filter(m -> modelId.equals(m.get("modelId")))
                    .findFirst().orElse(null));
        } catch (Exception e) {
            return R.ok(null);
        }
    }
}
