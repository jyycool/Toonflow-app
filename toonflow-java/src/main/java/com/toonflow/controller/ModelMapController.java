package com.toonflow.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.toonflow.ai.vendor.VendorService;
import com.toonflow.common.exception.BusinessException;
import com.toonflow.common.result.R;
import com.toonflow.entity.OModelPrompt;
import com.toonflow.entity.OVendorConfig;
import com.toonflow.mapper.OModelPromptMapper;
import com.toonflow.mapper.OVendorConfigMapper;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Stream;

/**
 * 模型映射控制器（图片/视频模型与提示词的绑定）
 * 对应原项目 setting/modelMap/*
 * 提示词以 Markdown 存储在数据目录 modelPrompt/{image|video}/ 下，
 * 绑定关系存在 o_modelPrompt 表。
 */
@RestController
@RequestMapping("/api/setting/modelMap")
@RequiredArgsConstructor
public class ModelMapController {

    private final OModelPromptMapper modelPromptMapper;
    private final OVendorConfigMapper vendorConfigMapper;
    private final VendorService vendorService;

    @Value("${toonflow.data-dir}")
    private String dataDir;

    @GetMapping("/getPromptList")
    public R<List<Map<String, String>>> getPromptList() {
        Path root = Paths.get(dataDir, "modelPrompt");
        List<Map<String, String>> result = new ArrayList<>();
        try {
            if (!Files.exists(root)) {
                Files.createDirectories(root);
                return R.ok(result);
            }
            try (Stream<Path> stream = Files.walk(root)) {
                for (Path p : stream.filter(Files::isRegularFile)
                        .filter(f -> f.toString().endsWith(".md")).toList()) {
                    String rel = root.relativize(p).toString().replace("\\", "/");
                    String name = p.getFileName().toString().replaceAll("\\.md$", "");
                    String type = rel.contains("/") ? rel.split("/")[0] : "";
                    Map<String, String> m = new HashMap<>();
                    m.put("path", rel);
                    m.put("name", name);
                    m.put("type", type);
                    m.put("data", Files.readString(p, StandardCharsets.UTF_8));
                    result.add(m);
                }
            }
        } catch (IOException e) {
            throw new BusinessException("读取提示词列表失败: " + e.getMessage());
        }
        return R.ok(result);
    }

    @PostMapping("/savePrompt")
    public R<String> savePrompt(@RequestBody SavePromptRequest req) {
        if (!List.of("image", "video").contains(req.getType())) {
            throw new BusinessException("type 必须为 image 或 video");
        }
        Path dir = Paths.get(dataDir, "modelPrompt", req.getType());
        try {
            Files.createDirectories(dir);
            Files.writeString(dir.resolve(req.getName() + ".md"), req.getData(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new BusinessException("保存提示词失败: " + e.getMessage());
        }
        return R.ok("保存成功");
    }

    @PostMapping("/updatePrompt")
    public R<String> updatePrompt(@RequestBody SavePromptRequest req) {
        return savePrompt(req);
    }

    @PostMapping("/deletePrompt")
    public R<String> deletePrompt(@RequestBody Map<String, String> body) {
        String path = body.get("path");
        if (path == null) throw new BusinessException("path不能为空");
        Path root = Paths.get(dataDir, "modelPrompt");
        Path target = root.resolve(path).normalize();
        if (!target.startsWith(root)) throw new BusinessException("非法路径");
        try {
            Files.deleteIfExists(target);
        } catch (IOException e) {
            throw new BusinessException("删除失败: " + e.getMessage());
        }
        return R.ok("删除成功");
    }

    @PostMapping("/bindingPrompt")
    public R<String> bindingPrompt(@RequestBody BindingRequest req) {
        OModelPrompt existing = modelPromptMapper.selectOne(
                new LambdaQueryWrapper<OModelPrompt>()
                        .eq(OModelPrompt::getModel, req.getModel())
                        .eq(OModelPrompt::getVendorId, req.getVendorId()));
        if (existing != null) {
            existing.setFileName(req.getFileName());
            existing.setPath(req.getPath());
            modelPromptMapper.updateById(existing);
        } else {
            OModelPrompt mp = new OModelPrompt();
            mp.setVendorId(req.getVendorId());
            mp.setModel(req.getModel());
            mp.setPath(req.getPath());
            mp.setFileName(req.getFileName());
            modelPromptMapper.insert(mp);
        }
        return R.ok("绑定成功");
    }

    /**
     * 获取图片和视频模型（含已绑定的提示词信息）
     */
    @GetMapping("/getImageAndVideoModel")
    public R<List<Map<String, Object>>> getImageAndVideoModel() {
        List<OVendorConfig> vendors = vendorConfigMapper.selectList(
                new LambdaQueryWrapper<OVendorConfig>().eq(OVendorConfig::getEnable, 1));
        List<Map<String, Object>> result = new ArrayList<>();
        for (OVendorConfig vendor : vendors) {
            List<OModelPrompt> promptList = modelPromptMapper.selectList(
                    new LambdaQueryWrapper<OModelPrompt>().eq(OModelPrompt::getVendorId, vendor.getId()));
            Map<String, OModelPrompt> promptMap = new HashMap<>();
            promptList.forEach(p -> promptMap.put(p.getModel(), p));

            List<Map<String, Object>> models = vendorService.getModelList(vendor.getId()).stream()
                    .filter(m -> "image".equals(m.get("type")) || "video".equals(m.get("type")))
                    .map(m -> {
                        Map<String, Object> item = new HashMap<>();
                        item.put("name", m.get("name"));
                        item.put("type", m.get("type"));
                        String modelName = vendor.getId() + ":" + m.get("modelId");
                        item.put("model", modelName);
                        OModelPrompt bound = promptMap.get(modelName);
                        if (bound != null) {
                            item.put("fileName", bound.getFileName());
                            item.put("path", bound.getPath());
                        }
                        return item;
                    }).toList();

            Map<String, Object> vendorResult = new HashMap<>();
            vendorResult.put("id", vendor.getId());
            vendorResult.put("promptList", models);
            result.add(vendorResult);
        }
        return R.ok(result);
    }

    @Data
    public static class SavePromptRequest {
        @NotBlank private String name;
        private String data;
        @NotBlank private String type;
    }

    @Data
    public static class BindingRequest {
        @NotBlank private String vendorId;
        @NotBlank private String model;
        @NotBlank private String path;
        @NotBlank private String fileName;
    }
}
