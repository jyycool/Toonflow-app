package com.toonflow.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.toonflow.common.result.R;
import com.toonflow.entity.OImageFlow;
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
    private final com.toonflow.ai.vendor.VideoGenerationService videoGenerationService;

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
}
