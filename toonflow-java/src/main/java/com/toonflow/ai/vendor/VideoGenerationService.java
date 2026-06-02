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

@Slf4j
@Service
@RequiredArgsConstructor
public class VideoGenerationService {

    private final MediaGenerationService mediaGenerationService;
    private final TaskRecordService taskRecordService;
    private final OVideoMapper videoMapper;
    private final OProjectMapper projectMapper;

    @Async
    public void asyncGenerate(String videoId, String projectId, String prompt) {
        OProject project = projectMapper.selectById(projectId);
        String videoModel = project != null ? project.getVideoModel() : null;

        String taskId = taskRecordService.start(projectId, "视频生成", videoModel,
                "视频#" + videoId, null);

        try {
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
