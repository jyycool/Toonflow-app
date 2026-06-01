package com.toonflow.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.toonflow.common.result.R;
import com.toonflow.entity.OAssets;
import com.toonflow.entity.OImage;
import com.toonflow.mapper.OAssetsMapper;
import com.toonflow.mapper.OImageMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 角落场景（cornerScape）控制器
 * 对应原项目 routes/cornerScape/*
 * 主要处理素材与音频绑定相关的展示数据。
 */
@RestController
@RequestMapping("/api/cornerScape")
@RequiredArgsConstructor
public class CornerScapeController {

    private final OAssetsMapper assetsMapper;
    private final OImageMapper imageMapper;

    /**
     * 获取全部素材（联查图片信息，排除 clip/audio，仅顶层素材）
     */
    @PostMapping("/getAllAssets")
    public R<List<Map<String, Object>>> getAllAssets(@RequestBody Map<String, Object> body) {
        Integer projectId = (Integer) body.get("projectId");
        @SuppressWarnings("unchecked")
        List<String> types = (List<String>) body.get("type");

        LambdaQueryWrapper<OAssets> wrapper = new LambdaQueryWrapper<OAssets>()
                .eq(OAssets::getProjectId, projectId)
                .ne(OAssets::getType, "clip")
                .ne(OAssets::getType, "audio")
                .isNull(OAssets::getAssetsId);
        if (types != null && !types.isEmpty()) {
            wrapper.in(OAssets::getType, types);
        }
        List<OAssets> assets = assetsMapper.selectList(wrapper);

        // 按 role > scene > tool > 其他 排序，并联查图片
        List<Map<String, Object>> result = assets.stream()
                .sorted(Comparator.comparingInt(a -> typeOrder(a.getType())))
                .map(a -> {
                    Map<String, Object> m = new HashMap<>();
                    m.put("id", a.getId());
                    m.put("name", a.getName());
                    m.put("type", a.getType());
                    m.put("describe", a.getDescribe());
                    m.put("prompt", a.getPrompt());
                    m.put("imageId", a.getImageId());
                    if (a.getImageId() != null) {
                        OImage img = imageMapper.selectById(a.getImageId());
                        if (img != null) {
                            m.put("filePath", img.getFilePath());
                            m.put("state", img.getState());
                            m.put("model", img.getModel());
                            m.put("resolution", img.getResolution());
                            m.put("errorReason", img.getErrorReason());
                        }
                    }
                    return m;
                })
                .toList();
        return R.ok(result);
    }

    private int typeOrder(String type) {
        if (type == null) return 4;
        return switch (type) {
            case "role" -> 1;
            case "scene" -> 2;
            case "tool" -> 3;
            default -> 4;
        };
    }
}
