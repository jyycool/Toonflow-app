package com.toonflow.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.toonflow.common.result.R;
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

    @GetMapping("/getModelList")
    public R<List<Map<String, Object>>> getModelList(@RequestParam(required = false) String type) {
        List<OVendorConfig> vendors = vendorConfigMapper.selectList(
                new LambdaQueryWrapper<OVendorConfig>().eq(OVendorConfig::getEnable, 1));
        List<Map<String, Object>> result = new ArrayList<>();
        for (OVendorConfig vendor : vendors) {
            try {
                if (vendor.getModels() != null) {
                    List<Map<String, Object>> models = objectMapper.readValue(
                            vendor.getModels(), new TypeReference<>() {});
                    for (Map<String, Object> model : models) {
                        if (type == null || type.equals(model.get("type"))) {
                            model.put("vendorId", vendor.getId());
                            model.put("modelName", vendor.getId() + ":" + model.get("modelId"));
                            result.add(model);
                        }
                    }
                }
            } catch (Exception ignored) {}
        }
        return R.ok(result);
    }

    @GetMapping("/getModelDetail")
    public R<Map<String, Object>> getModelDetail(@RequestParam String modelName) {
        String[] parts = modelName.split(":", 2);
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
