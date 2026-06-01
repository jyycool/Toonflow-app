package com.toonflow.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.toonflow.common.exception.BusinessException;
import com.toonflow.common.result.R;
import com.toonflow.entity.OScript;
import com.toonflow.entity.OScriptAssets;
import com.toonflow.mapper.OScriptAssetsMapper;
import com.toonflow.mapper.OScriptMapper;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/script")
@RequiredArgsConstructor
public class ScriptController {

    private final OScriptMapper scriptMapper;
    private final OScriptAssetsMapper scriptAssetsMapper;

    @PostMapping("/addScript")
    public R<Map<String, String>> addScript(@Valid @RequestBody AddScriptRequest req) {
        OScript script = new OScript();
        script.setName(req.getName());
        script.setContent(req.getContent());
        script.setProjectId(req.getProjectId());
        script.setCreateTime(System.currentTimeMillis());
        scriptMapper.insert(script);

        if (req.getAssets() != null && !req.getAssets().isEmpty()) {
            for (Integer assetId : req.getAssets()) {
                OScriptAssets sa = new OScriptAssets();
                sa.setScriptId(script.getId());
                sa.setAssetId(assetId);
                scriptAssetsMapper.insert(sa);
            }
        }
        return R.ok(Map.of("message", "添加剧本成功"));
    }

    @PostMapping("/batchAddScript")
    public R<Map<String, String>> batchAddScript(@RequestBody Map<String, Object> body) {
        // 批量添加剧本逻辑
        return R.ok(Map.of("message", "批量添加剧本成功"));
    }

    @GetMapping("/getScrptApi")
    public R<List<OScript>> getScript(@RequestParam Integer projectId) {
        List<OScript> list = scriptMapper.selectList(
                new LambdaQueryWrapper<OScript>()
                        .eq(OScript::getProjectId, projectId)
                        .orderByAsc(OScript::getCreateTime));
        return R.ok(list);
    }

    @PostMapping("/updateScript")
    public R<Map<String, String>> updateScript(@RequestBody OScript script) {
        scriptMapper.updateById(script);
        return R.ok(Map.of("message", "更新剧本成功"));
    }

    @PostMapping("/delScript")
    public R<Map<String, String>> delScript(@RequestBody Map<String, Integer> body) {
        Integer id = body.get("id");
        if (id == null) throw new BusinessException("id不能为空");
        scriptMapper.deleteById(id);
        scriptAssetsMapper.delete(new LambdaQueryWrapper<OScriptAssets>().eq(OScriptAssets::getScriptId, id));
        return R.ok(Map.of("message", "删除剧本成功"));
    }

    @Data
    public static class AddScriptRequest {
        @NotBlank private String name;
        @NotNull private String content;
        @NotNull private Integer projectId;
        @NotNull private List<Integer> assets;
    }
}
