package com.toonflow.ai.vendor.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.toonflow.ai.vendor.VendorAdapter;
import com.toonflow.ai.vendor.dto.ImageConfig;
import com.toonflow.ai.vendor.dto.VideoConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Toonflow 官方中转平台 供应商适配器。
 *
 * 移植自 vendor-reference/toonflow.ts（v2.0）。
 *
 * 鉴权：Authorization: Bearer {apiKey}（去除可能已存在的 "Bearer " 前缀），baseUrl 默认 https://api.toonflow.net/v1。
 *
 * 视频：POST {baseUrl}/video/generations（同步提交，返回 task id）
 * 轮询：GET  {baseUrl}/video/generations/{taskId}，间隔轮询直到 status 为 success/completed 或 failed。
 *
 * 图片：
 *   - gemini/nano 系：POST {baseUrl}/chat/completions，从 markdown 中提取图片
 *   - doubao/seedream 系：POST {baseUrl}/images/generations
 *
 * 类名为 ToonflowVendorAdapter 以避免与其它 Toonflow* 类冲突，
 * 但 vendorId() 与 @Component 名称均为 "toonflow"。
 */
@Slf4j
@Component("toonflow")
public class ToonflowVendorAdapter implements VendorAdapter {

    private static final String DEFAULT_BASE_URL = "https://api.toonflow.net/v1";
    private static final int MAX_POLL = 200;
    private static final long POLL_INTERVAL_MS = 3000;

    private final RestClient restClient = RestClient.create();

    @Override
    public String vendorId() {
        return "toonflow";
    }

    /** 解析 apiKey，去除可能已存在的 "Bearer " 前缀（对应 TS: apiKey.replace(/^Bearer\s+/i, "")）。 */
    private String resolveApiKey(Map<String, String> inputs) {
        String apiKey = inputs.getOrDefault("apiKey", "");
        if (apiKey == null || apiKey.isEmpty()) {
            throw new RuntimeException("缺少API Key");
        }
        return apiKey.replaceFirst("(?i)^Bearer\\s+", "");
    }

    private String resolveBaseUrl(Map<String, String> inputs) {
        String baseUrl = inputs.get("baseUrl");
        return (baseUrl == null || baseUrl.isEmpty()) ? DEFAULT_BASE_URL : baseUrl;
    }

    // ============================================================
    // 视频生成
    // ============================================================

    @Override
    public String videoRequest(VideoConfig config, Map<String, String> inputs) {
        String apiKey = resolveApiKey(inputs);
        String baseUrl = resolveBaseUrl(inputs);
        String modelName = config.getModelId();
        String lowerName = modelName != null ? modelName.toLowerCase() : "";

        List<String> imageRefs = config.getReferenceList() != null
                ? config.getReferenceList() : new ArrayList<>();

        Map<String, Object> body;

        if (lowerName.contains("wan")) {
            // 万象系列：单独的请求体（带 size 字段、metadata 首尾帧）
            Map<String, Object> metadata = new HashMap<>();
            if (imageRefs.size() >= 2) {
                if (imageRefs.get(0) != null) metadata.put("first_frame_url", imageRefs.get(0));
                if (imageRefs.get(1) != null) metadata.put("last_frame_url", imageRefs.get(1));
            } else if (!imageRefs.isEmpty()) {
                metadata.put("img_url", imageRefs.get(0));
            }
            if (config.getAudio() != null) {
                metadata.put("audio", config.getAudio());
            }

            String wanSize = wanSize(config.getResolution(), config.getAspectRatio());

            body = new HashMap<>();
            body.put("model", modelName);
            body.put("prompt", config.getPrompt());
            body.put("duration", config.getDuration());
            if (wanSize != null) body.put("size", wanSize);
            body.put("metadata", metadata);

            log.info("[videoRequest] 提交万象视频任务，模型: {}", modelName);
        } else {
            // 非万象通用路径
            Map<String, Object> metadata = new HashMap<>();

            if (lowerName.contains("doubao") || lowerName.contains("seedance")) {
                if (config.getAudio() != null) {
                    metadata.put("generate_audio", config.getAudio());
                }
                metadata.put("ratio", config.getAspectRatio());
                List<String> imageRoles = new ArrayList<>();
                List<String> references = new ArrayList<>();
                // 首尾帧模式：根据顺序标记 first_frame / last_frame
                for (int i = 0; i < imageRefs.size(); i++) {
                    imageRoles.add(i == 0 ? "first_frame" : "last_frame");
                }
                metadata.put("image_roles", imageRoles);
                metadata.put("references", references);
            } else if (lowerName.contains("vidu")) {
                metadata.put("aspect_ratio", config.getAspectRatio());
                metadata.put("audio", config.getAudio() != null ? config.getAudio() : false);
                metadata.put("off_peak", false);
            } else if (lowerName.contains("kling")) {
                metadata.put("aspect_ratio", config.getAspectRatio());
                if (!imageRefs.isEmpty()) {
                    metadata.put("image", imageRefs.get(0));
                }
            }

            body = new HashMap<>();
            body.put("model", modelName);
            if (!imageRefs.isEmpty()) {
                body.put("images", imageRefs);
            }
            body.put("prompt", config.getPrompt());
            body.put("duration", config.getDuration());
            body.put("metadata", metadata);

            log.info("[videoRequest] 提交视频任务，模型: {}", modelName);
        }

        // 提交任务
        JsonNode submitResp = restClient.post()
                .uri(baseUrl + "/video/generations")
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .body(body)
                .retrieve()
                .body(JsonNode.class);

        String taskId = submitResp != null ? submitResp.path("id").asText(null) : null;
        if (taskId == null) {
            throw new RuntimeException("Toonflow 视频任务提交失败: " + submitResp);
        }
        log.info("[videoRequest] 任务ID: {}", taskId);

        return pollVideoTask(baseUrl, apiKey, taskId);
    }

