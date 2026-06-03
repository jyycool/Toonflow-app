package com.toonflow.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.toonflow.common.exception.BusinessException;
import com.toonflow.common.result.R;
import com.toonflow.entity.OAssets;
import com.toonflow.entity.OImage;
import com.toonflow.entity.OVideo;
import com.toonflow.entity.OVideoTrack;
import com.toonflow.mapper.OAssetsMapper;
import com.toonflow.mapper.OImageMapper;
import com.toonflow.mapper.OVideoMapper;
import com.toonflow.mapper.OVideoTrackMapper;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/assets")
@RequiredArgsConstructor
public class AssetsController {

    private final OAssetsMapper assetsMapper;
    private final OImageMapper imageMapper;
    private final OVideoTrackMapper videoTrackMapper;
    private final OVideoMapper videoMapper;

    @PostMapping("/addAssets")
    public R<Map<String, String>> addAssets(@RequestBody OAssets assets) {
        assets.setStartTime(System.currentTimeMillis());
        assetsMapper.insert(assets);
        return R.ok(Map.of("message", "新增素材成功"));
    }

    @PostMapping("/getAssetsApi")
    public R<Map<String, Object>> getAssets(@RequestBody Map<String, Object> body) {
        String projectId = body.get("projectId") != null ? body.get("projectId").toString() : null;
        String type = body.get("type") != null ? body.get("type").toString() : null;
        String name = body.get("name") != null ? body.get("name").toString() : null;
        int page = body.get("page") instanceof Number n ? n.intValue() : 1;
        int limit = body.get("limit") instanceof Number n ? n.intValue() : 10;
        int offset = (page - 1) * limit;

        // Parent assets (assetsId IS NULL), paginated
        LambdaQueryWrapper<OAssets> parentQ = new LambdaQueryWrapper<OAssets>()
                .eq(OAssets::getProjectId, projectId)
                .eq(OAssets::getType, type)
                .isNull(OAssets::getAssetsId);
        if (name != null && !name.isBlank()) parentQ.like(OAssets::getName, name);
        long total = assetsMapper.selectCount(parentQ);
        // MyBatis-Plus doesn't have built-in offset/limit on LambdaQueryWrapper, use last()
        parentQ.last("LIMIT " + limit + " OFFSET " + offset);
        List<OAssets> parentAssets = assetsMapper.selectList(parentQ);

        // All child assets (assetsId IS NOT NULL) for this project+type
        LambdaQueryWrapper<OAssets> childQ = new LambdaQueryWrapper<OAssets>()
                .eq(OAssets::getProjectId, projectId)
                .eq(OAssets::getType, type)
                .isNotNull(OAssets::getAssetsId);
        if (name != null && !name.isBlank()) childQ.like(OAssets::getName, name);
        List<OAssets> childAssets = assetsMapper.selectList(childQ);

        // Collect all imageIds to batch-fetch
        List<String> allImageIds = java.util.stream.Stream.concat(parentAssets.stream(), childAssets.stream())
                .map(OAssets::getImageId).filter(id -> id != null).distinct().collect(Collectors.toList());
        Map<String, OImage> imageMap = new HashMap<>();
        if (!allImageIds.isEmpty()) {
            imageMapper.selectList(new LambdaQueryWrapper<OImage>().in(OImage::getId, allImageIds))
                    .forEach(img -> imageMap.put(img.getId(), img));
        }

        // Build child maps grouped by parent assetsId
        Map<String, List<Map<String, Object>>> childByParent = childAssets.stream()
                .map(c -> buildAssetMap(c, imageMap))
                .collect(Collectors.groupingBy(m -> m.get("assetsId").toString()));

        // Build result
        List<Map<String, Object>> data = parentAssets.stream().map(parent -> {
            Map<String, Object> m = buildAssetMap(parent, imageMap);
            m.put("sonAssets", childByParent.getOrDefault(parent.getId(), List.of()));
            return m;
        }).collect(Collectors.toList());

        Map<String, Object> result = new HashMap<>();
        result.put("data", data);
        result.put("total", total);
        return R.ok(result);
    }

