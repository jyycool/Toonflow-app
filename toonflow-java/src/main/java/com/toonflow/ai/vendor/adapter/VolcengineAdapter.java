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
 * 火山引擎(豆包) 厂商适配器
 *
 * 对应原项目 TS 模板 volcengine.ts。
 *
 * 鉴权：Bearer apiKey（inputValues.apiKey），baseUrl 以 /v3 结束。
 *
 * 图片：POST {baseUrl}/images/generations（同步返回 url/b64_json）
 * 视频：POST {baseUrl}/contents/generations/tasks（异步提交）
 * 轮询：GET  {baseUrl}/contents/generations/tasks/{id}，间隔 10s，最长 30 分钟
 *
 * 用 @Component("volcengine")，vendorId 与 o_vendorConfig.id 对应。
 */
@Slf4j
@Component("volcengine")
public class VolcengineAdapter implements VendorAdapter {

    private static final String DEFAULT_BASE = "https://ark.cn-beijing.volces.com/api/v3";
    private static final long POLL_INTERVAL_MS = 10000;
    /** 超时 600000 * 3 = 30 分钟，按轮询间隔换算次数 */
    private static final int MAX_POLL = (int) ((600000L * 3) / POLL_INTERVAL_MS);

    private final RestClient restClient = RestClient.create();

    @Override
    public String vendorId() {
        return "volcengine";
    }

    private String getBaseUrl(Map<String, String> inputs) {
        String baseUrl = inputs.getOrDefault("baseUrl", DEFAULT_BASE);
        if (baseUrl == null || baseUrl.isEmpty()) {
            baseUrl = DEFAULT_BASE;
        }
        // 去除尾部斜杠
        return baseUrl.replaceAll("/+$", "");
    }

    private String getApiKey(Map<String, String> inputs) {
        String apiKey = inputs.getOrDefault("apiKey", "");
        if (apiKey == null || apiKey.isEmpty()) {
            throw new RuntimeException("缺少API Key");
        }
        // 去除可能已存在的 Bearer 前缀
        return apiKey.replaceAll("(?i)^Bearer\\s+", "");
    }

    // ============================================================
    // 图片生成
    // ============================================================

