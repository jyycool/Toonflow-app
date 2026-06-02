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

import java.util.List;
import java.util.Map;

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
    private final com.toonflow.ai.vendor.VideoGenerationService videoGenerationService;
    private final com.toonflow.ai.vendor.MediaGenerationService mediaGenerationService;
    private final com.toonflow.ai.TaskRecordService taskRecordService;
    private final com.toonflow.ai.AiService aiService;

    // ========== Flow 数据 ==========

    @PostMapping("/getFlowData")
    public R<OImageFlow> getFlowData(@RequestBody Map<String, Object> body) {
        String id = body.get("id") != null ? body.get("id").toString() : null;
        return R.ok(imageFlowMapper.selectById(id));
    }

    @PostMapping("/saveFlowData")
    public R<Map<String, Object>> saveFlowData(@RequestBody OImageFlow flow) {
        if (flow.getId() == null) {
            imageFlowMapper.insert(flow);
        } else {
            imageFlowMapper.updateById(flow);
        }
        return R.ok(Map.of("id", flow.getId(), "message", "保存成功"));
    }

    @PostMapping("/getStoryboardData")
    public R<List<OStoryboard>> getStoryboardData(@RequestBody Map<String, Object> body) {
        String projectId = body.get("projectId") != null ? body.get("projectId").toString() : null;
        return R.ok(storyboardMapper.selectList(
                new LambdaQueryWrapper<OStoryboard>()
                        .eq(OStoryboard::getProjectId, projectId)
                        .orderByAsc(OStoryboard::getIndex)));
    }

    // ========== 视频工作台 ==========

    @PostMapping("/workbench/getVideoList")
    public R<List<OVideo>> getVideoList(@RequestBody Map<String, Object> body) {
        String projectId = body.get("projectId") != null ? body.get("projectId").toString() : null;
        return R.ok(videoMapper.selectList(
                new LambdaQueryWrapper<OVideo>().eq(OVideo::getProjectId, projectId)));
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
        List<OVideoTrack> tracks = videoTrackMapper.selectList(
                new LambdaQueryWrapper<OVideoTrack>().eq(OVideoTrack::getProjectId, projectId));
        List<OVideo> videos = videoMapper.selectList(
                new LambdaQueryWrapper<OVideo>().eq(OVideo::getProjectId, projectId));
        return R.ok(Map.of("tracks", tracks, "videos", videos));
    }

    @PostMapping("/workbench/checkVideoStateList")
    public R<List<OVideo>> checkVideoStateList(@RequestBody Map<String, Object> body) {
        @SuppressWarnings("unchecked")
        List<String> videoIds = (List<String>) body.get("videoIds");
        if (videoIds == null || videoIds.isEmpty()) return R.ok(List.of());
        return R.ok(videoMapper.selectList(
                new LambdaQueryWrapper<OVideo>()
                        .in(OVideo::getId, videoIds)
                        .in(OVideo::getState, List.of("生成成功", "生成失败"))));
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
    public R<List<Map<String, Object>>> getFileUrl(@RequestBody Map<String, Object> body) {
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) body.get("items");
        List<Map<String, Object>> result = new java.util.ArrayList<>();
        if (items == null) return R.ok(result);

        for (Map<String, Object> item : items) {
            String id = item.get("id") != null ? item.get("id").toString() : null;
            String sources = (String) item.get("sources");
            Map<String, Object> entry = new java.util.HashMap<>();
            entry.put("id", id);
            entry.put("sources", sources);
            if ("storyboard".equals(sources)) {
                OStoryboard sb = storyboardMapper.selectById(id);
                entry.put("filePath", sb != null ? sb.getFilePath() : null);
            } else if ("assets".equals(sources)) {
                com.toonflow.entity.OAssets asset = assetsMapper.selectById(id);
                if (asset != null && asset.getImageId() != null) {
                    com.toonflow.entity.OImage img = imageMapper.selectById(asset.getImageId());
                    entry.put("filePath", img != null ? img.getFilePath() : null);
                }
            }
            result.add(entry);
        }
        return R.ok(result);
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
        @SuppressWarnings("unchecked") List<String> ids = (List<String>) body.get("ids");
        if (ids == null || ids.isEmpty()) return R.ok(List.of());
        List<com.toonflow.entity.OAssets> assetsList = assetsMapper.selectList(
                new LambdaQueryWrapper<com.toonflow.entity.OAssets>().in(com.toonflow.entity.OAssets::getId, ids));
        List<Map<String, Object>> result = assetsList.stream().map(asset -> {
            Map<String, Object> item = new java.util.HashMap<>();
            item.put("id", asset.getId());
            item.put("prompt", asset.getPrompt());
            if (asset.getImageId() != null) {
                com.toonflow.entity.OImage img = imageMapper.selectById(asset.getImageId());
                if (img != null) {
                    item.put("state", img.getState());
                    item.put("filePath", img.getFilePath());
                    item.put("errorReason", img.getErrorReason());
                }
            }
            return item;
        }).filter(m -> !"生成中".equals(m.get("state"))).toList();
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
        List<String> assetIds = (List<String>) body.get("assetIds");
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
        @SuppressWarnings("unchecked")
        List<String> trackIds = (List<String>) body.get("trackIds");
        if (trackIds != null) {
            for (String trackId : trackIds) {
                OVideoTrack track = videoTrackMapper.selectById(trackId);
                if (track == null) continue;
                try {
                    String prompt = aiService.generateText("universalAi", List.of(
                            new com.toonflow.ai.AiService.ChatMessage("system",
                                    "你是视频提示词专家，根据描述生成视频提示词，只输出提示词。"),
                            new com.toonflow.ai.AiService.ChatMessage("user",
                                    track.getReason() != null ? track.getReason() : "")));
                    track.setPrompt(prompt);
                    videoTrackMapper.updateById(track);
                } catch (Exception ignored) {}
            }
        }
        return R.ok(Map.of("message", "批量生成提示词完成"));
    }

    @PostMapping("/workbench/getAudioBindAssetsList")
    public R<List<Map<String, Object>>> getAudioBindAssetsList(@RequestBody Map<String, Object> body) {
        @SuppressWarnings("unchecked") List<String> assetsIds = (List<String>) body.get("assetsIds");
        if (assetsIds == null || assetsIds.isEmpty()) return R.ok(List.of());

        List<com.toonflow.entity.OAssetsRole2Audio> binds = role2AudioMapper.selectList(
                new LambdaQueryWrapper<com.toonflow.entity.OAssetsRole2Audio>()
                        .in(com.toonflow.entity.OAssetsRole2Audio::getAssetsRoleId, assetsIds));
        List<Map<String, Object>> result = new java.util.ArrayList<>();
        for (com.toonflow.entity.OAssetsRole2Audio bind : binds) {
            List<com.toonflow.entity.OAssets> audios = assetsMapper.selectList(
                    new LambdaQueryWrapper<com.toonflow.entity.OAssets>()
                            .eq(com.toonflow.entity.OAssets::getAssetsId, bind.getAssetsAudioId()));
            for (com.toonflow.entity.OAssets a : audios) {
                Map<String, Object> m = new java.util.HashMap<>();
                m.put("id", a.getId());
                m.put("prompt", a.getPrompt());
                m.put("assetsId", a.getAssetsId());
                m.put("roleId", bind.getAssetsRoleId());
                result.add(m);
            }
        }
        return R.ok(result);
    }
}
