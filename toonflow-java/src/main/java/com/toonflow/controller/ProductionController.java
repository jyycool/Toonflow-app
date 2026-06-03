package com.toonflow.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.toonflow.common.result.R;
import com.toonflow.entity.OImageFlow;
import com.toonflow.entity.OProject;
import com.toonflow.entity.OStoryboard;
import com.toonflow.entity.OVideo;
import com.toonflow.entity.OVideoTrack;
import com.toonflow.mapper.*;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/production")
@RequiredArgsConstructor
public class ProductionController {

    private final OStoryboardMapper storyboardMapper;
    private final OVideoMapper videoMapper;
    private final OVideoTrackMapper videoTrackMapper;
    private final OImageFlowMapper imageFlowMapper;
    private final OProjectMapper projectMapper;
    private final OAssetsMapper assetsMapper;
    private final OImageMapper imageMapper;
    private final OAssets2StoryboardMapper assets2StoryboardMapper;
    private final OAssetsRole2AudioMapper role2AudioMapper;
    private final com.toonflow.mapper.OAgentWorkDataMapper agentWorkDataMapper;
    private final com.toonflow.mapper.OScriptMapper scriptMapper;
    private final com.toonflow.mapper.OScriptAssetsMapper scriptAssetsMapper;
    private final com.toonflow.mapper.OAssetsRole2AudioMapper assetsRole2AudioMapper;
    private final com.toonflow.mapper.OPromptMapper promptMapper;
    private final com.toonflow.ai.vendor.VideoGenerationService videoGenerationService;
    private final com.toonflow.ai.vendor.MediaGenerationService mediaGenerationService;
    private final com.toonflow.ai.TaskRecordService taskRecordService;
    private final com.toonflow.ai.AiService aiService;

    // ========== Flow 数据 ==========