    @Override
    public String imageRequest(ImageConfig config, Map<String, String> inputs) {
        String baseUrl = getBaseUrl(inputs);
        String apiKey = getApiKey(inputs);
        String modelName = config.getModelId() != null ? config.getModelId() : "";

        Map<String, Object> body = new HashMap<>();
        body.put("model", modelName);
        body.put("prompt", config.getPrompt() != null ? config.getPrompt() : "");
        body.put("response_format", "url");
        body.put("watermark", false);

        boolean isOldModel = modelName.contains("seedream-3-0");
        boolean is5Lite = modelName.contains("seedream-5-0-lite");

        // sequential_image_generation 仅 seedream 5.0-lite/4.5/4.0 支持
        if (!isOldModel) {
            body.put("sequential_image_generation", "disabled");
        }

        // 参考图片：单图为 string，多图为 array（seedream-3.0-t2i 不支持 image 参数）
        List<String> refs = config.getReferences();
        if (!isOldModel && refs != null && !refs.isEmpty()) {
            if (refs.size() == 1) {
                body.put("image", refs.get(0));
            } else {
                body.put("image", refs);
            }
        }

        // 尺寸处理
        String aspectRatio = config.getRatio() != null ? config.getRatio() : "1:1";
        String sizeKey = config.getResolution() != null ? config.getResolution() : "2K";

        Map<String, Map<String, String>> sizeTable = buildSizeTable();
        Map<String, String> table = sizeTable.get(sizeKey);
        String matched = table != null ? table.get(aspectRatio) : null;

        String size;
        if (matched != null) {
            String[] parts = matched.split("x");
            long pw = Long.parseLong(parts[0]);
            long ph = Long.parseLong(parts[1]);
            long totalPixels = pw * ph;
            if (isOldModel) {
                // seedream-3.0-t2i: 像素范围 [512x512, 2048x2048]
                size = matched;
            } else if (totalPixels < 3686400L) {
                // 1K 像素值不满足新模型最低要求，直接传 "2K"
                size = "2K";
            } else if (is5Lite && totalPixels > 10404496L) {
                // seedream-5.0-lite 最高 10404496，4K 超限，回退传 "2K"
                size = "2K";
            } else {
                size = matched;
            }
        } else if (isOldModel) {
            // seedream-3.0-t2i: 按比例计算，像素范围 [512x512, 2048x2048]
            String[] wh = aspectRatio.split(":");
            double w = Double.parseDouble(wh[0]);
            double h = Double.parseDouble(wh[1]);
            int base = "1K".equals(sizeKey) ? 1024 : 2048;
            int calcW = (int) Math.min(2048, Math.round(base * Math.sqrt(w / h)));
            int calcH = (int) Math.min(2048, Math.round(base * Math.sqrt(h / w)));
            size = Math.max(512, calcW) + "x" + Math.max(512, calcH);
        } else {
            // 新模型未匹配推荐值时，直接传分辨率字符串
            if (is5Lite) {
                size = "4K".equals(sizeKey) ? "3K" : ("1K".equals(sizeKey) ? "2K" : sizeKey);
            } else {
                size = "1K".equals(sizeKey) ? "2K" : sizeKey;
            }
        }
        body.put("size", size);

        log.info("[图片生成] 请求模型: {}, 尺寸: {}", modelName, size);

        JsonNode data = restClient.post()
                .uri(baseUrl + "/images/generations")
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .body(body)
                .retrieve()
                .body(JsonNode.class);

        if (data != null && data.has("error") && !data.path("error").isNull()) {
            JsonNode err = data.path("error");
            throw new RuntimeException("图片生成失败：" + err.path("message").asText(err.path("code").asText("")));
        }

        if (data != null && data.path("data").isArray() && data.path("data").size() > 0) {
            for (JsonNode item : data.path("data")) {
                if (item.hasNonNull("url")) {
                    return item.path("url").asText();
                }
                if (item.hasNonNull("b64_json")) {
                    return item.path("b64_json").asText();
                }
                if (item.has("error") && !item.path("error").isNull()) {
                    JsonNode err = item.path("error");
                    throw new RuntimeException("图片生成失败：" + err.path("message").asText(err.path("code").asText("")));
                }
            }
        }

        throw new RuntimeException("图片生成失败：未返回有效结果");
    }

    private Map<String, Map<String, String>> buildSizeTable() {
        Map<String, Map<String, String>> sizeTable = new HashMap<>();

        Map<String, String> k1 = new HashMap<>();
        k1.put("1:1", "1024x1024");
        k1.put("4:3", "1152x864");
        k1.put("3:4", "864x1152");
        k1.put("16:9", "1280x720");
        k1.put("9:16", "720x1280");
        k1.put("3:2", "1248x832");
        k1.put("2:3", "832x1248");
        k1.put("21:9", "1512x648");
        sizeTable.put("1K", k1);

        Map<String, String> k2 = new HashMap<>();
        k2.put("1:1", "2048x2048");
        k2.put("4:3", "2304x1728");
        k2.put("3:4", "1728x2304");
        k2.put("16:9", "2848x1600");
        k2.put("9:16", "1600x2848");
        k2.put("3:2", "2496x1664");
        k2.put("2:3", "1664x2496");
        k2.put("21:9", "3136x1344");
        sizeTable.put("2K", k2);

        Map<String, String> k4 = new HashMap<>();
        k4.put("1:1", "4096x4096");
        k4.put("4:3", "4704x3520");
        k4.put("3:4", "3520x4704");
        k4.put("16:9", "5504x3040");
        k4.put("9:16", "3040x5504");
        k4.put("3:2", "4992x3328");
        k4.put("2:3", "3328x4992");
        k4.put("21:9", "6240x2656");
        sizeTable.put("4K", k4);

        return sizeTable;
    }

