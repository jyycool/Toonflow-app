package com.toonflow.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.toonflow.common.exception.BusinessException;
import com.toonflow.common.result.R;
import com.toonflow.entity.OStoryboard;
import com.toonflow.mapper.OStoryboardMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/production/storyboard")
@RequiredArgsConstructor
public class StoryboardController {

    private final OStoryboardMapper storyboardMapper;

    @PostMapping("/addStoryboard")
    public R<Map<String, String>> addStoryboard(@RequestBody OStoryboard storyboard) {
        storyboard.setCreateTime(System.currentTimeMillis());
        storyboardMapper.insert(storyboard);
        return R.ok(Map.of("message", "新增分镜成功"));
    }

    @PostMapping("/getStoryboardData")
    public R<List<OStoryboard>> getStoryboardData(@RequestBody Map<String, Object> body) {
        Integer projectId = body.get("projectId") != null ? ((Number) body.get("projectId")).intValue() : null;
        Integer scriptId = body.get("scriptId") != null ? ((Number) body.get("scriptId")).intValue() : null;
        LambdaQueryWrapper<OStoryboard> wrapper = new LambdaQueryWrapper<OStoryboard>()
                .eq(OStoryboard::getProjectId, projectId);
        if (scriptId != null) wrapper.eq(OStoryboard::getScriptId, scriptId);
        wrapper.orderByAsc(OStoryboard::getIndex);
        return R.ok(storyboardMapper.selectList(wrapper));
    }

    @PostMapping("/editStoryboardInfo")
    public R<Map<String, String>> editStoryboardInfo(@RequestBody OStoryboard storyboard) {
        storyboardMapper.updateById(storyboard);
        return R.ok(Map.of("message", "编辑分镜成功"));
    }

    @PostMapping("/batchDelete")
    public R<Map<String, String>> batchDelete(@RequestBody Map<String, Object> body) {
        @SuppressWarnings("unchecked") List<Integer> ids = body.get("ids") != null ? ((List<Number>) body.get("ids")).stream().map(Number::intValue).collect(java.util.stream.Collectors.toList()) : null;
        if (ids == null || ids.isEmpty()) throw new BusinessException("ids不能为空");
        storyboardMapper.deleteBatchIds(ids);
        return R.ok(Map.of("message", "批量删除成功"));
    }

    @PostMapping("/removeFrame")
    public R<Map<String, String>> removeFrame(@RequestBody Map<String, Object> body) {
        Integer id = body.get("id") != null ? ((Number) body.get("id")).intValue() : null;
        storyboardMapper.deleteById(id);
        return R.ok(Map.of("message", "删除成功"));
    }

    @PostMapping("/pollingImage")
    public R<List<OStoryboard>> pollingImage(@RequestBody Map<String, Object> body) {
        @SuppressWarnings("unchecked") List<Integer> ids = body.get("ids") != null ? ((List<Number>) body.get("ids")).stream().map(Number::intValue).collect(java.util.stream.Collectors.toList()) : null;
        if (ids == null || ids.isEmpty()) return R.ok(List.of());
        return R.ok(storyboardMapper.selectList(
                new LambdaQueryWrapper<OStoryboard>().in(OStoryboard::getId, ids)));
    }

    @PostMapping("/updateStoryboardUrl")
    public R<Map<String, String>> updateStoryboardUrl(@RequestBody OStoryboard storyboard) {
        storyboardMapper.updateById(storyboard);
        return R.ok(Map.of("message", "更新成功"));
    }

    /**
     * 批量新增分镜信息
     */
    @PostMapping("/batchAddStoryboardInfo")
    public R<Map<String, String>> batchAddStoryboardInfo(@RequestBody Map<String, Object> body) {
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> data = (List<Map<String, Object>>) body.get("data");
        Integer scriptId = body.get("scriptId") != null ? ((Number) body.get("scriptId")).intValue() : null;
        Integer projectId = body.get("projectId") != null ? ((Number) body.get("projectId")).intValue() : null;
        if (data == null || data.isEmpty()) throw new BusinessException("数据不能为空");

        int index = 0;
        for (Map<String, Object> item : data) {
            OStoryboard sb = new OStoryboard();
            sb.setProjectId(projectId);
            sb.setScriptId(scriptId);
            sb.setIndex(index++);
            sb.setPrompt((String) item.get("prompt"));
            Object duration = item.get("duration");
            sb.setDuration(duration != null ? duration.toString() : null);
            sb.setTrack((String) item.get("track"));
            sb.setState((String) item.getOrDefault("state", "未生成"));
            sb.setFilePath((String) item.get("src"));
            sb.setVideoDesc((String) item.get("videoDesc"));
            Object shouldGen = item.get("shouldGenerateImage");
            sb.setShouldGenerateImage(shouldGen != null ? (Integer) shouldGen : 0);
            sb.setCreateTime(System.currentTimeMillis());
            storyboardMapper.insert(sb);
        }
        return R.ok(Map.of("message", "批量新增分镜成功"));
    }

    /**
     * 预览分镜图片（返回有序的文件路径列表）
     */
    @PostMapping("/previewImage")
    public R<List<Map<String, Object>>> previewImage(@RequestBody Map<String, Object> body) {
        @SuppressWarnings("unchecked") List<Integer> storyboardIds = body.get("storyboardIds") != null ? ((List<Number>) body.get("storyboardIds")).stream().map(Number::intValue).collect(java.util.stream.Collectors.toList()) : null;
        if (storyboardIds == null || storyboardIds.isEmpty()) return R.ok(List.of());

        List<OStoryboard> storyboards = storyboardMapper.selectList(
                new LambdaQueryWrapper<OStoryboard>().in(OStoryboard::getId, storyboardIds));
        Map<Integer, String> pathMap = new java.util.HashMap<>();
        storyboards.forEach(sb -> pathMap.put(sb.getId(), sb.getFilePath() != null ? sb.getFilePath() : ""));

        // 按入参顺序返回
        List<Map<String, Object>> ordered = storyboardIds.stream()
                .map(id -> {
                    Map<String, Object> m = new java.util.HashMap<>();
                    m.put("id", id);
                    m.put("filePath", pathMap.getOrDefault(id, ""));
                    return m;
                }).toList();
        return R.ok(ordered);
    }

    @PostMapping("/downPreviewImage")
    public R<List<Map<String, Object>>> downPreviewImage(@RequestBody Map<String, Object> body) {
        return previewImage(body);
    }
}