    @PostMapping("/getFlowData")
    public R<Map<String, Object>> getFlowData(@RequestBody Map<String, Object> body) {
        String projectId = body.get("projectId") != null ? body.get("projectId").toString() : null;
        String episodesId = body.get("episodesId") != null ? body.get("episodesId").toString() : null;

        // Load script content
        com.toonflow.entity.OScript script = scriptMapper.selectOne(
                new LambdaQueryWrapper<com.toonflow.entity.OScript>()
                        .eq(com.toonflow.entity.OScript::getProjectId, projectId)
                        .eq(com.toonflow.entity.OScript::getId, episodesId).last("LIMIT 1"));

        // Load parent assets for this script
        List<com.toonflow.entity.OScriptAssets> scriptAssets = scriptAssetsMapper.selectList(
                new LambdaQueryWrapper<com.toonflow.entity.OScriptAssets>()
                        .eq(com.toonflow.entity.OScriptAssets::getScriptId, episodesId));
        List<String> assetIds = scriptAssets.stream().map(com.toonflow.entity.OScriptAssets::getAssetId).collect(Collectors.toList());

        List<com.toonflow.entity.OAssets> parentAssets = assetIds.isEmpty() ? List.of() :
                assetsMapper.selectList(new LambdaQueryWrapper<com.toonflow.entity.OAssets>()
                        .in(com.toonflow.entity.OAssets::getId, assetIds)
                        .isNull(com.toonflow.entity.OAssets::getAssetsId)
                        .eq(com.toonflow.entity.OAssets::getProjectId, projectId));
        List<com.toonflow.entity.OAssets> childAssets = assetIds.isEmpty() ? List.of() :
                assetsMapper.selectList(new LambdaQueryWrapper<com.toonflow.entity.OAssets>()
                        .eq(com.toonflow.entity.OAssets::getProjectId, projectId)
                        .in(com.toonflow.entity.OAssets::getAssetsId, assetIds)
                        .isNotNull(com.toonflow.entity.OAssets::getAssetsId));

        // Batch load images
        Set<String> imageIdSet = new HashSet<>();
        parentAssets.forEach(a -> { if (a.getImageId() != null) imageIdSet.add(a.getImageId()); });
        childAssets.forEach(a -> { if (a.getImageId() != null) imageIdSet.add(a.getImageId()); });
        Map<String, com.toonflow.entity.OImage> imageMap = imageIdSet.isEmpty() ? Map.of() :
                imageMapper.selectList(new LambdaQueryWrapper<com.toonflow.entity.OImage>().in(com.toonflow.entity.OImage::getId, imageIdSet))
                        .stream().collect(Collectors.toMap(com.toonflow.entity.OImage::getId, i -> i));

        // Check for saved agent work data
        com.toonflow.entity.OAgentWorkData workData = agentWorkDataMapper.selectOne(
                new LambdaQueryWrapper<com.toonflow.entity.OAgentWorkData>()
                        .eq(com.toonflow.entity.OAgentWorkData::getProjectId, projectId)
                        .eq(com.toonflow.entity.OAgentWorkData::getEpisodesId, episodesId)
                        .last("LIMIT 1"));

        // Build asset list (shared between both paths)
        List<Map<String, Object>> assetList = parentAssets.stream().map(item -> {
            com.toonflow.entity.OImage img = item.getImageId() != null ? imageMap.get(item.getImageId()) : null;
            Map<String, Object> a = new HashMap<>();
            a.put("id", item.getId());
            a.put("name", nvl(item.getName()));
            a.put("type", nvl(item.getType()));
            a.put("prompt", nvl(item.getPrompt()));
            a.put("desc", nvl(item.getDescribe()));
            a.put("src", img != null ? img.getFilePath() : null);
            a.put("flowId", item.getFlowId());
            List<Map<String, Object>> derive = childAssets.stream()
                    .filter(c -> item.getId().equals(c.getAssetsId()))
                    .map(c -> {
                        com.toonflow.entity.OImage ci = c.getImageId() != null ? imageMap.get(c.getImageId()) : null;
                        Map<String, Object> d = new HashMap<>();
                        d.put("id", c.getId());
                        d.put("assetsId", item.getId());
                        d.put("name", nvl(c.getName()));
                        d.put("type", c.getType());
                        d.put("prompt", c.getPrompt());
                        d.put("desc", nvl(c.getDescribe()));
                        d.put("src", ci != null ? ci.getFilePath() : null);
                        d.put("state", ci != null && ci.getState() != null ? ci.getState() : "未生成");
                        d.put("errorReason", ci != null ? ci.getErrorReason() : null);
                        d.put("flowId", c.getFlowId());
                        return d;
                    }).collect(Collectors.toList());
            a.put("derive", derive);
            return a;
        }).collect(Collectors.toList());

        if (workData == null) {
            // No saved data — return empty flow structure
            Map<String, Object> flowData = new HashMap<>();
            flowData.put("script", script != null && script.getContent() != null ? script.getContent() : "");
            flowData.put("scriptPlan", "");
            flowData.put("assets", assetList);
            flowData.put("storyboardTable", "");
            flowData.put("storyboard", List.of());
            flowData.put("workbench", Map.of("videoList", List.of()));
            return R.ok(flowData);
        } else {
            // Has saved data — merge assets + storyboard
            Map<String, Object> flowData;
            try {
                flowData = new com.fasterxml.jackson.databind.ObjectMapper()
                        .readValue(workData.getData(), new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
            } catch (Exception e) {
                flowData = new HashMap<>();
            }
            flowData.put("assets", assetList);

            // Storyboard with associated asset IDs
            List<com.toonflow.entity.OStoryboard> storyboardData = storyboardMapper.selectList(
                    new LambdaQueryWrapper<OStoryboard>().eq(OStoryboard::getScriptId, episodesId));
            List<String> sbIds = storyboardData.stream().map(OStoryboard::getId).collect(Collectors.toList());
            Map<String, List<String>> assets2SbMap = new HashMap<>();
            if (!sbIds.isEmpty()) {
                assets2StoryboardMapper.selectList(new LambdaQueryWrapper<com.toonflow.entity.OAssets2Storyboard>()
                        .in(com.toonflow.entity.OAssets2Storyboard::getStoryboardId, sbIds))
                        .forEach(r -> assets2SbMap.computeIfAbsent(r.getStoryboardId(), k -> new ArrayList<>()).add(r.getAssetId()));
            }
            List<Map<String, Object>> storyboard = storyboardData.stream()
                    .sorted(Comparator.comparingInt(s -> s.getIndex() != null ? s.getIndex() : 0))
                    .map(s -> {
                        Map<String, Object> m = new HashMap<>();
                        m.put("id", s.getId());
                        m.put("index", s.getIndex());
                        m.put("duration", s.getDuration() != null ? Double.parseDouble(s.getDuration()) : 0.0);
                        m.put("prompt", s.getPrompt());
                        m.put("associateAssetsIds", assets2SbMap.getOrDefault(s.getId(), List.of()));
                        m.put("src", s.getFilePath());
                        m.put("state", s.getState());
                        m.put("videoDesc", s.getVideoDesc());
                        m.put("shouldGenerateImage", s.getShouldGenerateImage());
                        m.put("reason", nvl(s.getReason()));
                        m.put("flowId", s.getFlowId());
                        return m;
                    }).collect(Collectors.toList());
            flowData.put("storyboard", storyboard);
            return R.ok(flowData);
        }
    }

    private String nvl(String s) { return s != null ? s : ""; }

    @PostMapping("/saveFlowData")
    public R<Void> saveFlowData(@RequestBody Map<String, Object> body) {
        String projectId = body.get("projectId") != null ? body.get("projectId").toString() : null;
        String episodesId = body.get("episodesId") != null ? body.get("episodesId").toString() : null;
        String dataJson;
        try {
            dataJson = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(body.get("data"));
        } catch (Exception e) {
            dataJson = "{}";
        }

        // Update storyboard order if data.storyboard present
        Object dataObj = body.get("data");
        if (dataObj instanceof Map<?, ?> dataMap) {
            Object sbObj = dataMap.get("storyboard");
            if (sbObj instanceof List<?> sbList) {
                boolean allHaveId = sbList.stream().allMatch(i -> i instanceof Map<?, ?> m && m.get("id") != null);
                if (!sbList.isEmpty() && allHaveId) {
                    for (int idx = 0; idx < sbList.size(); idx++) {
                        if (sbList.get(idx) instanceof Map<?, ?> sm && sm.get("id") != null) {
                            OStoryboard sb = new OStoryboard();
                            sb.setId(sm.get("id").toString());
                            sb.setIndex(idx);
                            storyboardMapper.updateById(sb);
                        }
                    }
                }
            }
        }

        com.toonflow.entity.OAgentWorkData existing = agentWorkDataMapper.selectOne(
                new LambdaQueryWrapper<com.toonflow.entity.OAgentWorkData>()
                        .eq(com.toonflow.entity.OAgentWorkData::getProjectId, projectId)
                        .eq(com.toonflow.entity.OAgentWorkData::getEpisodesId, episodesId)
                        .eq(com.toonflow.entity.OAgentWorkData::getKey, "productionAgent")
                        .last("LIMIT 1"));
        if (existing == null) {
            com.toonflow.entity.OAgentWorkData record = new com.toonflow.entity.OAgentWorkData();
            record.setProjectId(projectId);
            record.setEpisodesId(episodesId);
            record.setKey("productionAgent");
            record.setData(dataJson);
            agentWorkDataMapper.insert(record);
        } else {
            existing.setData(dataJson);
            agentWorkDataMapper.updateById(existing);
        }
        return R.ok();
    }

    @PostMapping("/getStoryboardData")
    public R<List<Map<String, Object>>> getStoryboardData(@RequestBody Map<String, Object> body) {
        String scriptId = body.get("scriptId") != null ? body.get("scriptId").toString() : null;
        List<OStoryboard> storyboardData = storyboardMapper.selectList(
                new LambdaQueryWrapper<OStoryboard>()
                        .eq(OStoryboard::getScriptId, scriptId)
                        .orderByAsc(OStoryboard::getIndex));
        if (storyboardData.isEmpty()) return R.ok(List.of());

        List<String> sbIds = storyboardData.stream().map(OStoryboard::getId).collect(Collectors.toList());

        // Join o_assets2Storyboard -> o_assets -> o_image to get characters
        List<com.toonflow.entity.OAssets2Storyboard> a2s = assets2StoryboardMapper.selectList(
                new LambdaQueryWrapper<com.toonflow.entity.OAssets2Storyboard>()
                        .in(com.toonflow.entity.OAssets2Storyboard::getStoryboardId, sbIds));

        Map<String, List<Map<String, Object>>> charactersMap = new HashMap<>();
        if (!a2s.isEmpty()) {
            List<String> aIds = a2s.stream().map(com.toonflow.entity.OAssets2Storyboard::getAssetId).distinct().collect(Collectors.toList());
            List<com.toonflow.entity.OAssets> assets = assetsMapper.selectList(
                    new LambdaQueryWrapper<com.toonflow.entity.OAssets>().in(com.toonflow.entity.OAssets::getId, aIds)
                            .select(com.toonflow.entity.OAssets::getId, com.toonflow.entity.OAssets::getName,
                                    com.toonflow.entity.OAssets::getType, com.toonflow.entity.OAssets::getImageId));
            Set<String> imgIds = assets.stream().filter(a -> a.getImageId() != null).map(com.toonflow.entity.OAssets::getImageId).collect(Collectors.toSet());
            Map<String, String> imgPathMap = imgIds.isEmpty() ? Map.of() :
                    imageMapper.selectList(new LambdaQueryWrapper<com.toonflow.entity.OImage>().in(com.toonflow.entity.OImage::getId, imgIds)
                                    .select(com.toonflow.entity.OImage::getId, com.toonflow.entity.OImage::getFilePath))
                            .stream().collect(Collectors.toMap(com.toonflow.entity.OImage::getId, i -> i.getFilePath() != null ? i.getFilePath() : ""));
            Map<String, com.toonflow.entity.OAssets> assetById = assets.stream().collect(Collectors.toMap(com.toonflow.entity.OAssets::getId, a -> a));

            for (com.toonflow.entity.OAssets2Storyboard rel : a2s) {
                com.toonflow.entity.OAssets asset = assetById.get(rel.getAssetId());
                if (asset == null) continue;
                Map<String, Object> c = new HashMap<>();
                c.put("name", nvl(asset.getName()));
                c.put("type", nvl(asset.getType()));
                if (asset.getImageId() != null) c.put("avatar", imgPathMap.getOrDefault(asset.getImageId(), ""));
                charactersMap.computeIfAbsent(rel.getStoryboardId(), k -> new ArrayList<>()).add(c);
            }
        }

        List<Map<String, Object>> result = storyboardData.stream().map(item -> {
            Map<String, Object> m = new HashMap<>();
            m.put("id", String.valueOf(item.getId()));
            m.put("createTime", item.getCreateTime());
            m.put("duration", item.getDuration() != null ? Double.parseDouble(item.getDuration()) : null);
            m.put("filePath", item.getFilePath() != null ? item.getFilePath() : "");
            m.put("prompt", item.getPrompt());
            m.put("scriptId", item.getScriptId());
            m.put("characters", charactersMap.getOrDefault(item.getId(), List.of()));
            return m;
        }).collect(Collectors.toList());
        return R.ok(result);
    }

    // ========== 视频工作台 ==========

    @PostMapping("/workbench/getVideoList")
    public R<List<OVideo>> getVideoList(@RequestBody Map<String, Object> body) {
        String projectId = body.get("projectId") != null ? body.get("projectId").toString() : null;
        String scriptId = body.get("scriptId") != null ? body.get("scriptId").toString() : null;
        // Get trackIds from storyboards for this scriptId, then load videos
        List<OStoryboard> storyboards = storyboardMapper.selectList(
                new LambdaQueryWrapper<OStoryboard>()
                        .eq(OStoryboard::getProjectId, projectId)
                        .eq(OStoryboard::getScriptId, scriptId)
                        .orderByAsc(OStoryboard::getIndex));
        List<String> trackIds = storyboards.stream()
                .filter(s -> s.getTrackId() != null)
                .map(OStoryboard::getTrackId).distinct().collect(Collectors.toList());
        if (trackIds.isEmpty()) return R.ok(List.of());
        return R.ok(videoMapper.selectList(
                new LambdaQueryWrapper<OVideo>().in(OVideo::getVideoTrackId, trackIds)));
    }

    @PostMapping("/workbench/addTrack")
    public R<Map<String, String>> addTrack(@RequestBody OVideoTrack track) {
        videoTrackMapper.insert(track);
        return R.ok(Map.of("message", "添加轨道成功"));
    }

    @PostMapping("/workbench/deleteTrack")
    public R<Map<String, String>> deleteTrack(@RequestBody Map<String, Object> body) {
        String id = body.get("id") != null ? body.get("id").toString() : null;
        videoTrackMapper.deleteById(id);
        return R.ok(Map.of("message", "删除轨道成功"));
    }

    @PostMapping("/workbench/selectVideo")
    public R<Map<String, String>> selectVideo(@RequestBody Map<String, Object> body) {
        String trackId = body.get("trackId") != null ? body.get("trackId").toString() : null;
        OVideoTrack track = videoTrackMapper.selectById(trackId);
        if (track != null) {
            track.setSelectVideoId(body.get("videoId") != null ? body.get("videoId").toString() : null);
            videoTrackMapper.updateById(track);
        }
        return R.ok(Map.of("message", "选择视频成功"));
    }

    @PostMapping("/workbench/delVideo")
    public R<Map<String, String>> delVideo(@RequestBody Map<String, Object> body) {
        String id = body.get("id") != null ? body.get("id").toString() : null;
        videoMapper.deleteById(id);
        return R.ok(Map.of("message", "删除视频成功"));
    }

    @PostMapping("/workbench/updateVideoPrompt")
    public R<Map<String, String>> updateVideoPrompt(@RequestBody OVideoTrack track) {
        videoTrackMapper.updateById(track);
        return R.ok(Map.of("message", "更新提示词成功"));
    }

    @PostMapping("/workbench/updateVideoDuration")
    public R<Map<String, String>> updateVideoDuration(@RequestBody OVideoTrack track) {
        videoTrackMapper.updateById(track);
        return R.ok(Map.of("message", "更新时长成功"));
    }

    @PostMapping("/workbench/getGenerateData")
    public R<Map<String, Object>> getGenerateData(@RequestBody Map<String, Object> body) {
        String projectId = body.get("projectId") != null ? body.get("projectId").toString() : null;
        String scriptId = body.get("scriptId") != null ? body.get("scriptId").toString() : null;

        // Determine isRef from project videoMode
        OProject project = projectMapper.selectById(projectId);
        boolean isRef = false;
        if (project != null && project.getMode() != null) {
            try {
                com.fasterxml.jackson.databind.ObjectMapper om = new com.fasterxml.jackson.databind.ObjectMapper();
                Object parsed = om.readValue(project.getMode(), Object.class);
                isRef = parsed instanceof List;
            } catch (Exception ignored) {}
        }
        // Parse audioReferenceCount from videoMode array
        int audioReferenceCount = 0;
        if (isRef && project != null && project.getMode() != null) {
            try {
                com.fasterxml.jackson.databind.ObjectMapper om = new com.fasterxml.jackson.databind.ObjectMapper();
                List<?> modeList = om.readValue(project.getMode(), List.class);
                for (Object v : modeList) {
                    String sv = v.toString().toLowerCase();
                    if (sv.startsWith("audioreference:")) {
                        try { audioReferenceCount = Integer.parseInt(sv.split(":")[1]); } catch (Exception ignored) {}
                    }
                }
            } catch (Exception ignored) {}
        }

        // Storyboard list
        List<OStoryboard> storyboardList = storyboardMapper.selectList(
                new LambdaQueryWrapper<OStoryboard>()
                        .eq(OStoryboard::getScriptId, scriptId)
                        .eq(OStoryboard::getProjectId, projectId)
                        .orderByAsc(OStoryboard::getIndex));

        // Group storyboards by trackId -> medias(image entries)
        Map<String, List<Map<String, Object>>> storyboardTrackRecord = new LinkedHashMap<>();
        Map<String, String> sbIdToTrackId = new HashMap<>();
        for (OStoryboard s : storyboardList) {
            if (s.getTrackId() == null) continue;
            sbIdToTrackId.put(s.getId(), s.getTrackId());
            Map<String, Object> entry = new HashMap<>();
            entry.put("src", s.getFilePath() != null ? s.getFilePath() : "");
            entry.put("fileType", "image");
            entry.put("sources", "storyboard");
            entry.put("id", s.getId());
            entry.put("index", s.getIndex());
            if (s.getVideoDesc() != null) entry.put("prompt", s.getVideoDesc());
            storyboardTrackRecord.computeIfAbsent(s.getTrackId(), k -> new ArrayList<>()).add(entry);
        }

        // If isRef: load assets per storyboard with audio bindings
        Map<String, List<Map<String, Object>>> otherDataMap = new HashMap<>(); // key=storyboardId
        if (isRef && !storyboardList.isEmpty()) {
            List<String> sbIds = storyboardList.stream().map(OStoryboard::getId).collect(Collectors.toList());
            List<com.toonflow.entity.OAssets2Storyboard> a2sList = assets2StoryboardMapper.selectList(
                    new LambdaQueryWrapper<com.toonflow.entity.OAssets2Storyboard>()
                            .in(com.toonflow.entity.OAssets2Storyboard::getStoryboardId, sbIds));
            List<String> aIds = a2sList.stream().map(com.toonflow.entity.OAssets2Storyboard::getAssetId).distinct().collect(Collectors.toList());
            if (!aIds.isEmpty()) {
                List<com.toonflow.entity.OAssets> assetDatas = assetsMapper.selectList(
                        new LambdaQueryWrapper<com.toonflow.entity.OAssets>().in(com.toonflow.entity.OAssets::getId, aIds));
                Set<String> imgIds = assetDatas.stream().filter(a -> a.getImageId() != null).map(com.toonflow.entity.OAssets::getImageId).collect(Collectors.toSet());
                Map<String, String> imgPathMap = imgIds.isEmpty() ? Map.of() :
                        imageMapper.selectList(new LambdaQueryWrapper<com.toonflow.entity.OImage>().in(com.toonflow.entity.OImage::getId, imgIds)
                                        .select(com.toonflow.entity.OImage::getId, com.toonflow.entity.OImage::getFilePath))
                                .stream().collect(Collectors.toMap(com.toonflow.entity.OImage::getId, i -> nvl(i.getFilePath())));

                // Load audio bindings
                Set<String> queryAudioIds = new HashSet<>();
                assetDatas.forEach(a -> { queryAudioIds.add(a.getId()); if (a.getAssetsId() != null) queryAudioIds.add(a.getAssetsId()); });
                Map<String, List<Map<String, Object>>> audioRecord = new HashMap<>();
                if (!queryAudioIds.isEmpty()) {
                    List<com.toonflow.entity.OAssetsRole2Audio> audioBinds = assetsRole2AudioMapper.selectList(
                            new LambdaQueryWrapper<com.toonflow.entity.OAssetsRole2Audio>()
                                    .in(com.toonflow.entity.OAssetsRole2Audio::getAssetsRoleId, queryAudioIds));
                    List<String> audioAssetIds = audioBinds.stream().map(com.toonflow.entity.OAssetsRole2Audio::getAssetsAudioId).distinct().collect(Collectors.toList());
                    if (!audioAssetIds.isEmpty()) {
                        List<com.toonflow.entity.OAssets> audioAssets = assetsMapper.selectList(
                                new LambdaQueryWrapper<com.toonflow.entity.OAssets>().in(com.toonflow.entity.OAssets::getId, audioAssetIds));
                        Set<String> audioImgIds = audioAssets.stream().filter(a -> a.getImageId() != null).map(com.toonflow.entity.OAssets::getImageId).collect(Collectors.toSet());
                        Map<String, String> audioImgMap = audioImgIds.isEmpty() ? Map.of() :
                                imageMapper.selectList(new LambdaQueryWrapper<com.toonflow.entity.OImage>().in(com.toonflow.entity.OImage::getId, audioImgIds)
                                                .select(com.toonflow.entity.OImage::getId, com.toonflow.entity.OImage::getFilePath))
                                        .stream().collect(Collectors.toMap(com.toonflow.entity.OImage::getId, i -> nvl(i.getFilePath())));
                        Map<String, com.toonflow.entity.OAssets> audioAssetMap = audioAssets.stream().collect(Collectors.toMap(com.toonflow.entity.OAssets::getId, a -> a));
                        for (com.toonflow.entity.OAssetsRole2Audio bind : audioBinds) {
                            com.toonflow.entity.OAssets aa = audioAssetMap.get(bind.getAssetsAudioId());
                            if (aa == null) continue;
                            Map<String, Object> ae = new HashMap<>();
                            ae.put("id", aa.getId()); ae.put("name", aa.getName()); ae.put("describe", aa.getDescribe());
                            ae.put("type", aa.getType()); ae.put("fileType", "audio"); ae.put("sources", "assets");
                            ae.put("prompt", aa.getPrompt());
                            ae.put("src", aa.getImageId() != null ? audioImgMap.getOrDefault(aa.getImageId(), "") : "");
                            audioRecord.computeIfAbsent(bind.getAssetsRoleId(), k -> new ArrayList<>()).add(ae);
                        }
                    }
                }

                Map<String, com.toonflow.entity.OAssets> assetById = assetDatas.stream().collect(Collectors.toMap(com.toonflow.entity.OAssets::getId, a -> a));
                for (com.toonflow.entity.OAssets2Storyboard rel : a2sList) {
                    com.toonflow.entity.OAssets asset = assetById.get(rel.getAssetId());
                    if (asset == null) continue;
                    Map<String, Object> item = new HashMap<>();
                    item.put("id", asset.getId()); item.put("name", asset.getName()); item.put("describe", asset.getDescribe());
                    item.put("type", asset.getType()); item.put("fileType", "image"); item.put("sources", "assets");
                    item.put("src", asset.getImageId() != null ? imgPathMap.getOrDefault(asset.getImageId(), "") : "");
                    List<Map<String, Object>> sbList = otherDataMap.computeIfAbsent(rel.getStoryboardId(), k -> new ArrayList<>());
                    sbList.add(item);
                    if (audioRecord.containsKey(asset.getId())) sbList.addAll(audioRecord.get(asset.getId()));
                    if (asset.getAssetsId() != null && audioRecord.containsKey(asset.getAssetsId())) sbList.addAll(audioRecord.get(asset.getAssetsId()));
                }
            }
        }

        // Tracks + videos
        List<OVideoTrack> trackData = videoTrackMapper.selectList(
                new LambdaQueryWrapper<OVideoTrack>().eq(OVideoTrack::getProjectId, projectId).eq(OVideoTrack::getScriptId, scriptId));
        List<OVideo> videoList = List.of();
        if (!trackData.isEmpty()) {
            List<String> trackIds = trackData.stream().map(OVideoTrack::getId).collect(Collectors.toList());
            videoList = videoMapper.selectList(new LambdaQueryWrapper<OVideo>().in(OVideo::getVideoTrackId, trackIds));
        }
        final List<OVideo> finalVideoList = videoList;
        final int finalAudioRefCount = audioReferenceCount;

        List<Map<String, Object>> trackList = new ArrayList<>();
        for (OVideoTrack item : trackData) {
            String trackId = item.getId();
            List<Map<String, Object>> storyboardMedias = storyboardTrackRecord.getOrDefault(trackId, List.of());
            // Build unique asset medias for this track
            Set<String> seenAssetIds = new HashSet<>();
            List<Map<String, Object>> assetMedias = new ArrayList<>();
            for (Map<String, Object> sb : storyboardMedias) {
                Object sbId = sb.get("id");
                if (sbId != null) {
                    List<Map<String, Object>> sbAssets = otherDataMap.getOrDefault(sbId.toString(), List.of());
                    for (Map<String, Object> a : sbAssets) {
                        String aId = a.get("id") != null ? a.get("id").toString() : null;
                        if (aId != null && seenAssetIds.add(aId)) assetMedias.add(a);
                    }
                }
            }
            // Apply audioReference limit
            List<Map<String, Object>> filteredAssets = new ArrayList<>();
            int audioCount = 0;
            for (Map<String, Object> a : assetMedias) {
                if ("audio".equals(a.get("fileType")) && finalAudioRefCount > 0) {
                    if (audioCount >= finalAudioRefCount) continue;
                    audioCount++;
                }
                filteredAssets.add(a);
            }
            List<Map<String, Object>> hasImg = filteredAssets.stream().filter(a -> a.get("src") != null && !a.get("src").toString().isEmpty()).collect(Collectors.toList());
            List<Map<String, Object>> noImg = filteredAssets.stream().filter(a -> a.get("src") == null || a.get("src").toString().isEmpty()).collect(Collectors.toList());
            List<Map<String, Object>> medias = new ArrayList<>();
            medias.addAll(hasImg); medias.addAll(storyboardMedias); medias.addAll(noImg);

            List<Map<String, Object>> trackVideos = finalVideoList.stream()
                    .filter(v -> trackId.equals(v.getVideoTrackId()))
                    .map(v -> {
                        Map<String, Object> vm = new HashMap<>();
                        vm.put("id", v.getId());
                        vm.put("src", nvl(v.getFilePath()));
                        String st = v.getState();
                        vm.put("state", "已完成".equals(st) ? "已完成" : "生成中".equals(st) ? "生成中" : "生成失败".equals(st) ? "生成失败" : "未生成");
                        vm.put("errorReason", nvl(v.getErrorReason()));
                        return vm;
                    }).collect(Collectors.toList());

            Map<String, Object> t = new HashMap<>();
            t.put("id", trackId);
            t.put("duration", item.getDuration() != null ? item.getDuration() : 0);
            t.put("prompt", nvl(item.getPrompt()));
            t.put("state", nvl(item.getState()));
            t.put("reason", nvl(item.getReason()));
            t.put("selectVideoId", item.getSelectVideoId());
            t.put("medias", medias);
            t.put("videoList", trackVideos);
            trackList.add(t);
        }

        List<Map<String, Object>> storyboardResult = storyboardList.stream().map(s -> {
            Map<String, Object> m = new HashMap<>(); m.putAll(Map.of(
                    "id", s.getId(), "idx", s.getIndex() != null ? s.getIndex() : 0,
                    "src", nvl(s.getFilePath()), "filePath", nvl(s.getFilePath()),
                    "prompt", nvl(s.getPrompt()), "videoDesc", nvl(s.getVideoDesc()),
                    "state", nvl(s.getState()), "duration", nvl(s.getDuration()),
                    "trackId", nvl(s.getTrackId()), "reason", nvl(s.getReason())));
            return m;
        }).collect(Collectors.toList());

        Map<String, Object> result = new HashMap<>();
        result.put("storyboardList", storyboardResult);
        result.put("trackList", trackList);
        return R.ok(result);
    }

    @PostMapping("/workbench/checkVideoStateList")
    public R<List<Map<String, Object>>> checkVideoStateList(@RequestBody Map<String, Object> body) {
        @SuppressWarnings("unchecked")
        List<Object> rawIds = (List<Object>) body.get("videoIds");
        if (rawIds == null || rawIds.isEmpty()) return R.ok(List.of());
        List<String> videoIds = rawIds.stream().map(Object::toString).collect(Collectors.toList());
        List<OVideo> videos = videoMapper.selectList(
                new LambdaQueryWrapper<OVideo>()
                        .in(OVideo::getId, videoIds)
                        .in(OVideo::getState, List.of("生成成功", "生成失败"))
                        .select(OVideo::getId, OVideo::getState, OVideo::getErrorReason, OVideo::getFilePath));
        List<Map<String, Object>> result = videos.stream().map(v -> {
            Map<String, Object> m = new HashMap<>();
            m.put("id", v.getId());
            m.put("state", v.getState());
            m.put("errorReason", v.getErrorReason() != null ? v.getErrorReason() : "");
            m.put("src", v.getFilePath() != null ? v.getFilePath() : "");
            return m;
        }).collect(Collectors.toList());
        return R.ok(result);
    }

    @PostMapping("/workbench/generateVideo")
    public R<Map<String, Object>> generateVideo(@RequestBody Map<String, Object> body) {
        String projectId = body.get("projectId") != null ? body.get("projectId").toString() : null;
        String scriptId = body.get("scriptId") != null ? body.get("scriptId").toString() : null;
        String videoTrackId = body.get("videoTrackId") != null ? body.get("videoTrackId").toString() : null;
        String prompt = (String) body.getOrDefault("prompt", "");

        OVideo video = new OVideo();
        video.setProjectId(projectId);
        video.setScriptId(scriptId);
        video.setVideoTrackId(videoTrackId);
        video.setState("生成中");
        video.setTime(System.currentTimeMillis());
        videoMapper.insert(video);

        videoGenerationService.asyncGenerate(video.getId(), projectId, prompt);

        return R.ok(Map.of("videoId", video.getId(), "message", "已提交视频生成任务"));
    }

    @PostMapping("/workbench/batchGenerateVideo")
    public R<Map<String, String>> batchGenerateVideo(@RequestBody Map<String, Object> body) {
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> tasks = (List<Map<String, Object>>) body.get("tasks");
        String projectId = body.get("projectId") != null ? body.get("projectId").toString() : null;
        if (tasks != null) {
            for (Map<String, Object> t : tasks) {
                OVideo video = new OVideo();
                video.setProjectId(projectId);
                video.setScriptId(t.get("scriptId") != null ? t.get("scriptId").toString() : null);
                video.setVideoTrackId(t.get("videoTrackId") != null ? t.get("videoTrackId").toString() : null);
                video.setState("生成中");
                video.setTime(System.currentTimeMillis());
                videoMapper.insert(video);
                videoGenerationService.asyncGenerate(video.getId(), projectId,
                        (String) t.getOrDefault("prompt", ""));
            }
        }
        return R.ok(Map.of("message", "已提交批量视频生成任务"));
    }

    // ========== 图片编辑 ==========

    @PostMapping("/editImage/getImageFlow")
    public R<OImageFlow> getImageFlow(@RequestBody Map<String, Object> body) {
        String id = body.get("id") != null ? body.get("id").toString() : null;
        return R.ok(imageFlowMapper.selectById(id));
    }

    @PostMapping("/editImage/saveImageFlow")
    public R<Map<String, Object>> saveImageFlow(@RequestBody OImageFlow flow) {
        if (flow.getId() == null) {
            imageFlowMapper.insert(flow);
        } else {
            imageFlowMapper.updateById(flow);
        }
        return R.ok(Map.of("id", flow.getId()));
    }

    @PostMapping("/editImage/updateImageFlow")
    public R<Map<String, String>> updateImageFlow(@RequestBody OImageFlow flow) {
        imageFlowMapper.updateById(flow);
        return R.ok(Map.of("message", "更新成功"));
    }

    @PostMapping("/editImage/getImageDefaultModle")
    public R<Map<String, Object>> getImageDefaultModle(@RequestBody Map<String, Object> body) {
        String projectId = body.get("projectId") != null ? body.get("projectId").toString() : null;
        OProject project = projectMapper.selectById(projectId);
        Map<String, Object> result = new java.util.HashMap<>();
        if (project != null) {
            result.put("imageModel", project.getImageModel());
            result.put("imageQuality", project.getImageQuality());
        }
        return R.ok(result);
    }

    @PostMapping("/editImage/generateFlowImage")
    public R<Map<String, Object>> generateFlowImage(@RequestBody Map<String, Object> body) {
        String projectId = body.get("projectId") != null ? body.get("projectId").toString() : null;
        String prompt = (String) body.getOrDefault("prompt", "");
        String model = (String) body.get("model");
        String ratio = (String) body.getOrDefault("ratio", "1:1");
        String taskId = taskRecordService.start(projectId, "流程图片生成", model, prompt, null);
        try {
            String url = mediaGenerationService.generateImage(model, prompt, resolveSize(ratio));
            taskRecordService.done(taskId);
            return R.ok(Map.of("url", url));
        } catch (Exception e) {
            taskRecordService.fail(taskId, e.getMessage());
            throw new com.toonflow.common.exception.BusinessException("生成失败: " + e.getMessage());
        }
    }

    @PostMapping("/workbench/getFileUrl")
    public R<Map<String, Object>> getFileUrl(@RequestBody Map<String, Object> body) {
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) body.get("items");
        Map<String, String> result = new HashMap<>();
        if (items == null) return R.ok(Map.of("data", result));

        // Batch: collect storyboard and asset ids
        List<String> sbIds = items.stream().filter(i -> "storyboard".equals(i.get("sources")) && i.get("id") != null)
                .map(i -> i.get("id").toString()).collect(Collectors.toList());
        List<String> assetIds = items.stream().filter(i -> "assets".equals(i.get("sources")) && i.get("id") != null)
                .map(i -> i.get("id").toString()).collect(Collectors.toList());

        Map<String, String> sbFilePaths = new HashMap<>();
        if (!sbIds.isEmpty()) {
            storyboardMapper.selectList(new LambdaQueryWrapper<OStoryboard>().in(OStoryboard::getId, sbIds)
                    .select(OStoryboard::getId, OStoryboard::getFilePath))
                    .forEach(s -> sbFilePaths.put(s.getId(), s.getFilePath() != null ? s.getFilePath() : ""));
        }

        Map<String, String> assetFilePaths = new HashMap<>();
        if (!assetIds.isEmpty()) {
            List<com.toonflow.entity.OAssets> assets = assetsMapper.selectList(
                    new LambdaQueryWrapper<com.toonflow.entity.OAssets>().in(com.toonflow.entity.OAssets::getId, assetIds));
            List<String> imgIds = assets.stream().filter(a -> a.getImageId() != null)
                    .map(com.toonflow.entity.OAssets::getImageId).distinct().collect(Collectors.toList());
            Map<String, String> imgMap = imgIds.isEmpty() ? Map.of() :
                    imageMapper.selectList(new LambdaQueryWrapper<com.toonflow.entity.OImage>().in(com.toonflow.entity.OImage::getId, imgIds))
                            .stream().collect(Collectors.toMap(com.toonflow.entity.OImage::getId,
                                    i -> i.getFilePath() != null ? i.getFilePath() : ""));
            assets.forEach(a -> assetFilePaths.put(a.getId(),
                    a.getImageId() != null ? imgMap.getOrDefault(a.getImageId(), "") : ""));
        }

        for (Map<String, Object> item : items) {
            String id = item.get("id") != null ? item.get("id").toString() : null;
            String sources = item.get("sources") != null ? item.get("sources").toString() : "";
            if (id == null) continue;
            String key = id + ":" + sources;
            if ("storyboard".equals(sources)) {
                result.put(key, sbFilePaths.getOrDefault(id, ""));
            } else if ("assets".equals(sources)) {
                result.put(key, assetFilePaths.getOrDefault(id, ""));
            }
        }
        return R.ok(Map.of("data", result));
    }