    // ============================================================
    // 视频生成
    // ============================================================

    @Override
    public String videoRequest(VideoConfig config, Map<String, String> inputs) {
        String baseUrl = getBaseUrl(inputs);
        String apiKey = getApiKey(inputs);
        String modelName = config.getModelId() != null ? config.getModelId() : "";

        List<Map<String, Object>> content = new ArrayList<>();

        if (config.getPrompt() != null && !config.getPrompt().isEmpty()) {
            Map<String, Object> text = new HashMap<>();
            text.put("type", "text");
            text.put("text", config.getPrompt());
            content.add(text);
        }

        // VideoConfig 只提供 referenceList（图片 base64/url），按首尾帧处理（对应 startFrameOptional）
        List<String> refs = config.getReferenceList();
        if (refs != null && !refs.isEmpty()) {
            content.add(imageContent(refs.get(0), "first_frame"));
            if (refs.size() > 1) {
                content.add(imageContent(refs.get(1), "last_frame"));
            }
        }

        Map<String, Object> body = new HashMap<>();
        body.put("model", modelName);
        body.put("content", content);
        body.put("ratio", config.getAspectRatio());
        body.put("duration", config.getDuration());
        body.put("resolution", config.getResolution() != null ? config.getResolution() : "720p");
        body.put("watermark", false);
        // audio 为 true 或 null 时生成音频（对应 TS audio !== false）
        body.put("generate_audio", !Boolean.FALSE.equals(config.getAudio()));

        log.info("[视频生成] 提交任务, 模型: {}, 时长: {}s, 分辨率: {}",
                modelName, config.getDuration(), config.getResolution());

        JsonNode createResp = restClient.post()
                .uri(baseUrl + "/contents/generations/tasks")
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .body(body)
                .retrieve()
                .body(JsonNode.class);

        String taskId = createResp != null ? createResp.path("id").asText(null) : null;
        if (taskId == null || taskId.isEmpty()) {
            throw new RuntimeException("视频生成任务创建失败：未返回任务ID");
        }

        log.info("[视频生成] 任务已创建, ID: {}", taskId);

        return pollTask(baseUrl, apiKey, taskId);
    }

    private Map<String, Object> imageContent(String url, String role) {
        Map<String, Object> node = new HashMap<>();
        node.put("type", "image_url");
        Map<String, Object> imageUrl = new HashMap<>();
        imageUrl.put("url", url);
        node.put("image_url", imageUrl);
        node.put("role", role);
        return node;
    }

    private String pollTask(String baseUrl, String apiKey, String taskId) {
        for (int i = 0; i < MAX_POLL; i++) {
            try {
                Thread.sleep(POLL_INTERVAL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("轮询被中断");
            }

            JsonNode task = restClient.get()
                    .uri(baseUrl + "/contents/generations/tasks/" + taskId)
                    .header("Authorization", "Bearer " + apiKey)
                    .retrieve()
                    .body(JsonNode.class);

            String status = task != null ? task.path("status").asText("") : "";
            log.debug("[视频生成] 任务状态: {} ({}/{})", status, i + 1, MAX_POLL);

            switch (status) {
                case "succeeded" -> {
                    String videoUrl = task.path("content").path("video_url").asText(null);
                    if (videoUrl != null && !videoUrl.isEmpty()) {
                        return videoUrl;
                    }
                    throw new RuntimeException("任务成功但未返回视频URL");
                }
                case "failed" -> throw new RuntimeException(
                        task.path("error").path("message").asText("视频生成失败"));
                case "expired" -> throw new RuntimeException("视频生成任务超时");
                case "cancelled" -> throw new RuntimeException("视频生成任务已取消");
                default -> {
                    // running / queued / pending，继续轮询
                }
            }
        }
        throw new RuntimeException("视频生成任务轮询超时");
    }
}
