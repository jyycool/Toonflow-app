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
 * AtlasCloud MASS 厂商适配器
 *
 * 移植自 vendor-reference/atlascloud.ts（version 1.0）。
 *
 * 说明：
 * 1) 文本接口使用 OpenAI 兼容基地址：https://api.atlascloud.ai/v1（此处不实现）
 * 2) 图片/视频使用 Atlas Cloud 媒体接口：https://api.atlascloud.ai/api/v1
 * 3) 图片/视频为异步任务：提交后轮询 /api/v1/model/prediction/{id}
 *
 * 认证：Authorization: Bearer {apiKey}（去掉可能存在的前缀 "Bearer "）
 * 图片提交：POST {mediaBaseUrl}/model/generateImage
 * 视频提交：POST {mediaBaseUrl}/model/generateVideo
 * 轮询：    GET  {mediaBaseUrl}/model/prediction/{taskId}
 */
@Slf4j
@Component("atlascloud")
public class AtlascloudAdapter implements VendorAdapter {

    private static final String DEFAULT_MEDIA_BASE_URL = "https://api.atlascloud.ai/api/v1";

    // 轮询参数（与 TS pollTask 一致）
    private static final long IMAGE_POLL_INTERVAL_MS = 3000;
    private static final long IMAGE_POLL_TIMEOUT_MS = 600000;
    private static final long VIDEO_POLL_INTERVAL_MS = 5000;
    private static final long VIDEO_POLL_TIMEOUT_MS = 1800000;

    private static final List<String> SUCCESS_STATUSES =
            List.of("succeeded", "success", "done", "completed");
    private static final List<String> FAILED_STATUSES =
            List.of("failed", "error", "cancelled", "canceled", "expired");

    private final RestClient restClient = RestClient.create();

    @Override
    public String vendorId() {
        return "atlascloud";
    }

    // ============================================================
    // 辅助工具
    // ============================================================

    private static String getMediaBaseUrl(Map<String, String> inputs) {
        String base = inputs.getOrDefault("mediaBaseUrl", DEFAULT_MEDIA_BASE_URL);
        if (base == null || base.isEmpty()) {
            base = DEFAULT_MEDIA_BASE_URL;
        }
        return base.replaceAll("/+$", "");
    }

    private static String joinUrl(String base, String path) {
        return base + (path.startsWith("/") ? "" : "/") + path;
    }

    private static String resolveApiKey(Map<String, String> inputs) {
        String apiKey = inputs.getOrDefault("apiKey", "");
        if (apiKey == null || apiKey.isEmpty()) {
            throw new RuntimeException("缺少 API Key");
        }
        return apiKey.replaceFirst("(?i)^Bearer\\s+", "");
    }

    private JsonNode readByPath(JsonNode obj, String path) {
        if (obj == null || path == null || path.isEmpty()) {
            return null;
        }
        String normalized = path.replaceAll("\\[(\\d+)\\]", ".$1");
        JsonNode acc = obj;
        for (String key : normalized.split("\\.")) {
            if (acc == null || acc.isMissingNode() || acc.isNull()) {
                return null;
            }
            if (key.matches("\\d+") && acc.isArray()) {
                acc = acc.get(Integer.parseInt(key));
            } else {
                acc = acc.get(key);
            }
        }
        return (acc == null || acc.isMissingNode() || acc.isNull()) ? null : acc;
    }

    private String pickFirstPath(JsonNode obj, String... paths) {
        for (String path : paths) {
            JsonNode value = readByPath(obj, path);
            if (value != null && value.isValueNode()) {
                String text = value.asText();
                if (text != null && !text.isEmpty()) {
                    return text;
                }
            }
        }
        return null;
    }

    private String extractTaskId(JsonNode data) {
        return pickFirstPath(data, "id", "taskId", "task_id", "data.id", "data.taskId", "data.task_id");
    }