    private String resolveSize(String ratio) {
        if (ratio == null) return "1024x1024";
        return switch (ratio) {
            case "16:9" -> "1280x720";
            case "9:16" -> "720x1280";
            case "4:3" -> "1024x768";
            case "1:1" -> "1024x1024";
            default -> "1024x1024";
        };
    }

    // ========== 制作侧素材 ==========

    @PostMapping("/assets/updateAssetsUrl")
    public R<Map<String, String>> updateAssetsUrl(@RequestBody Map<String, Object> body) {
        String id = body.get("id") != null ? body.get("id").toString() : null;
        String url = (String) body.get("url");
        String flowId = body.get("flowId") != null ? body.get("flowId").toString() : null;

        com.toonflow.entity.OImage image = new com.toonflow.entity.OImage();
        image.setFilePath(url);
        image.setState("已完成");
        image.setAssetsId(id);
        imageMapper.insert(image);

        com.toonflow.entity.OAssets asset = assetsMapper.selectById(id);
        if (asset != null) {
            asset.setFlowId(flowId);
            asset.setImageId(image.getId());
            assetsMapper.updateById(asset);
        }
        return R.ok(Map.of("message", "更新成功"));
    }

    @PostMapping("/assets/pollingImage")
    public R<List<Map<String, Object>>> pollingProductionAssets(@RequestBody Map<String, Object> body) {
        @SuppressWarnings("unchecked") List<Object> rawIds = (List<Object>) body.get("ids");
        if (rawIds == null || rawIds.isEmpty()) return R.ok(List.of());
        List<String> ids = rawIds.stream().map(Object::toString).collect(Collectors.toList());
        List<com.toonflow.entity.OAssets> assetsList = assetsMapper.selectList(
                new LambdaQueryWrapper<com.toonflow.entity.OAssets>().in(com.toonflow.entity.OAssets::getId, ids));
        List<String> imgIds = assetsList.stream().filter(a -> a.getImageId() != null)
                .map(com.toonflow.entity.OAssets::getImageId).distinct().collect(Collectors.toList());
        Map<String, com.toonflow.entity.OImage> imgMap = imgIds.isEmpty() ? Map.of() :
                imageMapper.selectList(new LambdaQueryWrapper<com.toonflow.entity.OImage>()
                        .in(com.toonflow.entity.OImage::getId, imgIds)
                        .ne(com.toonflow.entity.OImage::getState, "生成中"))
                        .stream().collect(Collectors.toMap(com.toonflow.entity.OImage::getId, i -> i));
        List<Map<String, Object>> result = new ArrayList<>();
        for (com.toonflow.entity.OAssets asset : assetsList) {
            if (asset.getImageId() == null) continue;
            com.toonflow.entity.OImage img = imgMap.get(asset.getImageId());
            if (img == null) continue; // still generating
            Map<String, Object> item = new HashMap<>();
            item.put("id", asset.getId());
            item.put("prompt", asset.getPrompt());
            item.put("state", img.getState());
            item.put("filePath", img.getFilePath() != null ? img.getFilePath() : "");
            item.put("src", img.getFilePath() != null ? img.getFilePath() : "");
            item.put("errorReason", img.getErrorReason() != null ? img.getErrorReason() : "");
            result.add(item);
        }
        return R.ok(result);
    }

