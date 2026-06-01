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
    private final com.toonflow.ai.vendor.VideoGenerationService videoGenerationService;
    private final com.toonflow.ai.vendor.MediaGenerationService mediaGenerationService;
    private final com.toonflow.ai.TaskRecordService taskRecordService;

    // ========== Flow 数据 ==========

    @GetMapping("/getFlowData")
    public R<OImageFlow> getFlowData(@RequestParam Integer id) {
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

    @GetMapping("/getStoryboardData")
    public R<List<OStoryboard>> getStoryboardData(@RequestParam Integer projectId) {
        return R.ok(storyboardMapper.selectList(
                new LambdaQueryWrapper<OStoryboard>()
                        .eq(OStoryboard::getProjectId, projectId)
                        .orderByAsc(OStoryboard::getIndex)));
    }

    // ========== 视频工作台 ==========

    @GetMapping("/workbench/getVideoList")
    public R<List<OVideo>> getVideoList(@RequestParam Integer projectId) {
        return R.ok(videoMapper.selectList(
                new LambdaQueryWrapper<OVideo>().eq(OVideo::getProjectId, projectId)));
    }

    @PostMapping("/workbench/addTrack")
    public R<Map<String, String>> addTrack(@RequestBody OVideoTrack track) {
        videoTrackMapper.insert(track);
        return R.ok(Map.of("message", "添加轨道成功"));
    }

    @PostMapping("/workbench/deleteTrack")
    public R<Map<String, String>> deleteTrack(@RequestBody Map<String, Integer> body) {
        videoTrackMapper.deleteById(body.get("id"));
        return R.ok(Map.of("message", "删除轨道成功"));
    }

    @PostMapping("/workbench/selectVideo")
    public R<Map<String, String>> selectVideo(@RequestBody Map<String, Integer> body) {
        OVideoTrack track = videoTrackMapper.selectById(body.get("trackId"));
        if (track != null) {
            track.setSelectVideoId(body.get("videoId"));
            videoTrackMapper.updateById(track);
        }
        return R.ok(Map.of("message", "选择视频成功"));
    }

    @PostMapping("/workbench/delVideo")
    public R<Map<String, String>> delVideo(@RequestBody Map<String, Integer> body) {
        videoMapper.deleteById(body.get("id"));
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

    @GetMapping("/workbench/getGenerateData")
    public R<Map<String, Object>> getGenerateData(@RequestParam Integer projectId) {
        List<OVideoTrack> tracks = videoTrackMapper.selectList(
                new LambdaQueryWrapper<OVideoTrack>().eq(OVideoTrack::getProjectId, projectId));
        List<OVideo> videos = videoMapper.selectList(
                new LambdaQueryWrapper<OVideo>().eq(OVideo::getProjectId, projectId));
        return R.ok(Map.of("tracks", tracks, "videos", videos));
    }

    @PostMapping("/workbench/checkVideoStateList")
    public R<List<OVideo>> checkVideoStateList(@RequestBody Map<String, Object> body) {
        @SuppressWarnings("unchecked")
        List<Integer> videoIds = (List<Integer>) body.get("videoIds");
        if (videoIds == null || videoIds.isEmpty()) return R.ok(List.of());
        return R.ok(videoMapper.selectList(
                new LambdaQueryWrapper<OVideo>()
                        .in(OVideo::getId, videoIds)
                        .in(OVideo::getState, List.of("生成成功", "生成失败"))));
    }

    /**
     * 生成视频（异步任务，立即返回，后台执行并更新状态）
     */
    @PostMapping("/workbench/generateVideo")
    public R<Map<String, Object>> generateVideo(@RequestBody Map<String, Object> body) {
        Integer projectId = (Integer) body.get("projectId");
        Integer scriptId = (Integer) body.get("scriptId");
        Integer videoTrackId = (Integer) body.get("videoTrackId");
        String prompt = (String) body.getOrDefault("prompt", "");

        // 创建视频记录，状态为生成中
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
        Integer projectId = (Integer) body.get("projectId");
        if (tasks != null) {
            for (Map<String, Object> t : tasks) {
                OVideo video = new OVideo();
                video.setProjectId(projectId);
                video.setScriptId((Integer) t.get("scriptId"));
                video.setVideoTrackId((Integer) t.get("videoTrackId"));
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

    @GetMapping("/editImage/getImageFlow")
    public R<OImageFlow> getImageFlow(@RequestParam Integer id) {
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

    /**
     * 获取项目默认图片模型与质量
     */
    @PostMapping("/editImage/getImageDefaultModle")
    public R<Map<String, Object>> getImageDefaultModle(@RequestBody Map<String, Long> body) {
        OProject project = projectMapper.selectById(body.get("projectId"));
        Map<String, Object> result = new java.util.HashMap<>();
        if (project != null) {
            result.put("imageModel", project.getImageModel());
            result.put("imageQuality", project.getImageQuality());
        }
        return R.ok(result);
    }

    /**
     * 流程图片生成（异步）
     */
    @PostMapping("/editImage/generateFlowImage")
    public R<Map<String, Object>> generateFlowImage(@RequestBody Map<String, Object> body) {
        Integer projectId = (Integer) body.get("projectId");
        String prompt = (String) body.getOrDefault("prompt", "");
        String model = (String) body.get("model");
        String ratio = (String) body.getOrDefault("ratio", "1:1");
        Integer taskId = taskRecordService.start(projectId, "流程图片生成", model, prompt, null);
        try {
            String url = mediaGenerationService.generateImage(model, prompt, resolveSize(ratio));
            taskRecordService.done(taskId);
            return R.ok(Map.of("url", url));
        } catch (Exception e) {
            taskRecordService.fail(taskId, e.getMessage());
            throw new com.toonflow.common.exception.BusinessException("生成失败: " + e.getMessage());
        }
    }

    /**
     * 获取分镜/素材的文件地址
     */
    @PostMapping("/workbench/getFileUrl")
    public R<List<Map<String, Object>>> getFileUrl(@RequestBody Map<String, Object> body) {
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) body.get("items");
        List<Map<String, Object>> result = new java.util.ArrayList<>();
        if (items == null) return R.ok(result);

        for (Map<String, Object> item : items) {
            Integer id = (Integer) item.get("id");
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
}