    private Map<String, Object> buildAssetMap(OAssets a, Map<String, OImage> imageMap) {
        Map<String, Object> m = new HashMap<>();
        m.put("id", a.getId());
        m.put("name", a.getName());
        m.put("type", a.getType());
        m.put("describe", a.getDescribe());
        m.put("prompt", a.getPrompt());
        m.put("promptState", a.getPromptState());
        m.put("promptErrorReason", a.getPromptErrorReason());
        m.put("remark", a.getRemark());
        m.put("assetsId", a.getAssetsId());
        m.put("imageId", a.getImageId());
        m.put("projectId", a.getProjectId());
        m.put("startTime", a.getStartTime());
        // join image fields
        OImage img = a.getImageId() != null ? imageMap.get(a.getImageId()) : null;
        m.put("filePath", img != null ? img.getFilePath() : null);
        m.put("state", img != null ? img.getState() : null);
        m.put("errorReason", img != null ? img.getErrorReason() : null);
        // audio: split describe into sex|describe
        if ("audio".equals(a.getType()) && a.getDescribe() != null && a.getDescribe().contains("|")) {
            String[] parts = a.getDescribe().split("\\|", 2);
            m.put("sex", parts[0]);
            m.put("describe", parts[1]);
        }
        return m;
    }

    @PostMapping("/updateAssets")
    public R<Map<String, String>> updateAssets(@RequestBody OAssets assets) {
        assetsMapper.updateById(assets);
        return R.ok(Map.of("message", "更新素材成功"));
    }

    @PostMapping("/delAssets")
    public R<Map<String, String>> delAssets(@RequestBody Map<String, Object> body) {
        String id = body.get("id") != null ? body.get("id").toString() : null;
        if (id == null) throw new BusinessException("id不能为空");
        assetsMapper.deleteById(id);
        return R.ok(Map.of("message", "删除素材成功"));
    }

    @PostMapping("/batchDelete")
    public R<Map<String, String>> batchDelete(@RequestBody Map<String, Object> body) {
        @SuppressWarnings("unchecked") List<String> ids = (List<String>) body.get("ids");
        if (ids == null || ids.isEmpty()) throw new BusinessException("ids不能为空");
        assetsMapper.deleteBatchIds(ids);
        return R.ok(Map.of("message", "批量删除成功"));
    }