    @PostMapping("/assets/deleteAssetsDireve")
    public R<Map<String, String>> deleteAssetsDireve(@RequestBody Map<String, Object> body) {
        String id = body.get("id") != null ? body.get("id").toString() : null;
        com.toonflow.entity.OAssets asset = assetsMapper.selectById(id);
        if (asset == null) throw new com.toonflow.common.exception.BusinessException("资源未找到");
        if (asset.getFlowId() != null) imageFlowMapper.deleteById(asset.getFlowId());
        assetsMapper.deleteById(id);
        assets2StoryboardMapper.delete(
                new LambdaQueryWrapper<com.toonflow.entity.OAssets2Storyboard>()
                        .eq(com.toonflow.entity.OAssets2Storyboard::getAssetId, id));
        return R.ok(Map.of("message", "删除成功"));
    }

    @PostMapping("/assets/batchGenerateAssetsImage")
    public R<Map<String, String>> batchGenerateAssetsImage(@RequestBody Map<String, Object> body) {
        @SuppressWarnings("unchecked")
        List<Object> rawIds = (List<Object>) body.get("assetIds");
        List<String> assetIds = rawIds != null ? rawIds.stream().map(Object::toString).collect(Collectors.toList()) : null;
        String projectId = body.get("projectId") != null ? body.get("projectId").toString() : null;
        if (assetIds == null || assetIds.isEmpty()) {
            throw new com.toonflow.common.exception.BusinessException("assetIds不能为空");
        }
        OProject project = projectMapper.selectById(projectId);
        String imageModel = project != null ? project.getImageModel() : null;
        String size = resolveSize(project != null ? project.getVideoRatio() : "16:9");

        List<com.toonflow.entity.OAssets> list = assetsMapper.selectList(
                new LambdaQueryWrapper<com.toonflow.entity.OAssets>().in(com.toonflow.entity.OAssets::getId, assetIds));
        for (com.toonflow.entity.OAssets asset : list) {
            asyncGenerateAssetImage(asset, imageModel, size, projectId);
        }
        return R.ok(Map.of("message", "已提交生成任务"));
    }

