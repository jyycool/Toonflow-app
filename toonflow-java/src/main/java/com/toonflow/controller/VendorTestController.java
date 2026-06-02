package com.toonflow.controller;

import com.toonflow.ai.AiService;
import com.toonflow.ai.vendor.MediaGenerationService;
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

    /**
     * 聚合测试入口（type: text/image/video）
     */
    @PostMapping
    public R<Object> modelTest(@RequestBody Map<String, String> body) {
        String type = body.getOrDefault("type", "text");
        return switch (type) {
            case "image" -> R.ok(imageTest(body).getData());
            case "video" -> R.ok(videoTest(body).getData());
            default -> R.ok(textTest(body).getData());
        };
    }

    /**
     * 文本模型测试
     */
    @PostMapping("/textTest")
    public R<Map<String, Object>> textTest(@RequestBody Map<String, String> body) {
        String modelName = body.get("modelName"); // vendorId:modelId
        try {
            String reply = aiService.generateText(modelName, List.of(
                    new AiService.ChatMessage("user", "你好，请回复“连接成功”")));
            return R.ok(Map.of("success", true, "reply", reply));
        } catch (Exception e) {
            log.warn("文本模型测试失败: {}", e.getMessage());
            return R.ok(Map.of("success", false, "error", e.getMessage()));
        }
    }

    /**
     * 图片模型测试
     */
    @PostMapping("/imageTest")
    public R<Map<String, Object>> imageTest(@RequestBody Map<String, String> body) {
        String modelName = body.get("modelName");
        try {
            String url = mediaGenerationService.generateImage(modelName,
                    "a cute cat, test image", "512x512");
            return R.ok(Map.of("success", true, "url", url));
        } catch (Exception e) {
            log.warn("图片模型测试失败: {}", e.getMessage());
            return R.ok(Map.of("success", false, "error", e.getMessage()));
        }
    }

    /**
     * 视频模型测试（仅提交任务验证连通性）
     */
    @PostMapping("/videoTest")
    public R<Map<String, Object>> videoTest(@RequestBody Map<String, String> body) {
        String modelName = body.get("modelName");
        try {
            String taskId = mediaGenerationService.submitVideoTask(modelName,
                    "a cat walking, test video", null);
            return R.ok(Map.of("success", true, "taskId", taskId != null ? taskId : ""));
        } catch (Exception e) {
            log.warn("视频模型测试失败: {}", e.getMessage());
            return R.ok(Map.of("success", false, "error", e.getMessage()));
        }
    }
}
