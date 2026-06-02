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
 * MiniMax（海螺AI）厂商适配器
 *
 * 移植自 vendor-reference/minimax.ts。
 *
 * 鉴权：Authorization: Bearer {apiKey}（去除已有的 "Bearer " 前缀）。
 *
 * 图片：POST {baseUrl}/v1/image_generation（同步，返回 base64）。
 *
 * 视频（异步多步）：
 *   1. 提交：POST {baseUrl}/v1/video_generation -> 返回 task_id
 *   2. 轮询：GET  {baseUrl}/v1/query/video_generation?task_id={taskId}
 *            status == "Success" -> file_id；status == "Fail" -> 失败
 *   3. 取址：GET  {baseUrl}/v1/files/retrieve?file_id={fileId} -> file.download_url
 *
 * 用 @Component("minimax")，vendorId 与 o_vendorConfig.id 对应。
 */
@Slf4j
@Component("minimax")
public class MinimaxAdapter implements VendorAdapter {

    private static final String DEFAULT_BASE_URL = "https://api.minimaxi.com";
    private static final int MAX_POLL = 120;          // 600000ms / 5000ms
    private static final long POLL_INTERVAL_MS = 5000;

    private final RestClient restClient = RestClient.create();

    @Override
    public String vendorId() {
        return "minimax";
    }

    private String resolveApiKey(Map<String, String> inputs) {
        String apiKey = inputs.getOrDefault("apiKey", "");
        if (apiKey == null || apiKey.isEmpty()) {
            throw new RuntimeException("缺少API Key");
        }
        return apiKey.replaceFirst("(?i)^Bearer\\s+", "");
    }

    private String resolveBaseUrl(Map<String, String> inputs) {
        String baseUrl = inputs.getOrDefault("baseUrl", DEFAULT_BASE_URL);
        if (baseUrl == null || baseUrl.isEmpty()) {
            baseUrl = DEFAULT_BASE_URL;
        }
        return baseUrl.replaceAll("/$", "");
    }

    private static String extractBase64WithHead(String base64) {
        return base64.startsWith("data:") ? base64 : "data:image/png;base64," + base64;
    }

    @Override
    public String imageRequest(ImageConfig config, Map<String, String> inputs) {
        String apiKey = resolveApiKey(inputs);
        String baseUrl = resolveBaseUrl(inputs);

        Map<String, Object> reqBody = new HashMap<>();
        reqBody.put("model", config.getModelId() != null ? config.getModelId() : "image-01");
        reqBody.put("prompt", config.getPrompt());
        reqBody.put("aspect_ratio", config.getRatio());
        reqBody.put("response_format", "base64");
        reqBody.put("n", 1);
        reqBody.put("prompt_optimizer", true);
        reqBody.put("aigc_watermark", false);

        List<String> refs = config.getReferences();
        if (refs != null && !refs.isEmpty()) {
            String refBase64 = extractBase64WithHead(refs.get(0));
            Map<String, Object> subject = new HashMap<>();
            subject.put("type", "character");
            subject.put("image_file", refBase64);
            reqBody.put("subject_reference", List.of(subject));
        }

        log.info("开始提交MiniMax图像生成任务");
        JsonNode resp = restClient.post()
                .uri(baseUrl + "/v1/image_generation")
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .body(reqBody)
                .retrieve()
                .body(JsonNode.class);

        if (resp == null || resp.path("base_resp").path("status_code").asInt(-1) != 0) {
            String msg = resp != null ? resp.path("base_resp").path("status_msg").asText("") : "无响应";
            throw new RuntimeException("图像生成失败：" + msg);
        }
        if (resp.path("metadata").path("success_count").asInt(0) == 0) {
            throw new RuntimeException("图像生成被安全策略拦截，请调整prompt或参考图");
        }

        JsonNode imageArr = resp.path("data").path("image_base64");
        if (!imageArr.isArray() || imageArr.isEmpty()) {
            throw new RuntimeException("图像生成成功但无结果");
        }
        String imgBase64 = imageArr.get(0).asText();
        return imgBase64.startsWith("data:") ? imgBase64 : "data:image/png;base64," + imgBase64;
    }