    private String extractUrl(JsonNode data) {
        JsonNode dataOutputs = readByPath(data, "data.outputs");
        if (dataOutputs != null && dataOutputs.isArray() && dataOutputs.size() > 0) {
            return dataOutputs.get(0).asText();
        }
        JsonNode outputs = readByPath(data, "outputs");
        if (outputs != null && outputs.isArray() && outputs.size() > 0) {
            return outputs.get(0).asText();
        }
        return pickFirstPath(data,
                "url", "video_url", "image_url",
                "data.url", "data.video_url", "data.image_url",
                "data.output.url", "data.output.video_url",
                "output.url");
    }

    private String extractB64(JsonNode data) {
        return pickFirstPath(data, "b64_json", "data.b64_json", "data.0.b64_json", "data[0].b64_json");
    }

    private String extractStatus(JsonNode data) {
        String statusRaw = pickFirstPath(data, "status", "data.status", "data.state", "state");
        return statusRaw == null ? "" : statusRaw.toLowerCase();
    }

    private String extractError(JsonNode data) {
        return pickFirstPath(data, "error.message", "message", "msg", "data.error.message", "data.message");
    }

    private double clampNumber(Integer value, double min, double max, double fallback) {
        if (value == null) {
            return fallback;
        }
        return Math.max(min, Math.min(max, value));
    }

    private String normalizeResolution(String value, List<String> allowed, String fallback) {
        String lower = value == null ? "" : value.toLowerCase();
        for (String item : allowed) {
            if (item.toLowerCase().equals(lower)) {
                return item;
            }
        }
        if (lower.contains("1080")) {
            return allowed.stream().filter(i -> i.matches("(?i).*1080.*")).findFirst().orElse(fallback);
        }
        if (lower.contains("720")) {
            return allowed.stream().filter(i -> i.matches("(?i).*720.*")).findFirst().orElse(fallback);
        }
        if (lower.contains("480")) {
            return allowed.stream().filter(i -> i.matches("(?i).*480.*")).findFirst().orElse(fallback);
        }
        return fallback;
    }

    private String resolveAtlasVideoModelKind(String modelName) {
        if ("alibaba/wan-2.7/reference-to-video".equals(modelName)) {
            return "wanReferenceToVideo";
        }
        if (modelName.matches("^bytedance/seedance-2\\.0(?:-fast)?/reference-to-video$")) {
            return "seedanceReferenceToVideo";
        }
        if (modelName.matches("^bytedance/seedance-2\\.0(?:-fast)?/image-to-video$")) {
            return "seedanceImageToVideo";
        }
        if (modelName.matches("^bytedance/seedance-2\\.0(?:-fast)?/text-to-video$")) {
            return "seedanceTextToVideo";
        }
        return "generic";
    }

    private String resolveAtlasImageModelName(String modelName, boolean hasImageRefs) {
        if (!hasImageRefs) {
            return modelName;
        }
        return switch (modelName) {
            case "google/nano-banana-pro/text-to-image" -> "google/nano-banana-pro/edit";
            case "google/nano-banana-2/text-to-image" -> "google/nano-banana-2/edit";
            default -> modelName;
        };
    }

    private List<String> nonEmpty(List<String> in) {
        List<String> out = new ArrayList<>();
        if (in != null) {
            for (String s : in) {
                if (s != null && !s.isEmpty()) {
                    out.add(s);
                }
            }
        }
        return out;
    }

    // ============================================================
    // 图片
    // ============================================================

