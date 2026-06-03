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
import java.util.stream.Collectors;

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
    public R<Map<String, Object>> getStoryboardData(@RequestBody Map<String, Object> body) {
        String scriptId = body.get("scriptId") != null ? body.get("scriptId").toString() : null;
        String name = body.get("name") != null ? body.get("name").toString() : null;
        int page = body.get("page") instanceof Number n ? n.intValue() : 1;
        int limit = body.get("limit") instanceof Number n ? n.intValue() : 20;
        int offset = (page - 1) * limit;

        LambdaQueryWrapper<OStoryboard> q = new LambdaQueryWrapper<OStoryboard>()
                .eq(OStoryboard::getScriptId, scriptId);
        // TS filters by "title" but entity has no title; skip name filter
        long total = storyboardMapper.selectCount(q);
        q.last("LIMIT " + limit + " OFFSET " + offset);
        List<OStoryboard> rows = storyboardMapper.selectList(q);

        List<Map<String, Object>> data = rows.stream().map(i -> {
            Map<String, Object> m = new java.util.HashMap<>();
            m.put("id", i.getId());
            m.put("prompt", i.getPrompt());
            m.put("state", i.getState());
            m.put("src", i.getFilePath() != null ? i.getFilePath() : "");
            return m;
        }).collect(Collectors.toList());

        Map<String, Object> result = new java.util.HashMap<>();
        result.put("data", data);
        result.put("total", total);
        return R.ok(result);
    }

    @PostMapping("/editStoryboardInfo")
    public R<Map<String, String>> editStoryboardInfo(@RequestBody OStoryboard storyboard) {
        storyboardMapper.updateById(storyboard);
        return R.ok(Map.of("message", "编辑分镜成功"));
    }

    @PostMapping("/batchDelete")
    public R<Map<String, String>> batchDelete(@RequestBody Map<String, Object> body) {
        @SuppressWarnings("unchecked") List<String> ids = (List<String>) body.get("ids");
        if (ids == null || ids.isEmpty()) throw new BusinessException("ids不能为空");
        storyboardMapper.deleteBatchIds(ids);
        return R.ok(Map.of("message", "批量删除成功"));
    }

    @PostMapping("/removeFrame")
    public R<Map<String, String>> removeFrame(@RequestBody Map<String, Object> body) {
        String id = body.get("id") != null ? body.get("id").toString() : null;
        storyboardMapper.deleteById(id);
        return R.ok(Map.of("message", "删除成功"));
    }

    @PostMapping("/pollingImage")
    public R<List<OStoryboard>> pollingImage(@RequestBody Map<String, Object> body) {
        @SuppressWarnings("unchecked") List<String> ids = (List<String>) body.get("ids");
        if (ids == null || ids.isEmpty()) return R.ok(List.of());
        return R.ok(storyboardMapper.selectList(
                new LambdaQueryWrapper<OStoryboard>().in(OStoryboard::getId, ids)));
    }

    @PostMapping("/updateStoryboardUrl")
    public R<Map<String, String>> updateStoryboardUrl(@RequestBody OStoryboard storyboard) {
        storyboardMapper.updateById(storyboard);
        return R.ok(Map.of("message", "更新成功"));
    }

    @PostMapping("/batchAddStoryboardInfo")
    public R<Map<String, String>> batchAddStoryboardInfo(@RequestBody Map<String, Object> body) {
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> data = (List<Map<String, Object>>) body.get("data");
        String scriptId = body.get("scriptId") != null ? body.get("scriptId").toString() : null;
        String projectId = body.get("projectId") != null ? body.get("projectId").toString() : null;
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

    @PostMapping("/previewImage")
    public R<List<Map<String, Object>>> previewImage(@RequestBody Map<String, Object> body) {
        @SuppressWarnings("unchecked") List<String> storyboardIds = (List<String>) body.get("storyboardIds");
        if (storyboardIds == null || storyboardIds.isEmpty()) return R.ok(List.of());

        List<OStoryboard> storyboards = storyboardMapper.selectList(
                new LambdaQueryWrapper<OStoryboard>().in(OStoryboard::getId, storyboardIds));
        Map<String, String> pathMap = new java.util.HashMap<>();
        storyboards.forEach(sb -> pathMap.put(sb.getId(), sb.getFilePath() != null ? sb.getFilePath() : ""));

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