    @PostMapping("/getImage")
    public R<OImage> getImage(@RequestBody Map<String, Object> body) {
        return R.ok(imageMapper.selectById(body.get("id") != null ? body.get("id").toString() : null));
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
    public R<List<Map<String, Object>>> pollingImageAssets(@RequestBody Map<String, Object> body) {
        @SuppressWarnings("unchecked") List<Object> rawIds = (List<Object>) body.get("ids");
        if (rawIds == null || rawIds.isEmpty()) return R.ok(List.of());
        List<String> ids = rawIds.stream().map(Object::toString).collect(Collectors.toList());
        // Load assets by id, then join their images — return {state, id (assets.id), filePath}
        List<OAssets> assets = assetsMapper.selectList(
                new LambdaQueryWrapper<OAssets>().in(OAssets::getId, ids));
        List<String> imgIds = assets.stream().filter(a -> a.getImageId() != null)
                .map(OAssets::getImageId).distinct().collect(Collectors.toList());
        Map<String, OImage> imgMap = imgIds.isEmpty() ? Map.of() :
                imageMapper.selectList(new LambdaQueryWrapper<OImage>().in(OImage::getId, imgIds)
                        .ne(OImage::getState, "生成中"))
                        .stream().collect(Collectors.toMap(OImage::getId, i -> i));
        List<Map<String, Object>> result = new ArrayList<>();
        for (OAssets a : assets) {
            OImage img = a.getImageId() != null ? imgMap.get(a.getImageId()) : null;
            if (img == null) continue; // skip if image not found or still generating
            Map<String, Object> m = new HashMap<>();
            m.put("id", a.getId());
            m.put("state", img.getState());
            m.put("filePath", img.getFilePath() != null ? img.getFilePath() : "");
            result.add(m);
        }
        return R.ok(result);
    }

    @PostMapping("/pollingPromptAssets")
    public R<List<OAssets>> pollingPromptAssets(@RequestBody Map<String, Object> body) {
        @SuppressWarnings("unchecked") List<Object> rawIds = (List<Object>) body.get("ids");
        if (rawIds == null || rawIds.isEmpty()) return R.ok(List.of());
        List<String> ids = rawIds.stream().map(Object::toString).collect(Collectors.toList());
        return R.ok(assetsMapper.selectList(
                new LambdaQueryWrapper<OAssets>().in(OAssets::getId, ids)
                        .ne(OAssets::getPromptState, "生成中")));
    }

    @PostMapping("/addAudioAssets")
    public R<Map<String, String>> addAudioAssets(@RequestBody Map<String, Object> body) {
        String projectId = body.get("projectId") != null ? body.get("projectId").toString() : null;
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

    @PostMapping("/getMaterialData")
    public R<Map<String, Object>> getMaterialData(@RequestBody Map<String, Object> body) {
        String projectId = body.get("projectId") != null ? body.get("projectId").toString() : null;
        String scriptId = body.get("scriptId") != null ? body.get("scriptId").toString() : null;

        // Load clip assets joined with their images (assetsId on o_image)
        List<OAssets> clips = assetsMapper.selectList(
                new LambdaQueryWrapper<OAssets>()
                        .eq(OAssets::getProjectId, projectId)
                        .eq(OAssets::getType, "clip"));
        List<String> assetsIds = clips.stream().map(OAssets::getId).collect(Collectors.toList());
        Map<String, OImage> imageByAssetsId = assetsIds.isEmpty() ? Map.of() :
                imageMapper.selectList(new LambdaQueryWrapper<OImage>().in(OImage::getAssetsId, assetsIds))
                        .stream().collect(Collectors.toMap(OImage::getAssetsId, i -> i, (a, b) -> a));
        List<Map<String, Object>> data = new ArrayList<>();
        for (OAssets a : clips) {
            Map<String, Object> m = new HashMap<>();
            m.put("id", a.getId()); m.put("name", a.getName()); m.put("type", a.getType());
            OImage img = imageByAssetsId.get(a.getId());
            m.put("filePath", img != null && img.getFilePath() != null ? img.getFilePath() : "");
            data.add(m);
        }
        // Append ending video placeholder
        Map<String, Object> ending = new HashMap<>();
        ending.put("id", 0); ending.put("name", "Toonflow片尾"); ending.put("type", "clip");
        ending.put("filePath", "/ending.mp4");
        data.add(ending);

        // Load video tracks with generated videos
        List<Map<String, Object>> video = new ArrayList<>();
        if (scriptId != null) {
            List<OVideoTrack> trackRows = videoTrackMapper.selectList(
                    new LambdaQueryWrapper<OVideoTrack>()
                            .eq(OVideoTrack::getScriptId, scriptId)
                            .eq(OVideoTrack::getProjectId, projectId));
            for (OVideoTrack track : trackRows) {
                List<OVideo> videoItems = videoMapper.selectList(
                        new LambdaQueryWrapper<OVideo>()
                                .eq(OVideo::getVideoTrackId, track.getId())
                                .eq(OVideo::getState, "生成成功"));
                if (videoItems.isEmpty()) continue;
                List<Map<String, Object>> videoList = videoItems.stream().map(v -> {
                    Map<String, Object> vm = new HashMap<>();
                    vm.put("id", v.getId());
                    vm.put("filePath", v.getFilePath() != null ? v.getFilePath() : "");
                    vm.put("videoTrackId", v.getVideoTrackId());
                    return vm;
                }).collect(Collectors.toList());
                Map<String, Object> t = new HashMap<>();
                t.put("id", track.getId());
                t.put("videoId", track.getVideoId());
                t.put("video", videoList);
                video.add(t);
            }
        }

        Map<String, Object> result = new HashMap<>();
        result.put("data", data); result.put("video", video);
        return R.ok(result);
    }

    @PostMapping("/batchGenerationData")
    public R<Map<String, Object>> batchGenerationData(@RequestBody Map<String, Object> body) {
        String projectId = body.get("projectId") != null ? body.get("projectId").toString() : null;
        String type = body.get("type") != null ? body.get("type").toString() : null;
        String name = body.get("name") != null ? body.get("name").toString() : null;
        int page = body.get("page") instanceof Number n ? n.intValue() : 1;
        int limit = body.get("limit") instanceof Number n ? n.intValue() : 10;
        int offset = (page - 1) * limit;

        LambdaQueryWrapper<OAssets> q = new LambdaQueryWrapper<OAssets>()
                .eq(OAssets::getProjectId, projectId);
        if (type != null && !type.isBlank()) q.eq(OAssets::getType, type);
        if (name != null && !name.isBlank()) q.like(OAssets::getName, name);

        long total = assetsMapper.selectCount(q);
        q.last("LIMIT " + limit + " OFFSET " + offset);
        List<OAssets> data = assetsMapper.selectList(q);

        Map<String, Object> result = new HashMap<>();
        result.put("data", data);
        result.put("total", total);
        return R.ok(result);
    }

    @PostMapping("/delImage")
    public R<Map<String, String>> delImage(@RequestBody Map<String, Object> body) {
        String id = body.get("id") != null ? body.get("id").toString() : null;
        if (id != null) imageMapper.deleteById(id);
        return R.ok(Map.of("message", "删除图片成功"));
    }

    @Data
    public static class BatchGenerationData {
        @NotNull private String projectId;
        private List<String> ids;
    }
}