    @Override
    public String imageRequest(ImageConfig config, Map<String, String> inputs) {
        String apiKey = resolveApiKey(inputs);
        String mediaBaseUrl = getMediaBaseUrl(inputs);
        String url = joinUrl(mediaBaseUrl, "/model/generateImage");

        List<String> imageRefs = nonEmpty(config.getReferences());
        String modelName = config.getModelId() != null ? config.getModelId() : "";
        String resolvedModelName = resolveAtlasImageModelName(modelName, !imageRefs.isEmpty());
        boolean isNanoModel = resolvedModelName.matches("^google/nano-banana-(pro|2)/.*");
        boolean supportsImageConditioning = resolvedModelName.matches(
                "^(openai/gpt-image-2/text-to-image|google/nano-banana-(pro|2)/edit)$");

        Map<String, Object> body = new HashMap<>();
        body.put("model", resolvedModelName);
        body.put("prompt", config.getPrompt() != null ? config.getPrompt() : "");
        if (supportsImageConditioning && !imageRefs.isEmpty()) {
            body.put("images", imageRefs);
        }
        if (isNanoModel) {
            body.put("aspect_ratio", config.getRatio() != null ? config.getRatio() : "16:9");
            String size = config.getResolution() != null ? config.getResolution().toLowerCase() : "1k";
            String resolution = switch (size) {
                case "1k" -> "1k";
                case "2k" -> "2k";
                case "4k" -> "4k";
                default -> "1k";
            };
            body.put("resolution", resolution);
        }

        log.info("[AtlasCloud 图片] 提交任务: {} -> {}, refs={}", modelName, resolvedModelName, imageRefs.size());
        JsonNode submitData = restClient.post()
                .uri(url)
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .body(body)
                .retrieve()
                .body(JsonNode.class);

        // 同步返回（直接拿图）
        String syncB64 = extractB64(submitData);
        if (syncB64 != null) {
            return syncB64;
        }
        String syncUrl = extractUrl(submitData);
        if (syncUrl != null) {
            return syncUrl;
        }

        // 异步返回（拿 taskId 再轮询）
        String taskId = extractTaskId(submitData);
        if (taskId == null) {
            throw new RuntimeException("图片任务提交失败：未获取到任务ID。原始响应：" + truncate(submitData));
        }

        return pollTask(taskId, apiKey, mediaBaseUrl, "image",
                IMAGE_POLL_INTERVAL_MS, IMAGE_POLL_TIMEOUT_MS);
    }

    // ============================================================
    // 视频
    // ============================================================

    @Override
    public String videoRequest(VideoConfig config, Map<String, String> inputs) {
        String apiKey = resolveApiKey(inputs);
        String mediaBaseUrl = getMediaBaseUrl(inputs);
        String url = joinUrl(mediaBaseUrl, "/model/generateVideo");

        Map<String, Object> body = buildAtlasVideoPayload(config);

        log.info("[AtlasCloud 视频] 提交任务: {}", config.getModelId());
        JsonNode submitData = restClient.post()
                .uri(url)
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .body(body)
                .retrieve()
                .body(JsonNode.class);

        String taskId = extractTaskId(submitData);
        if (taskId == null) {
            String syncUrl = extractUrl(submitData);
            if (syncUrl != null) {
                return syncUrl;
            }
            throw new RuntimeException("视频任务提交失败：未获取到任务ID。原始响应：" + truncate(submitData));
        }

        return pollTask(taskId, apiKey, mediaBaseUrl, "video",
                VIDEO_POLL_INTERVAL_MS, VIDEO_POLL_TIMEOUT_MS);
    }