    @org.springframework.scheduling.annotation.Async
    public void asyncGenerateAssetImage(com.toonflow.entity.OAssets asset, String imageModel,
                                         String size, String projectId) {
        String taskId = taskRecordService.start(projectId, "素材图片生成", imageModel,
                "素材#" + asset.getId(), null);
        try {
            String url = mediaGenerationService.generateImage(imageModel, asset.getPrompt(), size);
            com.toonflow.entity.OImage image = new com.toonflow.entity.OImage();
            image.setAssetsId(asset.getId());
            image.setFilePath(url);
            image.setState("已完成");
            image.setModel(imageModel);
            imageMapper.insert(image);
            asset.setImageId(image.getId());
            assetsMapper.updateById(asset);
            taskRecordService.done(taskId);
        } catch (Exception e) {
            taskRecordService.fail(taskId, e.getMessage());
        }
    }

    // ========== 工作台：视频提示词 ==========

    @PostMapping("/workbench/generateVideoPrompt")
    public R<Map<String, String>> generateVideoPrompt(@RequestBody Map<String, Object> body) {
        String trackId = body.get("trackId") != null ? body.get("trackId").toString() : null;
        String desc = (String) body.getOrDefault("desc", "");
        try {
            String prompt = aiService.generateText("universalAi", List.of(
                    new com.toonflow.ai.AiService.ChatMessage("system",
                            "你是视频生成提示词专家。请根据画面描述生成一段适合视频生成模型的运镜与画面提示词，只输出提示词。"),
                    new com.toonflow.ai.AiService.ChatMessage("user", desc)));
            if (trackId != null) {
                OVideoTrack track = videoTrackMapper.selectById(trackId);
                if (track != null) {
                    track.setPrompt(prompt);
                    videoTrackMapper.updateById(track);
                }
            }
            return R.ok(Map.of("prompt", prompt));
        } catch (Exception e) {
            throw new com.toonflow.common.exception.BusinessException("生成提示词失败: " + e.getMessage());
        }
    }

