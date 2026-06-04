package com.toonflow.controller;

import com.toonflow.ai.AiService;
import com.toonflow.ai.vendor.MediaGenerationService;
import com.toonflow.ai.vendor.VendorService;
import com.toonflow.common.result.R;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 供应商模型测试控制器
 * 对应原项目 setting/vendorConfig/modelTest/*
 * 测试文本/图片/视频模型是否配置正确、可正常调用。
 */
@Slf4j
@RestController
@RequestMapping("/api/setting/vendorConfig/modelTest")
@RequiredArgsConstructor
public class VendorTestController {

    private final AiService aiService;
    private final MediaGenerationService mediaGenerationService;
    private final VendorService vendorService;

    /**
     * 聚合测试入口（type: text/image/video）
     */
    @PostMapping
    public R<Object> modelTest(@RequestBody Map<String, Object> body) {
        String type = body.getOrDefault("type", "text").toString();
        String id = body.get("id") != null ? body.get("id").toString() : null;
        String modelName = body.get("modelName") != null ? body.get("modelName").toString() : null;
        String fullModel = (id != null && !id.isEmpty()) ? id + ":" + modelName : modelName;
        Map<String, Object> dispatch = new java.util.HashMap<>(body);
        dispatch.put("modelName", fullModel);
        return switch (type) {
            case "image" -> R.ok(imageTest(dispatch).getData());
            case "video" -> R.ok(videoTest(dispatch).getData());
            default -> R.ok(textTest(dispatch).getData());
        };
    }

    /**
     * 文本模型测试
     */
    @PostMapping("/textTest")
    public R<Map<String, Object>> textTest(@RequestBody Map<String, Object> body) {
        String modelName = body.get("modelName") != null ? body.get("modelName").toString() : null;
        try {
            var model = aiService.buildChatModel(modelName);
            var messages = List.<org.springframework.ai.chat.messages.Message>of(
                    new org.springframework.ai.chat.messages.UserMessage("hello, please reply connection success"));
            var response = model.call(new org.springframework.ai.chat.prompt.Prompt(messages));
            String reply = response.getResult().getOutput().getText();
            return R.ok(Map.of("success", true, "reply", reply));
        } catch (Exception e) {
            log.warn("文本模型测试失败: {}", e.getMessage());
            return R.ok(Map.of("success", false, "error", e.getMessage() != null ? e.getMessage() : "未知错误"));
        }
    }

    /**
     * 图片模型测试
     */
    @PostMapping("/imageTest")
    public R<Map<String, Object>> imageTest(@RequestBody Map<String, Object> body) {
        String modelName = body.get("modelName") != null ? body.get("modelName").toString() : null;
        String prompt = body.get("prompt") != null ? body.get("prompt").toString() : "a cute cat, test image";
        try {
            String url = mediaGenerationService.generateImage(modelName, prompt, "512x512");
            return R.ok(Map.of("success", true, "url", url != null ? url : ""));
        } catch (Exception e) {
            log.warn("图片模型测试失败: {}", e.getMessage());
            return R.ok(Map.of("success", false, "error", e.getMessage() != null ? e.getMessage() : "未知错误"));
        }
    }

    /**
     * 视频模型测试（仅提交任务验证连通性）
     */
    @PostMapping("/videoTest")
    public R<Map<String, Object>> videoTest(@RequestBody Map<String, Object> body) {
        String modelName = body.get("modelName") != null ? body.get("modelName").toString() : null;
        try {
            // 从模型的 durationResolutionMap 取首个时长/分辨率（与原项目 modelTest 一致）
            Integer duration = null;
            String resolution = null;
            String[] parts = modelName.split(":", 2);
            if (parts.length == 2) {
                try {
                    Map<String, Object> detail = vendorService.getModelDetail(parts[0], parts[1]);
                    Object drm = detail.get("durationResolutionMap");
                    if (drm instanceof List<?> drmList && !drmList.isEmpty()
                            && drmList.get(0) instanceof Map<?, ?> first) {
                        Object durList = first.get("duration");
                        Object resList = first.get("resolution");
                        if (durList instanceof List<?> dl && !dl.isEmpty())
                            duration = ((Number) dl.get(0)).intValue();
                        if (resList instanceof List<?> rl && !rl.isEmpty())
                            resolution = String.valueOf(rl.get(0));
                    }
                } catch (Exception ignored) {}
            }
            String url = mediaGenerationService.generateVideo(modelName,
                    "a cat walking, test video", null, "16:9", duration, resolution);
            return R.ok(Map.of("success", true, "url", url != null ? url : ""));
        } catch (Exception e) {
            log.warn("视频模型测试失败: {}", e.getMessage());
            return R.ok(Map.of("success", false, "error", e.getMessage()));
        }
    }
}