    /**
     * 构建视频请求体。
     *
     * 注意：Java 端 VideoConfig 只提供扁平的 getReferenceList()（List&lt;String&gt;），
     * 没有按 image/video/audio 区分类型，故将其全部视为图片参考（与 TS 中 image 参考路径一致）。
     */
    private Map<String, Object> buildAtlasVideoPayload(VideoConfig config) {
        String modelName = config.getModelId() != null ? config.getModelId() : "";
        List<String> imageRefs = nonEmpty(config.getReferenceList());
        String kind = resolveAtlasVideoModelKind(modelName);
        String ratio = config.getAspectRatio() != null ? config.getAspectRatio() : "16:9";
        boolean shouldGenerateAudio = config.getAudio() == null || Boolean.TRUE.equals(config.getAudio());

        Map<String, Object> body = new HashMap<>();
        body.put("model", modelName);
        body.put("prompt", config.getPrompt() != null ? config.getPrompt() : "");

        switch (kind) {
            case "wanReferenceToVideo" -> {
                if (imageRefs.isEmpty()) {
                    throw new RuntimeException(modelName + " 需要至少 1 张参考图");
                }
                body.put("images", List.of(imageRefs.get(0)));
                body.put("ratio", ratio);
                body.put("duration", clampNumber(config.getDuration(), 2, 10, 5));
                body.put("resolution", normalizeResolution(config.getResolution(), List.of("720P", "1080P"), "720P"));
                body.put("prompt_extend", false);
                body.put("seed", -1);
            }
            case "seedanceReferenceToVideo" -> {
                if (imageRefs.isEmpty()) {
                    throw new RuntimeException(modelName + " 需要至少 1 张参考图");
                }
                if (shouldGenerateAudio) {
                    body.put("generate_audio", true);
                }
                body.put("images", List.of(imageRefs.get(0)));
                body.put("ratio", ratio);
                body.put("duration", clampNumber(config.getDuration(), 4, 15, 5));
                body.put("resolution", normalizeResolution(config.getResolution(), List.of("480p", "720p", "1080p"), "720p"));
                body.put("watermark", false);
            }
            case "seedanceImageToVideo" -> {
                if (imageRefs.isEmpty()) {
                    throw new RuntimeException(modelName + " 需要至少 1 张参考图");
                }
                if (shouldGenerateAudio) {
                    body.put("generate_audio", true);
                }
                body.put("images", imageRefs);
                body.put("ratio", ratio);
                body.put("duration", clampNumber(config.getDuration(), 4, 15, 5));
                body.put("resolution", normalizeResolution(config.getResolution(), List.of("480p", "720p", "1080p"), "720p"));
                body.put("watermark", false);
            }
            default -> {
                if (shouldGenerateAudio) {
                    body.put("generate_audio", true);
                }
                if (!imageRefs.isEmpty()) {
                    body.put("reference_images", imageRefs);
                }
                body.put("ratio", ratio);
                body.put("duration", clampNumber(config.getDuration(), 4, 15, 5));
                body.put("resolution", normalizeResolution(config.getResolution(), List.of("480p", "720p"), "720p"));
                body.put("watermark", false);
            }
        }
        return body;
    }

    // ============================================================
    // 轮询
    // ============================================================

    private String pollTask(String taskId, String apiKey, String mediaBaseUrl, String type,
                            long intervalMs, long timeoutMs) {
        String resultUrl = joinUrl(mediaBaseUrl, "/model/prediction/" + taskId);
        long deadline = System.currentTimeMillis() + timeoutMs;

        while (System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(intervalMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("轮询被中断");
            }

            JsonNode data = restClient.get()
                    .uri(resultUrl)
                    .header("Authorization", "Bearer " + apiKey)
                    .retrieve()
                    .body(JsonNode.class);

            String status = extractStatus(data);

            if (SUCCESS_STATUSES.contains(status)) {
                if ("image".equals(type)) {
                    String b64 = extractB64(data);
                    if (b64 != null) {
                        return b64;
                    }
                }
                String mediaUrl = extractUrl(data);
                if (mediaUrl != null) {
                    return mediaUrl;
                }
                throw new RuntimeException("image".equals(type)
                        ? "任务成功但未返回结果地址" : "任务成功但未返回视频地址");
            }
            if (FAILED_STATUSES.contains(status)) {
                String err = extractError(data);
                throw new RuntimeException(err != null ? err
                        : ("image".equals(type) ? "图片生成失败" : "视频生成失败"));
            }
            log.debug("AtlasCloud 任务 {} 状态: {}", taskId, status);
        }
        throw new RuntimeException("AtlasCloud 任务轮询超时");
    }

    private String truncate(JsonNode node) {
        String s = node == null ? "null" : node.toString();
        return s.length() > 500 ? s.substring(0, 500) : s;
    }
}