    @PostMapping("/workbench/batchGeneratePrompt")
    public R<Map<String, String>> batchGeneratePrompt(@RequestBody Map<String, Object> body) {
        String projectId = body.get("projectId") != null ? body.get("projectId").toString() : null;
        String model = body.get("model") != null ? body.get("model").toString() : null;
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> trackDataList = (List<Map<String, Object>>) body.get("trackData");
        if (trackDataList == null || trackDataList.isEmpty()) return R.ok(Map.of("message", "批量生成提示词完成"));

        OProject project = projectMapper.selectById(projectId);
        String artStyle = project != null ? nvl(project.getArtStyle()) : "";

        // Load videoPromptGeneration system prompt from o_prompt
        com.toonflow.entity.OPrompt videoPrompt = promptMapper.selectOne(
                new LambdaQueryWrapper<com.toonflow.entity.OPrompt>()
                        .eq(com.toonflow.entity.OPrompt::getType, "videoPromptGeneration").last("LIMIT 1"));
        String systemPrompt = videoPrompt != null
                ? (videoPrompt.getUseData() != null ? videoPrompt.getUseData() : nvl(videoPrompt.getData()))
                : "你是视频生成提示词专家。请根据画面描述和资产信息生成适合视频生成模型的提示词，只输出提示词。";

        for (Map<String, Object> trackDataItem : trackDataList) {
            String trackId = trackDataItem.get("trackId") != null ? trackDataItem.get("trackId").toString() : null;
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> info = (List<Map<String, Object>>) trackDataItem.get("info");
            if (trackId == null || info == null) continue;

            // Build content from info items
            List<Map<String, Object>> assets = new ArrayList<>();
            List<Map<String, Object>> storyboards = new ArrayList<>();
            for (Map<String, Object> infoItem : info) {
                String id = infoItem.get("id") != null ? infoItem.get("id").toString() : null;
                String sources = infoItem.get("sources") != null ? infoItem.get("sources").toString() : "";
                if ("storyboard".equals(sources) && id != null) {
                    OStoryboard sb = storyboardMapper.selectById(id);
                    if (sb != null) {
                        List<com.toonflow.entity.OAssets2Storyboard> a2s = assets2StoryboardMapper.selectList(
                                new LambdaQueryWrapper<com.toonflow.entity.OAssets2Storyboard>()
                                        .eq(com.toonflow.entity.OAssets2Storyboard::getStoryboardId, id));
                        List<String> aIds = a2s.stream().map(com.toonflow.entity.OAssets2Storyboard::getAssetId).collect(Collectors.toList());
                        Map<String, Object> sbMap = new HashMap<>();
                        sbMap.put("videoDesc", sb.getVideoDesc());
                        sbMap.put("duration", sb.getDuration());
                        sbMap.put("associateAssetsIds", aIds);
                        storyboards.add(sbMap);
                    }
                } else if ("assets".equals(sources) && id != null) {
                    com.toonflow.entity.OAssets asset = assetsMapper.selectById(id);
                    if (asset != null) {
                        com.toonflow.entity.OImage img = asset.getImageId() != null ? imageMapper.selectById(asset.getImageId()) : null;
                        Map<String, Object> am = new HashMap<>();
                        am.put("id", asset.getId()); am.put("type", asset.getType()); am.put("name", asset.getName());
                        am.put("filePath", img != null ? img.getFilePath() : null);
                        assets.add(am);
                    }
                }
            }

            String modelData = model != null && model.contains(":") ? model.split(":", 2)[1] : model;
            String content = "**模型名称**：" + modelData + "，**资产信息**（角色、场景、道具、音频)：" +
                    assets.stream().filter(a -> a.get("filePath") != null)
                            .map(a -> "[" + a.get("id") + "," + a.get("type") + "," + a.get("name") + "]")
                            .collect(Collectors.joining("，")) +
                    "，**分镜信息**：" + storyboards.stream()
                            .map(s -> "<storyboardItem videoDesc='" + s.get("videoDesc") + "' duration='" + s.get("duration") + "'></storyboardItem>")
                            .collect(Collectors.joining());

            try {
                String prompt = aiService.generateText("universalAi", List.of(
                        new com.toonflow.ai.AiService.ChatMessage("system", systemPrompt),
                        new com.toonflow.ai.AiService.ChatMessage("user", content)));
                OVideoTrack track = videoTrackMapper.selectById(trackId);
                if (track != null) {
                    track.setPrompt(prompt);
                    videoTrackMapper.updateById(track);
                }
            } catch (Exception ignored) {}
        }
        return R.ok(Map.of("message", "批量生成提示词完成"));
    }

