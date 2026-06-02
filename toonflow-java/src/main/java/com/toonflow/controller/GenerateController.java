package com.toonflow.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.toonflow.ai.TaskRecordService;
import com.toonflow.ai.vendor.MediaGenerationService;
import com.toonflow.common.exception.BusinessException;
import com.toonflow.common.result.R;
import com.toonflow.entity.OAssets;
import com.toonflow.entity.OImage;
import com.toonflow.entity.OProject;
import com.toonflow.entity.OStoryboard;
import com.toonflow.mapper.OAssetsMapper;
import com.toonflow.mapper.OImageMapper;
import com.toonflow.mapper.OProjectMapper;
import com.toonflow.mapper.OStoryboardMapper;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 图片/视频生成控制器
 * 对应原项目 assetsGenerate/* 和 production/storyboard/batchGenerateImage 等
 *
 * 生成为异步任务：立即返回，后台执行并更新数据库状态，前端轮询查询。
 */
@Slf4j
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class GenerateController {

    private final MediaGenerationService mediaGenerationService;
    private final TaskRecordService taskRecordService;
    private final com.toonflow.ai.AiService aiService;
    private final OStoryboardMapper storyboardMapper;
    private final OAssetsMapper assetsMapper;
    private final OImageMapper imageMapper;
    private final OProjectMapper projectMapper;

    /**
     * 批量生成分镜图片
     */
    @PostMapping("/production/storyboard/batchGenerateImage")
    public R<Map<String, String>> batchGenerateImage(@RequestBody BatchGenImageRequest req) {
        if (req.getStoryboardIds() == null || req.getStoryboardIds().isEmpty()) {
            throw new BusinessException("storyboardIds不能为空");
        }
        List<OStoryboard> storyboards = storyboardMapper.selectList(
                new LambdaQueryWrapper<OStoryboard>()
                        .eq(OStoryboard::getProjectId, req.getProjectId())
                        .eq(OStoryboard::getScriptId, req.getScriptId())
                        .in(OStoryboard::getId, req.getStoryboardIds()));
        if (storyboards.isEmpty()) throw new BusinessException("未查到分镜数据");

        // 标记为生成中
        for (OStoryboard sb : storyboards) {
            sb.setState("生成中");
            sb.setShouldGenerateImage(1);
            storyboardMapper.updateById(sb);
        }

        OProject project = projectMapper.selectById(req.getProjectId().longValue());
        String imageModel = project != null ? project.getImageModel() : null;
        String size = resolveSize(project != null ? project.getVideoRatio() : "16:9");

        // 异步生成
        asyncGenerateStoryboards(storyboards, imageModel, size, req.getProjectId());

        return R.ok(Map.of("message", "已提交生成任务"));
    }

    @Async
    public void asyncGenerateStoryboards(List<OStoryboard> storyboards, String imageModel,
                                          String size, Integer projectId) {
        for (OStoryboard sb : storyboards) {
            Integer taskId = taskRecordService.start(projectId, "分镜图片生成", imageModel,
                    "分镜#" + sb.getId(), Map.of("storyboardId", sb.getId()));
            try {
                String url = mediaGenerationService.generateImage(imageModel, sb.getPrompt(), size);
                sb.setFilePath(url);
                sb.setState("已生成");
                storyboardMapper.updateById(sb);
                taskRecordService.done(taskId);
            } catch (Exception e) {
                sb.setState("生成失败");
                sb.setReason(e.getMessage());
                storyboardMapper.updateById(sb);
                taskRecordService.fail(taskId, e.getMessage());
            }
        }
    }

    /**
     * 批量生成素材图片
     */
    @PostMapping("/assetsGenerate/batchGenerateImageAssets")
    public R<Map<String, String>> batchGenerateImageAssets(@RequestBody BatchGenAssetsRequest req) {
        if (req.getAssetIds() == null || req.getAssetIds().isEmpty()) {
            throw new BusinessException("assetIds不能为空");
        }
        List<OAssets> assetsList = assetsMapper.selectList(
                new LambdaQueryWrapper<OAssets>().in(OAssets::getId, req.getAssetIds()));

        OProject project = projectMapper.selectById(req.getProjectId().longValue());
        String imageModel = project != null ? project.getImageModel() : null;
        String size = resolveSize(project != null ? project.getVideoRatio() : "16:9");

        asyncGenerateAssets(assetsList, imageModel, size, req.getProjectId());
        return R.ok(Map.of("message", "已提交生成任务"));
    }

    @Async
    public void asyncGenerateAssets(List<OAssets> assetsList, String imageModel,
                                     String size, Integer projectId) {
        for (OAssets asset : assetsList) {
            Integer taskId = taskRecordService.start(projectId, "素材图片生成", imageModel,
                    "素材#" + asset.getId(), Map.of("assetId", asset.getId()));
            try {
                String url = mediaGenerationService.generateImage(imageModel, asset.getPrompt(), size);
                // 创建图片记录
                OImage image = new OImage();
                image.setAssetsId(asset.getId());
                image.setFilePath(url);
                image.setState("已生成");
                image.setModel(imageModel);
                image.setType("asset");
                imageMapper.insert(image);

                asset.setImageId(image.getId());
                assetsMapper.updateById(asset);
                taskRecordService.done(taskId);
            } catch (Exception e) {
                taskRecordService.fail(taskId, e.getMessage());
            }
        }
    }

    /**
     * 单张图片生成
     */
    @PostMapping("/assetsGenerate/generateAssets")
    public R<Map<String, Object>> generateAssets(@RequestBody GenAssetRequest req) {
        OProject project = projectMapper.selectById(req.getProjectId().longValue());
        String imageModel = project != null ? project.getImageModel() : null;
        String size = resolveSize(project != null ? project.getVideoRatio() : "16:9");
        Integer taskId = taskRecordService.start(req.getProjectId(), "图片生成", imageModel,
                req.getPrompt(), null);
        try {
            String url = mediaGenerationService.generateImage(imageModel, req.getPrompt(), size);
            taskRecordService.done(taskId);
            return R.ok(Map.of("url", url));
        } catch (Exception e) {
            taskRecordService.fail(taskId, e.getMessage());
            throw new BusinessException("生成失败: " + e.getMessage());
        }
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

    /**
     * 取消生成（将图片标记为生成失败）
     */
    @PostMapping("/assetsGenerate/cancelGenerate")
    public R<Map<String, String>> cancelGenerate(@RequestBody Map<String, Integer> body) {
        Integer id = body.get("id");
        com.toonflow.entity.OImage image = imageMapper.selectById(id);
        if (image != null) {
            image.setState("生成失败");
            imageMapper.updateById(image);
        }
        return R.ok(Map.of("message", "取消成功"));
    }

    /**
     * 润色素材提示词（调用 AI 优化）
     */
    @PostMapping("/assetsGenerate/polishAssetsPrompt")
    public R<Map<String, Object>> polishAssetsPrompt(@RequestBody Map<String, Object> body) {
        Integer assetsId = body.get("assetsId") != null ? ((Number) body.get("assetsId")).intValue() : null;
        String name = (String) body.getOrDefault("name", "");
        String describe = (String) body.getOrDefault("describe", "");
        try {
            String polished = aiService.generateText("universalAi", List.of(
                    new com.toonflow.ai.AiService.ChatMessage("system",
                            "你是一个图像提示词专家。请将用户提供的素材名称和描述润色为高质量的图像生成提示词，只输出提示词本身。"),
                    new com.toonflow.ai.AiService.ChatMessage("user", "名称：" + name + "\n描述：" + describe)));
            // 回写到素材
            com.toonflow.entity.OAssets asset = assetsMapper.selectById(assetsId);
            if (asset != null) {
                asset.setPrompt(polished);
                assetsMapper.updateById(asset);
            }
            return R.ok(Map.of("prompt", polished));
        } catch (Exception e) {
            throw new BusinessException("润色失败: " + e.getMessage());
        }
    }

    @PostMapping("/assetsGenerate/batchPolishAssetsPrompt")
    public R<Map<String, String>> batchPolishAssetsPrompt(@RequestBody Map<String, Object> body) {
        @SuppressWarnings("unchecked")
        List<Number> raw_assetIds = (List<Number>) body.get("assetIds");
        List<Integer> assetIds = raw_assetIds != null ? raw_assetIds.stream().map(Number::intValue).collect(java.util.stream.Collectors.toList()) : null;
        if (assetIds != null) {
            for (Integer id : assetIds) {
                com.toonflow.entity.OAssets asset = assetsMapper.selectById(id);
                if (asset == null) continue;
                try {
                    String polished = aiService.generateText("universalAi", List.of(
                            new com.toonflow.ai.AiService.ChatMessage("system",
                                    "你是图像提示词专家，请将素材描述润色为高质量图像提示词，只输出提示词。"),
                            new com.toonflow.ai.AiService.ChatMessage("user",
                                    "名称：" + asset.getName() + "\n描述：" + asset.getDescribe())));
                    asset.setPrompt(polished);
                    assetsMapper.updateById(asset);
                } catch (Exception ignored) {}
            }
        }
        return R.ok(Map.of("message", "批量润色完成"));
    }

    @Data
    public static class BatchGenImageRequest {
        @NotNull private List<Integer> storyboardIds;
        @NotNull private Integer projectId;
        @NotNull private Integer scriptId;
        private Integer concurrentCount = 5;
        private Boolean compulsory = false;
    }

    @Data
    public static class BatchGenAssetsRequest {
        @NotNull private List<Integer> assetIds;
        @NotNull private Integer projectId;
    }

    @Data
    public static class GenAssetRequest {
        @NotNull private Integer projectId;
        @NotNull private String prompt;
    }
}
