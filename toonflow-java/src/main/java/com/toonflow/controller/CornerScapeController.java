package com.toonflow.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.toonflow.common.result.R;
import com.toonflow.entity.OAssets;
import com.toonflow.entity.OAssetsRole2Audio;
import com.toonflow.entity.OImage;
import com.toonflow.mapper.OAssetsMapper;
import com.toonflow.mapper.OAssetsRole2AudioMapper;
import com.toonflow.mapper.OImageMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/cornerScape")
@RequiredArgsConstructor
public class CornerScapeController {

    private final OAssetsMapper assetsMapper;
    private final OImageMapper imageMapper;
    private final OAssetsRole2AudioMapper role2AudioMapper;

    @PostMapping("/getAllAssets")
    public R<List<Map<String, Object>>> getAllAssets(@RequestBody Map<String, Object> body) {
        String projectId = body.get("projectId") != null ? body.get("projectId").toString() : null;
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

    @PostMapping("/updateAssetsAudio")
    public R<Map<String, String>> updateAssetsAudio(@RequestBody Map<String, Object> body) {
        String assetsId = body.get("assetsId") != null ? body.get("assetsId").toString() : null;
        @SuppressWarnings("unchecked")
        List<String> audioIds = (List<String>) body.get("audioIds");
        if (audioIds != null && audioIds.size() > 1) {
            throw new com.toonflow.common.exception.BusinessException("仅可绑定一个音色");
        }
        role2AudioMapper.delete(new LambdaQueryWrapper<OAssetsRole2Audio>()
                .eq(OAssetsRole2Audio::getAssetsRoleId, assetsId));
        if (audioIds != null && !audioIds.isEmpty()) {
            OAssetsRole2Audio bind = new OAssetsRole2Audio();
            bind.setAssetsRoleId(assetsId);
            bind.setAssetsAudioId(audioIds.get(0));
            role2AudioMapper.insert(bind);
        }
        return R.ok(Map.of("message", "更新音频成功"));
    }

    @PostMapping("/pollingAudio")
    public R<List<OAssets>> pollingAudio(@RequestBody Map<String, Object> body) {
        @SuppressWarnings("unchecked") List<String> ids = (List<String>) body.get("ids");
        if (ids == null || ids.isEmpty()) return R.ok(List.of());
        return R.ok(assetsMapper.selectList(
                new LambdaQueryWrapper<OAssets>()
                        .in(OAssets::getId, ids)
                        .ne(OAssets::getAudioBindState, 1)));
    }

    @PostMapping("/batchBindAudio")
    public R<Map<String, String>> batchBindAudio(@RequestBody Map<String, Object> body) {
        String projectId = body.get("projectId") != null ? body.get("projectId").toString() : null;
        @SuppressWarnings("unchecked")
        List<String> assetsIds = (List<String>) body.get("assetsIds");

        List<OAssets> audioData = assetsMapper.selectList(
                new LambdaQueryWrapper<OAssets>()
                        .eq(OAssets::getProjectId, projectId)
                        .eq(OAssets::getType, "audio")
                        .isNull(OAssets::getAssetsId));
        if (audioData.isEmpty()) {
            throw new com.toonflow.common.exception.BusinessException("暂无设置音频，请先前往资产中心上传音频");
        }
        if (assetsIds != null) {
            for (String id : assetsIds) {
                OAssets asset = assetsMapper.selectById(id);
                if (asset != null) {
                    asset.setAudioBindState(1);
                    assetsMapper.updateById(asset);
                }
            }
        }
        return R.ok(Map.of("message", "已提交音频绑定任务"));
    }
}
