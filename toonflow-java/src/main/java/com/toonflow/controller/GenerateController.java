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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.stream.Collectors;
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
    private final com.toonflow.mapper.OAssets2StoryboardMapper assets2StoryboardMapper;

    @org.springframework.beans.factory.annotation.Value("${toonflow.data-dir:${user.home}/.toonflow}")
    private String dataDir;

    @PostMapping("/production/storyboard/batchGenerateImage")
    public R<List<Map<String, Object>>> batchGenerateImage(@RequestBody BatchGenImageRequest req) {
        if (req.getStoryboardIds() == null || req.getStoryboardIds().isEmpty()) {
            throw new BusinessException("storyboardIds不能为空");
        }
        List<OStoryboard> storyboards = storyboardMapper.selectList(
                new LambdaQueryWrapper<OStoryboard>()
                        .eq(OStoryboard::getProjectId, req.getProjectId())
                        .eq(OStoryboard::getScriptId, req.getScriptId())
                        .in(OStoryboard::getId, req.getStoryboardIds()));
        if (storyboards.isEmpty()) throw new BusinessException("未查到分镜数据");

        boolean compulsory = Boolean.TRUE.equals(req.getCompulsory());
        if (compulsory) {
            for (OStoryboard sb : storyboards) {
                sb.setState("生成中"); sb.setShouldGenerateImage(1);
                storyboardMapper.updateById(sb);
            }
        } else {
            for (OStoryboard sb : storyboards) {
                if (sb.getShouldGenerateImage() != null && sb.getShouldGenerateImage() == 0) {
                    sb.setState("未生成");
                } else {
                    sb.setState("生成中");
                }
                storyboardMapper.updateById(sb);
            }
        }

        // Load associateAssetsIds per storyboard (from o_assets2Storyboard)
        List<String> sbIds = storyboards.stream().map(OStoryboard::getId).collect(Collectors.toList());
        List<com.toonflow.entity.OAssets2Storyboard> a2sList = assets2StoryboardMapper.selectList(
                new LambdaQueryWrapper<com.toonflow.entity.OAssets2Storyboard>()
                        .in(com.toonflow.entity.OAssets2Storyboard::getStoryboardId, sbIds));
        Map<String, List<String>> assetRecord = new HashMap<>();
        for (com.toonflow.entity.OAssets2Storyboard r : a2sList) {
            assetRecord.computeIfAbsent(r.getStoryboardId(), k -> new ArrayList<>()).add(r.getAssetId());
        }

        List<Map<String, Object>> response = storyboards.stream().map(sb -> {
            Map<String, Object> m = new HashMap<>();
            m.put("id", sb.getId());
            m.put("prompt", sb.getPrompt());
            m.put("associateAssetsIds", assetRecord.getOrDefault(sb.getId(), List.of()));
            m.put("src", null);
            m.put("state", sb.getState());
            m.put("videoDesc", sb.getVideoDesc());
            m.put("shouldGenerateImage", sb.getShouldGenerateImage());
            return m;
        }).collect(Collectors.toList());

        OProject project = projectMapper.selectById(req.getProjectId());
        String imageModel = project != null ? project.getImageModel() : null;
        String size = resolveSize(project != null ? project.getVideoRatio() : "16:9");
        List<OStoryboard> toGenerate = compulsory ? storyboards :
                storyboards.stream().filter(sb -> sb.getShouldGenerateImage() == null || sb.getShouldGenerateImage() != 0).collect(Collectors.toList());
        asyncGenerateStoryboards(toGenerate, imageModel, size, req.getProjectId());

        return R.ok(response);
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
    public R<Map<String, Object>> batchGenerateImageAssets(@RequestBody Map<String, Object> body) {
        String projectId = body.get("projectId") != null ? body.get("projectId").toString() : null;
        String model = body.get("model") != null ? body.get("model").toString() : null;
        String resolution = body.get("resolution") != null ? body.get("resolution").toString() : null;
        int concurrentCount = body.get("concurrentCount") instanceof Number n ? n.intValue() : 1;

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) body.get("items");
        if (items == null || items.isEmpty()) throw new BusinessException("items不能为空");

        OProject project = projectMapper.selectById(projectId);
        if (project == null) throw new BusinessException("项目为空");

        // Determine model/size
        String imageModel = model != null ? model : (project.getImageModel() != null ? project.getImageModel() : null);
        String size = resolution != null ? resolution : resolveSize(project.getVideoRatio());

        // Pre-create o_image placeholder for each item and collect imageIds
        List<String> imageIds = new java.util.ArrayList<>();
        for (Map<String, Object> item : items) {
            String assetId = item.get("id") != null ? item.get("id").toString() : null;
            String itemType = item.get("type") != null ? item.get("type").toString() : "role";
            OImage placeholder = new OImage();
            placeholder.setType(itemType);
            placeholder.setState("生成中");
            placeholder.setAssetsId(assetId);
            imageMapper.insert(placeholder);
            if (assetId != null) {
                OAssets upd = new OAssets(); upd.setId(assetId); upd.setImageId(placeholder.getId());
                assetsMapper.updateById(upd);
            }
            imageIds.add(placeholder.getId());
        }

        final String finalModel = imageModel;
        final String finalSize = size;

        // Async concurrent generation
        ExecutorService executor = Executors.newFixedThreadPool(Math.max(1, concurrentCount));
        for (int i = 0; i < items.size(); i++) {
            final int idx = i;
            final Map<String, Object> item = items.get(i);
            final String imageId = imageIds.get(i);
            executor.submit(() -> {
                String assetId = item.get("id") != null ? item.get("id").toString() : null;
                String itemType = item.get("type") != null ? item.get("type").toString() : "role";
                String name = item.get("name") != null ? item.get("name").toString() : "";
                String prompt = item.get("prompt") != null ? item.get("prompt").toString() : "";
                String base64 = (String) item.get("base64");

                // Check if cancelled
                OImage current = imageMapper.selectById(imageId);
                if (current != null && "生成失败".equals(current.getState())) return;

                String taskId = taskRecordService.start(projectId, "素材图片生成", finalModel, name, null);
                try {
                    String userPrompt = buildAssetPrompt(itemType, project.getArtStyle(), name, prompt);
                    String imagePath = "/" + projectId + "/" + getTypeDir(itemType) + "/" + UUID.randomUUID() + ".jpg";
                    String url = mediaGenerationService.generateImage(finalModel, userPrompt, finalSize);

                    OImage upd = new OImage(); upd.setId(imageId);
                    upd.setState("已完成"); upd.setFilePath(url);
                    upd.setType(itemType); upd.setModel(parseModelName(finalModel));
                    upd.setResolution(finalSize);
                    imageMapper.updateById(upd);
                    if (assetId != null) {
                        OAssets aUpd = new OAssets(); aUpd.setId(assetId); aUpd.setImageId(imageId);
                        assetsMapper.updateById(aUpd);
                    }
                    taskRecordService.done(taskId);
                } catch (Exception e) {
                    OImage upd = new OImage(); upd.setId(imageId);
                    upd.setState("生成失败"); upd.setErrorReason(e.getMessage());
                    imageMapper.updateById(upd);
                    taskRecordService.fail(taskId, e.getMessage());
                }
            });
        }
        executor.shutdown();
        return R.ok(Map.of("total", items.size()));
    }

    @PostMapping("/assetsGenerate/generateAssets")
    public R<Map<String, Object>> generateAssets(@RequestBody Map<String, Object> body) {
        String projectId = body.get("projectId") != null ? body.get("projectId").toString() : null;
        String model = body.get("model") != null ? body.get("model").toString() : null;
        String resolution = body.get("resolution") != null ? body.get("resolution").toString() : null;
        String assetId = body.get("id") != null ? body.get("id").toString() : null;
        String type = body.get("type") != null ? body.get("type").toString() : "role";
        String name = body.get("name") != null ? body.get("name").toString() : "";
        String prompt = body.get("prompt") != null ? body.get("prompt").toString() : "";

        OProject project = projectMapper.selectById(projectId);
        if (project == null) throw new BusinessException("项目为空");

        String imageModel = model != null ? model : project.getImageModel();
        String size = resolution != null ? resolution : resolveSize(project.getVideoRatio());

        // Create o_image placeholder
        OImage placeholder = new OImage();
        placeholder.setType(type);
        placeholder.setState("生成中");
        placeholder.setAssetsId(assetId);
        placeholder.setModel(parseModelName(imageModel));
        placeholder.setResolution(size);
        imageMapper.insert(placeholder);
        if (assetId != null) {
            OAssets upd = new OAssets(); upd.setId(assetId); upd.setImageId(placeholder.getId());
            assetsMapper.updateById(upd);
        }

        String taskId = taskRecordService.start(projectId, "图片生成", imageModel, name, null);
        try {
            String userPrompt = buildAssetPrompt(type, project.getArtStyle(), name, prompt);
            String url = mediaGenerationService.generateImage(imageModel, userPrompt, size);

            // Check if cancelled
            OImage current = imageMapper.selectById(placeholder.getId());
            if (current != null && "生成失败".equals(current.getState())) {
                taskRecordService.done(taskId);
                return R.ok(Map.of("path", "", "assetsId", assetId != null ? assetId : ""));
            }

            OImage upd = new OImage(); upd.setId(placeholder.getId());
            upd.setState("已完成"); upd.setFilePath(url);
            upd.setType(type); upd.setModel(parseModelName(imageModel)); upd.setResolution(size);
            imageMapper.updateById(upd);
            if (assetId != null) {
                OAssets aUpd = new OAssets(); aUpd.setId(assetId); aUpd.setImageId(placeholder.getId());
                assetsMapper.updateById(aUpd);
            }
            taskRecordService.done(taskId);
            return R.ok(Map.of("path", url, "assetsId", assetId != null ? assetId : ""));
        } catch (Exception e) {
            OImage upd = new OImage(); upd.setId(placeholder.getId());
            upd.setState("生成失败"); upd.setErrorReason(e.getMessage());
            imageMapper.updateById(upd);
            taskRecordService.fail(taskId, e.getMessage());
            throw new BusinessException(e.getMessage() != null ? e.getMessage() : "图片生成失败");
        }
    }

    private String buildAssetPrompt(String type, String artStyle, String name, String prompt) {
        String label = switch (type) {
            case "role" -> "角色"; case "scene" -> "场景"; case "tool" -> "道具"; default -> type;
        };
        String promptTitle = switch (type) {
            case "role" -> "角色标准四视图"; case "scene" -> "标准场景图"; case "tool" -> "标准道具图"; default -> "图";
        };
        String promptEnd = switch (type) {
            case "role" -> "人物角色四视图"; case "scene" -> "标准场景图"; case "tool" -> "标准道具图"; default -> "图";
        };
        return "请根据以下参数生成" + promptTitle + "：\n\n**基础参数：**\n- 画风风格: " +
                (artStyle != null ? artStyle : "未指定") + "\n\n**" + label + "设定：**\n- 名称:" + name +
                ",\n- 提示词:" + prompt + ",\n\n请严格按照系统规范生成" + promptEnd + "。";
    }

    private String getTypeDir(String type) {
        return switch (type) {
            case "role" -> "role"; case "scene" -> "scene"; case "tool" -> "props"; default -> "assets";
        };
    }

    private String parseModelName(String vendorModel) {
        if (vendorModel == null) return "";
        int idx = vendorModel.indexOf(':');
        return idx >= 0 ? vendorModel.substring(idx + 1) : vendorModel;
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
        String projectId = body.get("projectId") != null ? body.get("projectId").toString() : null;
        String type = body.get("type") != null ? body.get("type").toString() : null;
        String name = (String) body.getOrDefault("name", "");
        String describe = (String) body.getOrDefault("describe", "");

        OProject project = projectMapper.selectById(projectId);
        if (project == null) throw new BusinessException("项目为空");

        // Mark in-progress
        com.toonflow.entity.OAssets assetData = assetsMapper.selectById(assetsId);
        if (assetData == null) throw new BusinessException("资产不存在");
        com.toonflow.entity.OAssets upd = new com.toonflow.entity.OAssets();
        upd.setId(assetsId); upd.setPromptState("生成中");
        assetsMapper.updateById(upd);

        boolean isDerivative = assetData.getAssetsId() != null;
        String visualManualKey = getVisualManualKey(type, isDerivative);
        String nameLabel = getNameLabel(type);
        String visualManual = getArtPrompt(project.getArtStyle(), "art_skills", visualManualKey);
        if (visualManual.isBlank()) {
            com.toonflow.entity.OAssets fail = new com.toonflow.entity.OAssets();
            fail.setId(assetsId); fail.setPromptState("生成失败"); fail.setPromptErrorReason("视觉手册未定义");
            assetsMapper.updateById(fail);
            throw new BusinessException("视觉手册未定义");
        }
        try {
            String userContent = "**基础参数：**\n**" + nameLabel + "设定：**\n- " + nameLabel + "名称:" + name + ",\n- " + nameLabel + "描述:" + describe + ",";
            String output = aiService.generateText("universalAi", List.of(
                    new com.toonflow.ai.AiService.ChatMessage("system", visualManual),
                    new com.toonflow.ai.AiService.ChatMessage("user", userContent)));
            if (output == null || output.isBlank()) {
                com.toonflow.entity.OAssets fail = new com.toonflow.entity.OAssets();
                fail.setId(assetsId); fail.setPromptState("生成失败");
                assetsMapper.updateById(fail);
                throw new BusinessException("生成失败");
            }
            com.toonflow.entity.OAssets done = new com.toonflow.entity.OAssets();
            done.setId(assetsId); done.setPrompt(output); done.setPromptState("已完成");
            assetsMapper.updateById(done);
            return R.ok(Map.of("prompt", output, "assetsId", assetsId));
        } catch (BusinessException be) {
            throw be;
        } catch (Exception e) {
            com.toonflow.entity.OAssets fail = new com.toonflow.entity.OAssets();
            fail.setId(assetsId); fail.setPromptState("失败"); fail.setPromptErrorReason(e.getMessage());
            assetsMapper.updateById(fail);
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

}
