package com.toonflow.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.toonflow.common.exception.BusinessException;
import com.toonflow.common.result.R;
import com.toonflow.entity.OImageFlow;
import com.toonflow.entity.OStoryboard;
import com.toonflow.entity.OVideoTrack;
import com.toonflow.mapper.OAssets2StoryboardMapper;
import com.toonflow.mapper.OImageFlowMapper;
import com.toonflow.mapper.OStoryboardMapper;
import com.toonflow.mapper.OVideoTrackMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/production/storyboard")
@RequiredArgsConstructor
public class StoryboardController {

    private final OStoryboardMapper storyboardMapper;
    private final OAssets2StoryboardMapper assets2StoryboardMapper;
    private final OVideoTrackMapper videoTrackMapper;
    private final OImageFlowMapper imageFlowMapper;

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
        @SuppressWarnings("unchecked") List<Object> rawIds = (List<Object>) body.get("ids");
        String projectId = body.get("projectId") != null ? body.get("projectId").toString() : null;
        if (rawIds == null || rawIds.isEmpty()) throw new BusinessException("请先选择分镜");
        List<String> ids = rawIds.stream().map(Object::toString).collect(Collectors.toList());
        LambdaQueryWrapper<OStoryboard> q = new LambdaQueryWrapper<OStoryboard>().in(OStoryboard::getId, ids);
        if (projectId != null) q.eq(OStoryboard::getProjectId, projectId);
        List<OStoryboard> sbList = storyboardMapper.selectList(q);
        if (sbList.isEmpty()) throw new BusinessException("当前选择分镜不存在");
        // Delete associated imageFlow records
        List<String> flowIds = sbList.stream().filter(s -> s.getFlowId() != null).map(OStoryboard::getFlowId).collect(Collectors.toList());
        if (!flowIds.isEmpty()) flowIds.forEach(fid -> imageFlowMapper.deleteById(fid));
        List<String> sbIds = sbList.stream().map(OStoryboard::getId).collect(Collectors.toList());
        storyboardMapper.deleteBatchIds(sbIds);
        assets2StoryboardMapper.delete(new LambdaQueryWrapper<com.toonflow.entity.OAssets2Storyboard>()
                .in(com.toonflow.entity.OAssets2Storyboard::getStoryboardId, sbIds));
        return R.ok(Map.of("message", "视频删除成功"));
    }

    @PostMapping("/removeFrame")
    public R<Map<String, String>> removeFrame(@RequestBody Map<String, Object> body) {
        String id = body.get("id") != null ? body.get("id").toString() : null;
        OStoryboard sb = storyboardMapper.selectById(id);
        if (sb == null) throw new BusinessException("未找到该分镜");
        // Delete imageFlow if linked
        if (sb.getFlowId() != null) imageFlowMapper.deleteById(sb.getFlowId());
        // If this is the only storyboard in the track, delete the track too
        if (sb.getTrack() != null) {
            long trackCount = storyboardMapper.selectCount(
                    new LambdaQueryWrapper<OStoryboard>().eq(OStoryboard::getTrack, sb.getTrack()));
            if (trackCount == 1 && sb.getTrackId() != null) videoTrackMapper.deleteById(sb.getTrackId());
        }
        storyboardMapper.deleteById(id);
        assets2StoryboardMapper.delete(new LambdaQueryWrapper<com.toonflow.entity.OAssets2Storyboard>()
                .eq(com.toonflow.entity.OAssets2Storyboard::getStoryboardId, id));
        return R.ok(Map.of("message", "视频删除成功"));
    }

    @PostMapping("/pollingImage")
    public R<List<Map<String, Object>>> pollingImage(@RequestBody Map<String, Object> body) {
        @SuppressWarnings("unchecked") List<Object> rawIds = (List<Object>) body.get("ids");
        if (rawIds == null || rawIds.isEmpty()) return R.ok(List.of());
        List<String> ids = rawIds.stream().map(Object::toString).collect(Collectors.toList());
        List<OStoryboard> rows = storyboardMapper.selectList(
                new LambdaQueryWrapper<OStoryboard>()
                        .in(OStoryboard::getId, ids)
                        .ne(OStoryboard::getState, "生成中"));
        List<Map<String, Object>> result = rows.stream().map(s -> {
            Map<String, Object> m = new HashMap<>();
            m.put("id", s.getId());
            m.put("state", s.getState());
            m.put("reason", s.getReason() != null ? s.getReason() : "");
            m.put("filePath", s.getFilePath() != null ? s.getFilePath() : "");
            m.put("src", s.getFilePath() != null ? s.getFilePath() : "");
            m.put("prompt", s.getPrompt());
            return m;
        }).collect(Collectors.toList());
        return R.ok(result);
    }

    @PostMapping("/updateStoryboardUrl")
    public R<Map<String, String>> updateStoryboardUrl(@RequestBody OStoryboard storyboard) {
        storyboardMapper.updateById(storyboard);
        return R.ok(Map.of("message", "更新成功"));
    }

    @PostMapping("/batchAddStoryboardInfo")
    public R<List<Map<String, Object>>> batchAddStoryboardInfo(@RequestBody Map<String, Object> body) {
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> data = (List<Map<String, Object>>) body.get("data");
        String scriptId = body.get("scriptId") != null ? body.get("scriptId").toString() : null;
        String projectId = body.get("projectId") != null ? body.get("projectId").toString() : null;
        if (data == null || data.isEmpty()) throw new BusinessException("数据不能为空");

        // Insert storyboards with associateAssetsIds
        for (Map<String, Object> item : data) {
            OStoryboard sb = new OStoryboard();
            sb.setProjectId(projectId);
            sb.setScriptId(scriptId);
            sb.setPrompt((String) item.get("prompt"));
            Object duration = item.get("duration");
            sb.setDuration(duration != null ? duration.toString() : null);
            sb.setTrack((String) item.get("track"));
            sb.setState((String) item.getOrDefault("state", "未生成"));
            sb.setFilePath((String) item.get("src"));
            sb.setVideoDesc((String) item.get("videoDesc"));
            Object shouldGen = item.get("shouldGenerateImage");
            sb.setShouldGenerateImage(shouldGen instanceof Number n ? n.intValue() : 0);
            sb.setCreateTime(System.currentTimeMillis());
            storyboardMapper.insert(sb);
            item.put("_insertedId", sb.getId());

            @SuppressWarnings("unchecked")
            List<Object> assocIds = (List<Object>) item.get("associateAssetsIds");
            if (assocIds != null && !assocIds.isEmpty()) {
                for (Object aid : assocIds) {
                    com.toonflow.entity.OAssets2Storyboard a2s = new com.toonflow.entity.OAssets2Storyboard();
                    a2s.setAssetId(aid.toString());
                    a2s.setStoryboardId(sb.getId());
                    assets2StoryboardMapper.insert(a2s);
                }
            }
        }

        // Reload all storyboards for this script
        List<OStoryboard> allStoryboards = storyboardMapper.selectList(
                new LambdaQueryWrapper<OStoryboard>().eq(OStoryboard::getScriptId, scriptId));

        // Group by track, create/reuse video tracks
        Map<String, List<OStoryboard>> byTrack = new LinkedHashMap<>();
        for (OStoryboard s : allStoryboards) {
            byTrack.computeIfAbsent(s.getTrack() != null ? s.getTrack() : "", k -> new ArrayList<>()).add(s);
        }

        for (Map.Entry<String, List<OStoryboard>> entry : byTrack.entrySet()) {
            String track = entry.getKey();
            List<OStoryboard> trackSbs = entry.getValue();
            double totalDuration = trackSbs.stream()
                    .mapToDouble(s -> s.getDuration() != null ? Double.parseDouble(s.getDuration()) : 0.0).sum();

            // Find existing trackId for this track name
            OStoryboard existing = trackSbs.stream().filter(s -> s.getTrackId() != null).findFirst().orElse(null);
            String trackId;
            if (existing != null) {
                trackId = existing.getTrackId();
                OVideoTrack vt = new OVideoTrack();
                vt.setId(trackId); vt.setDuration((int) totalDuration);
                videoTrackMapper.updateById(vt);
            } else {
                trackId = String.valueOf(System.currentTimeMillis());
                OVideoTrack vt = new OVideoTrack();
                vt.setId(trackId); vt.setScriptId(scriptId);
                vt.setProjectId(projectId); vt.setDuration((int) totalDuration);
                videoTrackMapper.insert(vt);
            }

            // Update all storyboards in this track
            List<String> sbIds = trackSbs.stream().map(OStoryboard::getId).collect(Collectors.toList());
            final String finalTrackId = trackId;
            storyboardMapper.selectList(new LambdaQueryWrapper<OStoryboard>().in(OStoryboard::getId, sbIds))
                    .forEach(s -> { s.setTrackId(finalTrackId); storyboardMapper.updateById(s); });
        }

        // Reload with updated trackIds
        List<OStoryboard> finalList = storyboardMapper.selectList(
                new LambdaQueryWrapper<OStoryboard>().eq(OStoryboard::getScriptId, scriptId));
        List<String> sbIds = finalList.stream().map(OStoryboard::getId).collect(Collectors.toList());
        Map<String, List<String>> a2sMap = new HashMap<>();
        if (!sbIds.isEmpty()) {
            assets2StoryboardMapper.selectList(new LambdaQueryWrapper<com.toonflow.entity.OAssets2Storyboard>()
                            .in(com.toonflow.entity.OAssets2Storyboard::getStoryboardId, sbIds))
                    .forEach(r -> a2sMap.computeIfAbsent(r.getStoryboardId(), k -> new ArrayList<>()).add(r.getAssetId()));
        }

        List<Map<String, Object>> result = finalList.stream().map(s -> {
            Map<String, Object> m = new HashMap<>();
            m.put("id", s.getId());
            m.put("trackId", s.getTrackId());
            m.put("prompt", s.getPrompt());
            m.put("duration", s.getDuration() != null ? Double.parseDouble(s.getDuration()) : 0.0);
            m.put("state", s.getState());
            m.put("scriptId", s.getScriptId());
            m.put("reason", s.getReason() != null ? s.getReason() : "");
            m.put("videoDesc", s.getVideoDesc());
            m.put("src", s.getFilePath() != null ? s.getFilePath() : "");
            m.put("associateAssetsIds", a2sMap.getOrDefault(s.getId(), List.of()));
            return m;
        }).collect(Collectors.toList());
        return R.ok(result);
    }

    @PostMapping("/previewImage")
    public R<List<Map<String, Object>>> previewImage(@RequestBody Map<String, Object> body) {
        @SuppressWarnings("unchecked") List<Object> rawSbIds = (List<Object>) body.get("storyboardIds");
        if (rawSbIds == null || rawSbIds.isEmpty()) return R.ok(List.of());
        List<String> storyboardIds = rawSbIds.stream().map(Object::toString).collect(Collectors.toList());

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