    @PostMapping("/workbench/getAudioBindAssetsList")
    public R<List<Map<String, Object>>> getAudioBindAssetsList(@RequestBody Map<String, Object> body) {
        @SuppressWarnings("unchecked") List<Object> rawIds = (List<Object>) body.get("assetsIds");
        if (rawIds == null || rawIds.isEmpty()) return R.ok(List.of());
        List<String> assetsIds = rawIds.stream().map(Object::toString).collect(Collectors.toList());

        List<com.toonflow.entity.OAssetsRole2Audio> binds = role2AudioMapper.selectList(
                new LambdaQueryWrapper<com.toonflow.entity.OAssetsRole2Audio>()
                        .in(com.toonflow.entity.OAssetsRole2Audio::getAssetsRoleId, assetsIds));
        if (binds.isEmpty()) return R.ok(List.of());

        // TS: o_assets where assetsId in (assetsAudioIds) joined with o_image
        List<String> audioAssetIds = binds.stream()
                .map(com.toonflow.entity.OAssetsRole2Audio::getAssetsAudioId).distinct().collect(Collectors.toList());
        List<com.toonflow.entity.OAssets> audios = assetsMapper.selectList(
                new LambdaQueryWrapper<com.toonflow.entity.OAssets>()
                        .in(com.toonflow.entity.OAssets::getAssetsId, audioAssetIds));
        List<String> imgIds = audios.stream().filter(a -> a.getImageId() != null)
                .map(com.toonflow.entity.OAssets::getImageId).distinct().collect(Collectors.toList());
        Map<String, String> imgPathMap = imgIds.isEmpty() ? Map.of() :
                imageMapper.selectList(new LambdaQueryWrapper<com.toonflow.entity.OImage>()
                        .in(com.toonflow.entity.OImage::getId, imgIds))
                        .stream().collect(Collectors.toMap(
                                com.toonflow.entity.OImage::getId,
                                i -> i.getFilePath() != null ? i.getFilePath() : ""));

        List<Map<String, Object>> result = audios.stream().map(a -> {
            Map<String, Object> m = new HashMap<>();
            m.put("id", a.getId());
            m.put("prompt", a.getPrompt());
            m.put("fileType", "audio");
            m.put("sources", "assets");
            m.put("src", a.getImageId() != null ? imgPathMap.getOrDefault(a.getImageId(), "") : "");
            return m;
        }).collect(Collectors.toList());
        return R.ok(result);
    }
}
