package com.toonflow.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.toonflow.common.exception.BusinessException;
import com.toonflow.common.result.R;
import com.toonflow.entity.OAssets;
import com.toonflow.entity.OImage;
import com.toonflow.mapper.OAssetsMapper;
import com.toonflow.mapper.OImageMapper;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/assets")
@RequiredArgsConstructor
public class AssetsController {

    private final OAssetsMapper assetsMapper;
    private final OImageMapper imageMapper;

    @PostMapping("/addAssets")
    public R<Map<String, String>> addAssets(@RequestBody OAssets assets) {
        assets.setStartTime(System.currentTimeMillis());
        assetsMapper.insert(assets);
        return R.ok(Map.of("message", "新增素材成功"));
    }

    @PostMapping("/getAssetsApi")
    public R<List<OAssets>> getAssets(@RequestBody Map<String, Object> body) {
        Integer projectId = body.get("projectId");
        Integer scriptId = body.get("scriptId");
        LambdaQueryWrapper<OAssets> wrapper = new LambdaQueryWrapper<OAssets>()
                .eq(OAssets::getProjectId, projectId);
        if (scriptId != null) wrapper.eq(OAssets::getScriptId, scriptId);
        return R.ok(assetsMapper.selectList(wrapper));
    }

    @PostMapping("/updateAssets")
    public R<Map<String, String>> updateAssets(@RequestBody OAssets assets) {
        assetsMapper.updateById(assets);
        return R.ok(Map.of("message", "更新素材成功"));
    }

    @PostMapping("/delAssets")
    public R<Map<String, String>> delAssets(@RequestBody Map<String, Object> body) {
        Integer id = body.get("id");
        if (id == null) throw new BusinessException("id不能为空");
        assetsMapper.deleteById(id);
        return R.ok(Map.of("message", "删除素材成功"));
    }

    @PostMapping("/batchDelete")
    public R<Map<String, String>> batchDelete(@RequestBody Map<String, Object> body) {
        List<Integer> ids = body.get("ids");
        if (ids == null || ids.isEmpty()) throw new BusinessException("ids不能为空");
        assetsMapper.deleteBatchIds(ids);
        return R.ok(Map.of("message", "批量删除成功"));
    }

    @PostMapping("/getImage")
    public R<OImage> getImage(@RequestBody Map<String, Object> body) {
        return R.ok(imageMapper.selectById(body.get("id")));
    }

    @PostMapping("/saveAssets")
    public R<Map<String, String>> saveAssets(@RequestBody OAssets assets) {
        if (assets.getId() == null) {
            assets.setStartTime(System.currentTimeMillis());
            assetsMapper.insert(assets);
        } else {
            assetsMapper.updateById(assets);
        }
        return R.ok(Map.of("message", "保存成功"));
    }

    @PostMapping("/pollingImageAssets")
    public R<List<OImage>> pollingImageAssets(@RequestBody Map<String, Object> body) {
        List<Integer> ids = body.get("ids");
        if (ids == null || ids.isEmpty()) return R.ok(List.of());
        return R.ok(imageMapper.selectList(
                new LambdaQueryWrapper<OImage>().in(OImage::getId, ids)));
    }

    @PostMapping("/pollingPromptAssets")
    public R<List<OAssets>> pollingPromptAssets(@RequestBody Map<String, Object> body) {
        List<Integer> ids = body.get("ids");
        if (ids == null || ids.isEmpty()) return R.ok(List.of());
        return R.ok(assetsMapper.selectList(
                new LambdaQueryWrapper<OAssets>().in(OAssets::getId, ids)));
    }

    /**
     * 新增音频素材（每个 assetsItem 落库为一条 type=audio 的素材）
     */
    @PostMapping("/addAudioAssets")
    public R<Map<String, String>> addAudioAssets(@RequestBody Map<String, Object> body) {
        Integer projectId = body.get("projectId") != null ? ((Number) body.get("projectId")).intValue() : null;
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) body.get("assetsItem");
        if (items != null) {
            for (Map<String, Object> item : items) {
                OAssets asset = new OAssets();
                asset.setProjectId(projectId);
                asset.setType("audio");
                asset.setName((String) item.get("name"));
                asset.setDescribe((String) item.get("describe"));
                asset.setPrompt((String) item.get("prompt"));
                asset.setStartTime(System.currentTimeMillis());
                assetsMapper.insert(asset);
            }
        }
        return R.ok(Map.of("message", "新增音频素材成功"));
    }

    @PostMapping("/updateAudioAssets")
    public R<Map<String, String>> updateAudioAssets(@RequestBody OAssets asset) {
        assetsMapper.updateById(asset);
        return R.ok(Map.of("message", "更新音频素材成功"));
    }

    /**
     * 获取素材数据（type=clip，联查图片）
     */
    @PostMapping("/getMaterialData")
    public R<List<OAssets>> getMaterialData(@RequestBody Map<String, Object> body) {
        Integer projectId = body.get("projectId");
        return R.ok(assetsMapper.selectList(
                new LambdaQueryWrapper<OAssets>()
                        .eq(OAssets::getProjectId, projectId)
                        .eq(OAssets::getType, "clip")));
    }

    @PostMapping("/batchGenerationData")
    public R<List<OAssets>> batchGenerationData(@RequestBody Map<String, Object> body) {
        Integer projectId = body.get("projectId") != null ? ((Number) body.get("projectId")).intValue() : null;
        return R.ok(assetsMapper.selectList(
                new LambdaQueryWrapper<OAssets>().eq(OAssets::getProjectId, projectId)));
    }

    @PostMapping("/delImage")
    public R<Map<String, String>> delImage(@RequestBody Map<String, Object> body) {
        Integer id = body.get("id");
        if (id != null) imageMapper.deleteById(id);
        return R.ok(Map.of("message", "删除图片成功"));
    }

    @Data
    public static class BatchGenerationData {
        @NotNull private Integer projectId;
        private List<Integer> ids;
    }
}
