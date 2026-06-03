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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

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

    @org.springframework.beans.factory.annotation.Value("${toonflow.data-dir:${user.home}/.toonflow}")
    private String dataDir;

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

        for (OStoryboard sb : storyboards) {
            sb.setState("生成中");
            sb.setShouldGenerateImage(1);
            storyboardMapper.updateById(sb);
        }

        OProject project = projectMapper.selectById(req.getProjectId());
        String imageModel = project != null ? project.getImageModel() : null;
        String size = resolveSize(project != null ? project.getVideoRatio() : "16:9");

        asyncGenerateStoryboards(storyboards, imageModel, size, req.getProjectId());

        return R.ok(Map.of("message", "已提交生成任务"));
    }

    @Async
    public void asyncGenerateStoryboards(List<OStoryboard> storyboards, String imageModel,
                                          String size, String projectId) {
        for (OStoryboard sb : storyboards) {
            String taskId = taskRecordService.start(projectId, "分镜图片生成", imageModel,
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

    @PostMapping("/assetsGenerate/batchGenerateImageAssets")
    public R<Map<String, String>> batchGenerateImageAssets(@RequestBody BatchGenAssetsRequest req) {
        if (req.getAssetIds() == null || req.getAssetIds().isEmpty()) {
            throw new BusinessException("assetIds不能为空");
        }
        List<OAssets> assetsList = assetsMapper.selectList(
                new LambdaQueryWrapper<OAssets>().in(OAssets::getId, req.getAssetIds()));

        OProject project = projectMapper.selectById(req.getProjectId());
        String imageModel = project != null ? project.getImageModel() : null;
        String size = resolveSize(project != null ? project.getVideoRatio() : "16:9");

        asyncGenerateAssets(assetsList, imageModel, size, req.getProjectId());
        return R.ok(Map.of("message", "已提交生成任务"));
    }

    @Async
    public void asyncGenerateAssets(List<OAssets> assetsList, String imageModel,
                                     String size, String projectId) {
        for (OAssets asset : assetsList) {
            String taskId = taskRecordService.start(projectId, "素材图片生成", imageModel,
                    "素材#" + asset.getId(), Map.of("assetId", asset.getId()));
            try {
                String url = mediaGenerationService.generateImage(imageModel, asset.getPrompt(), size);
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

    @PostMapping("/assetsGenerate/generateAssets")
    public R<Map<String, Object>> generateAssets(@RequestBody GenAssetRequest req) {
        OProject project = projectMapper.selectById(req.getProjectId());
        String imageModel = project != null ? project.getImageModel() : null;
        String size = resolveSize(project != null ? project.getVideoRatio() : "16:9");
        String taskId = taskRecordService.start(req.getProjectId(), "图片生成", imageModel,
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

    @PostMapping("/assetsGenerate/cancelGenerate")
    public R<Map<String, String>> cancelGenerate(@RequestBody Map<String, Object> body) {
        String id = body.get("id") != null ? body.get("id").toString() : null;
        com.toonflow.entity.OImage image = imageMapper.selectById(id);
        if (image != null) {
            image.setState("生成失败");
            imageMapper.updateById(image);
        }
        return R.ok(Map.of("message", "取消成功"));
    }

    @PostMapping("/assetsGenerate/polishAssetsPrompt")
    public R<Map<String, Object>> polishAssetsPrompt(@RequestBody Map<String, Object> body) {
        String assetsId = body.get("assetsId") != null ? body.get("assetsId").toString() : null;
        String name = (String) body.getOrDefault("name", "");
        String describe = (String) body.getOrDefault("describe", "");
        try {
            String polished = aiService.generateText("universalAi", List.of(
                    new com.toonflow.ai.AiService.ChatMessage("system",
                            "你是一个图像提示词专家。请将用户提供的素材名称和描述润色为高质量的图像生成提示词，只输出提示词本身。"),
                    new com.toonflow.ai.AiService.ChatMessage("user", "名称：" + name + "\n描述：" + describe)));
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
    public R<Map<String, Object>> batchPolishAssetsPrompt(@RequestBody Map<String, Object> body) {
        String projectId = body.get("projectId") != null ? body.get("projectId").toString() : null;
        String otherTextPrompt = body.get("otherTextPrompt") != null ? body.get("otherTextPrompt").toString() : "";
        int concurrentCount = body.get("concurrentCount") instanceof Number n ? n.intValue() : 1;
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) body.get("items");
        if (items == null || items.isEmpty()) return R.fail("items 不能为空");

        OProject project = projectMapper.selectById(projectId);
        if (project == null) return R.fail("项目为空");

        List<String> assetIds = items.stream()
                .map(i -> i.get("assetsId") != null ? i.get("assetsId").toString() : null)
                .filter(id -> id != null).toList();

        // Validate all assets exist
        List<OAssets> assetsList = assetsMapper.selectList(
                new LambdaQueryWrapper<OAssets>().in(OAssets::getId, assetIds).select(OAssets::getId, OAssets::getAssetsId));
        if (assetsList.isEmpty()) return R.fail("资产不存在");
        Map<String, OAssets> assetsMap = assetsList.stream().collect(java.util.stream.Collectors.toMap(OAssets::getId, a -> a));

        // Mark all as 生成中
        for (String id : assetIds) {
            OAssets a = new OAssets(); a.setId(id); a.setPromptState("生成中");
            assetsMapper.updateById(a);
        }

        // Background async with concurrency control
        ExecutorService executor = Executors.newFixedThreadPool(Math.max(1, concurrentCount));
        for (Map<String, Object> item : items) {
            executor.submit(() -> {
                String assetsId = item.get("assetsId") != null ? item.get("assetsId").toString() : null;
                String type = item.get("type") != null ? item.get("type").toString() : null;
                String name = item.get("name") != null ? item.get("name").toString() : "";
                String describe = item.get("describe") != null ? item.get("describe").toString() : "";
                if (assetsId == null || type == null) return;

                OAssets assetData = assetsMap.get(assetsId);
                boolean isDerivative = assetData != null && assetData.getAssetsId() != null;
                String visualManualKey = getVisualManualKey(type, isDerivative);
                String nameLabel = getNameLabel(type);

                String visualManual = getArtPrompt(project.getArtStyle(), "art_skills", visualManualKey);
                if (visualManual.isBlank()) {
                    OAssets upd = new OAssets(); upd.setId(assetsId);
                    upd.setPromptState("生成失败"); upd.setPromptErrorReason("视觉手册未定义");
                    assetsMapper.updateById(upd);
                    return;
                }
                try {
                    String systemPrompt = visualManual + (otherTextPrompt.isBlank() ? "" : "\n" + otherTextPrompt);
                    String userContent = "**基础参数：**\n**" + nameLabel + "设定：**\n- " + nameLabel + "名称:" + name + ",\n- " + nameLabel + "描述:" + describe + ",";
                    String output = aiService.generateText("universalAi", List.of(
                            new com.toonflow.ai.AiService.ChatMessage("system", systemPrompt),
                            new com.toonflow.ai.AiService.ChatMessage("user", userContent)));
                    OAssets upd = new OAssets(); upd.setId(assetsId);
                    if (output == null || output.isBlank()) {
                        upd.setPromptState("生成失败");
                    } else {
                        upd.setPrompt(output); upd.setPromptState("已完成");
                    }
                    assetsMapper.updateById(upd);
                } catch (Exception e) {
                    OAssets upd = new OAssets(); upd.setId(assetsId);
                    upd.setPromptState("失败"); upd.setPromptErrorReason(e.getMessage());
                    assetsMapper.updateById(upd);
                }
            });
        }
        executor.shutdown();

        return R.ok(Map.of("total", items.size()));
    }

    private String getVisualManualKey(String type, boolean isDerivative) {
        return switch (type) {
            case "role" -> isDerivative ? "art_character_derivative" : "art_character";
            case "scene" -> isDerivative ? "art_scene_derivative" : "art_scene";
            case "tool" -> isDerivative ? "art_prop_derivative" : "art_prop";
            default -> "";
        };
    }

    private String getNameLabel(String type) {
        return switch (type) {
            case "role" -> "角色";
            case "scene" -> "场景";
            case "tool" -> "道具";
            default -> type;
        };
    }

    private String getArtPrompt(String styleName, String source, String fileName) {
        if (styleName == null || styleName.isBlank() || fileName.isBlank()) return "";
        Path baseDir = Paths.get(dataDir, "skills", source, styleName);
        if (!Files.exists(baseDir)) return "";
        String prefix = readFileRecursive(baseDir, "prefix.md");
        String target = fileName.endsWith(".md") ? fileName : fileName + ".md";
        String content = readFileRecursive(baseDir, target);
        if (content.isBlank()) return prefix;
        return prefix.isBlank() ? content : prefix + "\n" + content;
    }

    private String readFileRecursive(Path dir, String targetName) {
        try (var stream = Files.walk(dir)) {
            return stream.filter(p -> p.getFileName().toString().equals(targetName) && Files.isRegularFile(p))
                    .findFirst().map(p -> { try { return Files.readString(p); } catch (IOException e) { return ""; } })
                    .orElse("");
        } catch (IOException e) {
            return "";
        }
    }

    @Data
    public static class BatchGenImageRequest {
        @NotNull private List<String> storyboardIds;
        @NotNull private String projectId;
        @NotNull private String scriptId;
        private Integer concurrentCount = 5;
        private Boolean compulsory = false;
    }

    @Data
    public static class BatchGenAssetsRequest {
        @NotNull private List<String> assetIds;
        @NotNull private String projectId;
    }

    @Data
    public static class GenAssetRequest {
        @NotNull private String projectId;
        @NotNull private String prompt;
    }
}
