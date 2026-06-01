package com.toonflow.ai.vendor;

import com.toonflow.ai.TaskRecordService;
import com.toonflow.entity.OProject;
import com.toonflow.entity.OVideo;
import com.toonflow.mapper.OProjectMapper;
import com.toonflow.mapper.OVideoMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

/**
 * 视频生成服务（异步 + 轮询闭环）
 * 对应原项目 production/workbench/generateVideo + checkVideoStateList
 *
 * 视频生成通常是异步任务：提交后轮询厂商任务状态，
 * 成功后下载视频并更新 o_video 记录状态。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VideoGenerationService {

    private final MediaGenerationService mediaGenerationService;
    private final TaskRecordService taskRecordService;
    private final OVideoMapper videoMapper;
    private final OProjectMapper projectMapper;

    private static final int MAX_POLL_ATTEMPTS = 60;
    private static final long POLL_INTERVAL_MS = 5000;

    /**
     * 异步生成视频：提交任务 -> 轮询 -> 更新状态
     */
    @Async
    public void asyncGenerate(Integer videoId, Integer projectId, String prompt) {
        OProject project = projectMapper.selectById(projectId.longValue());
        String videoModel = project != null ? project.getVideoModel() : null;

        Integer taskId = taskRecordService.start(projectId, "视频生成", videoModel,
                "视频#" + videoId, null);

        try {
            // 提交视频生成任务
            String externalTaskId = mediaGenerationService.submitVideoTask(videoModel, prompt, null);
            if (externalTaskId == null) {
                throw new RuntimeException("视频任务提交未返回任务 ID");
            }

            // 轮询任务状态（此处为骨架，实际需调用厂商查询接口）
            String videoUrl = pollVideoTask(videoModel, externalTaskId);

            OVideo video = videoMapper.selectById(videoId);
            if (video != null) {
                video.setFilePath(videoUrl);
                video.setState("生成成功");
                videoMapper.updateById(video);
            }
            taskRecordService.done(taskId);
        } catch (Exception e) {
            log.error("视频生成失败 videoId={}", videoId, e);
            OVideo video = videoMapper.selectById(videoId);
            if (video != null) {
                video.setState("生成失败");
                video.setErrorReason(e.getMessage());
                videoMapper.updateById(video);
            }
            taskRecordService.fail(taskId, e.getMessage());
        }
    }

    /**
     * 轮询视频任务状态
     * 注：具体查询接口因厂商而异，此处提供轮询框架，
     * 实际部署时需在 MediaGenerationService 中实现 queryVideoTask。
     */
    private String pollVideoTask(String videoModel, String externalTaskId)
            throws InterruptedException {
        for (int attempt = 0; attempt < MAX_POLL_ATTEMPTS; attempt++) {
            Thread.sleep(POLL_INTERVAL_MS);
            // String status = mediaGenerationService.queryVideoTask(videoModel, externalTaskId);
            // if ("succeeded".equals(status)) return videoUrl;
            // if ("failed".equals(status)) throw new RuntimeException("厂商返回失败");
            log.debug("轮询视频任务 {} 第 {} 次", externalTaskId, attempt + 1);
        }
        throw new RuntimeException("视频生成超时");
    }
}
