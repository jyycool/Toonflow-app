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

    @GetMapping("/getAssetsApi")
    public R<List<OAssets>> getAssets(@RequestParam Integer projectId,
                                       @RequestParam(required = false) Integer scriptId) {
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
    public R<Map<String, String>> delAssets(@RequestBody Map<String, Integer> body) {
        Integer id = body.get("id");
        if (id == null) throw new BusinessException("id不能为空");
        assetsMapper.deleteById(id);
        return R.ok(Map.of("message", "删除素材成功"));
    }

    @PostMapping("/batchDelete")
    public R<Map<String, String>> batchDelete(@RequestBody Map<String, List<Integer>> body) {
        List<Integer> ids = body.get("ids");
        if (ids == null || ids.isEmpty()) throw new BusinessException("ids不能为空");
        assetsMapper.deleteBatchIds(ids);
        return R.ok(Map.of("message", "批量删除成功"));
    }

    @GetMapping("/getImage")
    public R<OImage> getImage(@RequestParam Integer id) {
        return R.ok(imageMapper.selectById(id));
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

    @GetMapping("/pollingImageAssets")
    public R<List<OImage>> pollingImageAssets(@RequestParam List<Integer> ids) {
        return R.ok(imageMapper.selectList(
                new LambdaQueryWrapper<OImage>().in(OImage::getId, ids)));
    }

    @GetMapping("/pollingPromptAssets")
    public R<List<OAssets>> pollingPromptAssets(@RequestParam List<Integer> ids) {
        return R.ok(assetsMapper.selectList(
                new LambdaQueryWrapper<OAssets>().in(OAssets::getId, ids)));
    }

    @Data
    public static class BatchGenerationData {
        @NotNull private Integer projectId;
        private List<Integer> ids;
    }
}