    @Override
    public String videoRequest(VideoConfig config, Map<String, String> inputs) {
        String apiKey = resolveApiKey(inputs);
        String baseUrl = resolveBaseUrl(inputs);

        Map<String, Object> reqBody = new HashMap<>();
        reqBody.put("model", config.getModelId() != null ? config.getModelId() : "MiniMax-Hailuo-02");
        reqBody.put("prompt", config.getPrompt());
        reqBody.put("duration", config.getDuration());
        reqBody.put("resolution", config.getResolution());
        reqBody.put("aigc_watermark", false);
        reqBody.put("prompt_optimizer", true);

        // 提取图片类型的引用（这里 referenceList 已是 base64 列表）
        List<String> imageRefs = new ArrayList<>();
        if (config.getReferenceList() != null) {
            for (String ref : config.getReferenceList()) {
                if (ref != null && !ref.isEmpty()) {
                    imageRefs.add(extractBase64WithHead(ref));
                }
            }
        }

        if (imageRefs.size() >= 2) {
            // 首尾帧模式
            reqBody.put("first_frame_image", imageRefs.get(0));
            reqBody.put("last_frame_image", imageRefs.get(1));
        } else if (imageRefs.size() == 1) {
            // 单图（图生视频）模式
            reqBody.put("first_frame_image", imageRefs.get(0));
        }

        // 1. 提交任务
        log.info("开始提交MiniMax视频生成任务");
        JsonNode submitResp = restClient.post()
                .uri(baseUrl + "/v1/video_generation")
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .body(reqBody)
                .retrieve()
                .body(JsonNode.class);

        if (submitResp == null || submitResp.path("base_resp").path("status_code").asInt(-1) != 0) {
            String msg = submitResp != null
                    ? submitResp.path("base_resp").path("status_msg").asText("") : "无响应";
            throw new RuntimeException("任务提交失败：" + msg);
        }
        String taskId = submitResp.path("task_id").asText(null);
        if (taskId == null || taskId.isEmpty()) {
            throw new RuntimeException("任务提交失败：未返回 task_id");
        }
        log.info("视频任务提交成功，任务ID: {}", taskId);

        // 2. 轮询任务状态获取 file_id
        String fileId = pollTask(taskId, apiKey, baseUrl);
        log.info("视频任务生成成功，文件ID: {}", fileId);

        // 3. 获取下载地址
        JsonNode fileResp = restClient.get()
                .uri(baseUrl + "/v1/files/retrieve?file_id=" + fileId)
                .header("Authorization", "Bearer " + apiKey)
                .retrieve()
                .body(JsonNode.class);

        if (fileResp == null || fileResp.path("base_resp").path("status_code").asInt(-1) != 0) {
            String msg = fileResp != null
                    ? fileResp.path("base_resp").path("status_msg").asText("") : "无响应";
            throw new RuntimeException("获取文件地址失败：" + msg);
        }
        String downloadUrl = fileResp.path("file").path("download_url").asText(null);
        if (downloadUrl == null || downloadUrl.isEmpty()) {
            throw new RuntimeException("获取文件地址失败：未返回 download_url");
        }
        log.info("视频下载地址获取成功: {}", downloadUrl);
        return downloadUrl;
    }

    /**
     * 轮询 MiniMax 视频任务直到完成，返回 file_id。
     */
    private String pollTask(String taskId, String apiKey, String baseUrl) {
        for (int i = 0; i < MAX_POLL; i++) {
            try {
                Thread.sleep(POLL_INTERVAL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("轮询被中断");
            }

            JsonNode queryResp = restClient.get()
                    .uri(baseUrl + "/v1/query/video_generation?task_id=" + taskId)
                    .header("Authorization", "Bearer " + apiKey)
                    .retrieve()
                    .body(JsonNode.class);

            if (queryResp == null || queryResp.path("base_resp").path("status_code").asInt(-1) != 0) {
                String msg = queryResp != null
                        ? queryResp.path("base_resp").path("status_msg").asText("") : "无响应";
                throw new RuntimeException(msg);
            }

            String status = queryResp.path("status").asText("");
            switch (status) {
                case "Success" -> {
                    return queryResp.path("file_id").asText();
                }
                case "Fail" -> throw new RuntimeException("视频生成失败");
                default -> log.debug("视频任务生成中，当前状态：{} ({}/{})", status, i + 1, MAX_POLL);
            }
        }
        throw new RuntimeException("MiniMax视频任务轮询超时");
    }
}