    /** 轮询视频任务，对应 TS pollTask。返回最终 result_url。 */
    private String pollVideoTask(String baseUrl, String apiKey, String taskId) {
        for (int i = 0; i < MAX_POLL; i++) {
            try {
                Thread.sleep(POLL_INTERVAL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("轮询被中断");
            }

            JsonNode queryData = restClient.get()
                    .uri(baseUrl + "/video/generations/" + taskId)
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .retrieve()
                    .body(JsonNode.class);

            // status = queryData.status ?? queryData.data.status
            String status = "";
            if (queryData != null) {
                if (queryData.hasNonNull("status")) {
                    status = queryData.path("status").asText("");
                } else {
                    status = queryData.path("data").path("status").asText("");
                }
            }

            switch (status) {
                case "completed", "SUCCESS", "success" -> {
                    return queryData.path("data").path("result_url").asText();
                }
                case "FAILURE", "failed" -> {
                    String reason = queryData.path("data").path("fail_reason").asText("视频生成失败");
                    throw new RuntimeException(reason);
                }
                default -> log.debug("Toonflow 视频任务 {} 状态: {} ({}/{})", taskId, status, i + 1, MAX_POLL);
            }
        }
        throw new RuntimeException("Toonflow 视频任务轮询超时");
    }

    /** 万象 size 映射：resolution + aspectRatio -> "W*H"。 */
    private String wanSize(String resolution, String aspectRatio) {
        if (resolution == null || aspectRatio == null) return null;
        Map<String, Map<String, String>> map = new HashMap<>();
        map.put("480p", Map.of("16:9", "832*480", "9:16", "480*832"));
        map.put("720p", Map.of("16:9", "1280*720", "9:16", "720*1280"));
        map.put("1080p", Map.of("16:9", "1920*1080", "9:16", "1080*1920"));
        Map<String, String> byRatio = map.get(resolution);
        return byRatio != null ? byRatio.get(aspectRatio) : null;
    }

    // ============================================================
    // 图片生成
    // ============================================================

    @Override
    public String imageRequest(ImageConfig config, Map<String, String> inputs) {
        String apiKey = resolveApiKey(inputs);
        String baseUrl = resolveBaseUrl(inputs);
        String modelName = config.getModelId();
        String lowerName = modelName != null ? modelName.toLowerCase() : "";

        List<String> imageBase64List = config.getReferences() != null
                ? config.getReferences() : new ArrayList<>();

        // gemini / nano 系：走 chat/completions，从 markdown 中提取图片
        if (lowerName.contains("gemini") || lowerName.contains("nano")) {
            Map<String, String> imageConfigGoogle = new HashMap<>();
            imageConfigGoogle.put("aspect_ratio", config.getRatio());
            imageConfigGoogle.put("image_size", config.getResolution());

            List<Map<String, Object>> messages = new ArrayList<>();
            if (!imageBase64List.isEmpty()) {
                List<Map<String, Object>> content = new ArrayList<>();
                for (String b : imageBase64List) {
                    Map<String, Object> imgUrl = new HashMap<>();
                    imgUrl.put("url", b);
                    Map<String, Object> part = new HashMap<>();
                    part.put("type", "image_url");
                    part.put("image_url", imgUrl);
                    content.add(part);
                }
                Map<String, Object> userMsg = new HashMap<>();
                userMsg.put("role", "user");
                userMsg.put("content", content);
                messages.add(userMsg);
            }
            Map<String, Object> textMsg = new HashMap<>();
            textMsg.put("role", "user");
            textMsg.put("content", config.getPrompt() + "请直接输出图片");
            messages.add(textMsg);

            Map<String, Object> google = new HashMap<>();
            google.put("image_config", imageConfigGoogle);
            Map<String, Object> extraBody = new HashMap<>();
            extraBody.put("google", google);

            Map<String, Object> body = new HashMap<>();
            body.put("model", modelName);
            body.put("messages", messages);
            body.put("extra_body", extraBody);

            log.info("[imageRequest] 使用 gemini 适配器，模型: {}", modelName);
            JsonNode data = restClient.post()
                    .uri(baseUrl + "/chat/completions")
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .body(body)
                    .retrieve()
                    .body(JsonNode.class);

            String mdContent = data != null
                    ? data.path("choices").path(0).path("message").path("content").asText("") : "";
            String url = extractFirstImageFromMd(mdContent);
            if (url == null) {
                throw new RuntimeException("未能从响应中提取图片");
            }
            return url;
        }

        // 豆包 / seedream 系：走 images/generations
        if (lowerName.contains("doubao") || lowerName.contains("seedream")) {
            String size = config.getResolution();
            String effectiveSize = "1K".equals(size) ? "2K" : size;
            Map<String, Map<String, String>> sizeMap = new HashMap<>();
            sizeMap.put("16:9", Map.of("2K", "2848x1600", "4K", "4096x2304"));
            sizeMap.put("9:16", Map.of("2K", "1600x2848", "4K", "2304x4096"));
            String resolvedSize = null;
            Map<String, String> byRatio = sizeMap.get(config.getRatio());
            if (byRatio != null) resolvedSize = byRatio.get(effectiveSize);

            Map<String, Object> body = new HashMap<>();
            body.put("model", modelName);
            body.put("prompt", config.getPrompt());
            body.put("size", resolvedSize);
            body.put("response_format", "url");
            body.put("sequential_image_generation", "disabled");
            body.put("stream", false);
            body.put("watermark", false);
            if (!imageBase64List.isEmpty()) {
                body.put("image", imageBase64List);
            }

            log.info("[imageRequest] 使用 doubao 适配器，模型: {}", modelName);
            JsonNode data = restClient.post()
                    .uri(baseUrl + "/images/generations")
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .body(body)
                    .retrieve()
                    .body(JsonNode.class);

            String resultUrl = data != null
                    ? data.path("data").path(0).path("url").asText(null) : null;
            if (resultUrl == null) {
                throw new RuntimeException("Toonflow 图片生成失败: " + data);
            }
            return resultUrl;
        }

        throw new RuntimeException("不支持的图像模型: " + modelName);
    }

    /**
     * 从 markdown 内容中提取第一张图片地址（base64 data URI 或 url）。
     * 对应 TS extractFirstImageFromMd。
     */
    private String extractFirstImageFromMd(String content) {
        if (content == null) return null;
        java.util.regex.Pattern regex = java.util.regex.Pattern.compile(
                "!\\[([^\\]]*)\\]\\((data:image/[^;]+;base64,[A-Za-z0-9+/=]+|https?://[^\\s)]+|//[^\\s)]+|[^\\s)]+)\\)");
        java.util.regex.Matcher m = regex.matcher(content);
        if (!m.find()) return null;
        String raw = m.group(2).trim();
        if (raw.startsWith("data:")) {
            return raw;
        }
        return raw.split("\\s+")[0];
    }
}
