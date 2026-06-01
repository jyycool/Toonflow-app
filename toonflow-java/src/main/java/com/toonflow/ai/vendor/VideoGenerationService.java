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

    /**
     * 异步生成视频：委托适配器完成提交+轮询，再更新状态
     */
    @Async
    public void asyncGenerate(Integer videoId, Integer projectId, String prompt) {
        OProject project = projectMapper.selectById(projectId.longValue());
        String videoModel = project != null ? project.getVideoModel() : null;

        Integer taskId = taskRecordService.start(projectId, "视频生成", videoModel,
                "视频#" + videoId, null);

        try {
            // 适配器内部完成「提交任务 + 轮询 + 返回最终地址」并落盘
            String videoUrl = mediaGenerationService.generateVideo(videoModel, prompt, null, "16:9");

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
}
