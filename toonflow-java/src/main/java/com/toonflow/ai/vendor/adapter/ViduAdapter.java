package com.toonflow.ai.vendor.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.toonflow.ai.vendor.VendorAdapter;
import com.toonflow.ai.vendor.dto.ImageConfig;
import com.toonflow.ai.vendor.dto.VideoConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Vidu 开放平台厂商适配器
 *
 * 由 vidu.ts 移植而来。鉴权使用 Authorization: Token {apiKey}。
 *
 * 图片：POST {baseUrl}/reference2image（异步），轮询 GET {baseUrl}/tasks/{id}/creations
 * 视频：POST {baseUrl}/start-end2video（异步），轮询同上
 *
 * 用 @Component("vidu")，vendorId 与 o_vendorConfig.id 对应。
 */
@Slf4j
@Component("vidu")
public class ViduAdapter implements VendorAdapter {

    private static final String DEFAULT_BASE_URL = "https://api.vidu.cn/ent/v2";
    private static final int MAX_POLL = 120;
    private static final long POLL_INTERVAL_MS = 3000;

    private final RestClient restClient = RestClient.create();

    @Override
    public String vendorId() {
        return "vidu";
    }

    @Override
    public String imageRequest(ImageConfig config, Map<String, String> inputs) {
        String baseUrl = inputs.getOrDefault("baseUrl", DEFAULT_BASE_URL);
        String apiKey = resolveApiKey(inputs);

        // size: 1K -> 2K，其余保持原值
        String size = config.getResolution();
        if ("1K".equals(size)) {
            size = "2K";
        }

        // aspect_ratio 尺寸映射
        Map<String, Map<String, String>> sizeMap = new HashMap<>();
        Map<String, String> r169 = new HashMap<>();
        r169.put("1k", "1920x1080");
        r169.put("2K", "2848x1600");
        r169.put("4K", "4096x2304");
        sizeMap.put("16:9", r169);
        Map<String, String> r916 = new HashMap<>();
        r916.put("1k", "1920x1080");
        r916.put("2K", "1600x2848");
        r916.put("4K", "2304x4096");
        sizeMap.put("9:16", r916);

        String ratio = config.getRatio();
        String aspectRatio = null;
        Map<String, String> ratioMap = sizeMap.get(ratio);
        if (ratioMap != null) {
            aspectRatio = ratioMap.get(size);
        }

        Map<String, Object> body = new HashMap<>();
        body.put("model", config.getModelId());
        body.put("prompt", config.getPrompt());
        body.put("aspect_ratio", aspectRatio);
        body.put("seed", 0);
        body.put("resolution", size);
        List<String> references = config.getReferences();
        if (references != null && !references.isEmpty()) {
            body.put("image", references);
        }

        JsonNode submitResp = restClient.post()
                .uri(baseUrl + "/reference2image")
                .header("Authorization", "Token " + apiKey)
                .header("Content-Type", "application/json")
                .body(body)
                .retrieve()
                .body(JsonNode.class);

        String taskId = submitResp != null ? submitResp.path("task_id").asText(null) : null;
        if (taskId == null) {
            throw new RuntimeException("Vidu 图片任务提交失败: " + submitResp);
        }

        JsonNode creations = checkTaskResult(baseUrl, apiKey, taskId);
        if (creations == null || !creations.isArray() || creations.size() == 0) {
            throw new RuntimeException("图片未能生成");
        }
        return creations.get(0).path("url").asText();
    }

    @Override
    public String videoRequest(VideoConfig config, Map<String, String> inputs) {
        String baseUrl = inputs.getOrDefault("baseUrl", DEFAULT_BASE_URL);
        String apiKey = resolveApiKey(inputs);

        // 构建 metadata（vidu）
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("aspect_ratio", config.getAspectRatio());
        metadata.put("audio", config.getAudio() != null ? config.getAudio() : false);
        metadata.put("off_peak", false);

        // 公共请求参数
        Map<String, Object> body = new HashMap<>();
        body.put("model", config.getModelId());
        List<String> references = config.getReferenceList();
        if (references != null && !references.isEmpty()) {
            body.put("images", references);
        }
        body.put("prompt", config.getPrompt());
        body.put("size", config.getResolution());
        body.put("duration", config.getDuration());
        body.put("metadata", metadata);

        JsonNode submitResp = restClient.post()
                .uri(baseUrl + "/start-end2video")
                .header("Authorization", "Token " + apiKey)
                .header("Content-Type", "application/json")
                .body(body)
                .retrieve()
                .body(JsonNode.class);

        String taskId = submitResp != null ? submitResp.path("id").asText(null) : null;
        if (taskId == null) {
            throw new RuntimeException("Vidu 视频任务提交失败: " + submitResp);
        }

        JsonNode creations = checkTaskResult(baseUrl, apiKey, taskId);
        if (creations == null || !creations.isArray() || creations.size() == 0) {
            throw new RuntimeException("视频未能生成");
        }
        return creations.get(0).path("url").asText();
    }

    /**
     * apiKey 去掉可能存在的 "Token " 前缀（对应 TS replace("Token ", "")）
     */
    private String resolveApiKey(Map<String, String> inputs) {
        String apiKey = inputs.getOrDefault("apiKey", "");
        if (apiKey.isEmpty()) {
            throw new RuntimeException("缺少API Key");
        }
        return apiKey.replace("Token ", "");
    }

    /**
     * 轮询 Vidu 异步任务直到完成，返回 creations 节点
     * 轮询地址：GET {baseUrl}/tasks/{id}/creations
     */
    private JsonNode checkTaskResult(String baseUrl, String apiKey, String taskId) {
        String queryUrl = baseUrl + "/tasks/" + taskId + "/creations";
        for (int i = 0; i < MAX_POLL; i++) {
            try {
                Thread.sleep(POLL_INTERVAL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("轮询被中断");
            }

            JsonNode resp = restClient.get()
                    .uri(queryUrl)
                    .header("Authorization", "Token " + apiKey)
                    .header("Content-Type", "application/json")
                    .retrieve()
                    .body(JsonNode.class);

            String status = "";
            String failReason = "生成失败";
            if (resp != null) {
                if (resp.hasNonNull("state")) {
                    status = resp.path("state").asText("");
                } else {
                    status = resp.path("data").path("state").asText("");
                }
                if (resp.path("data").hasNonNull("err_code")) {
                    failReason = resp.path("data").path("err_code").asText(failReason);
                }
            }

            switch (status) {
                case "completed", "SUCCESS", "success" -> {
                    return resp.path("creations");
                }
                case "FAILURE", "failed" -> throw new RuntimeException(failReason);
                default -> log.debug("Vidu 任务 {} 状态: {} ({}/{})", taskId, status, i + 1, MAX_POLL);
            }
        }
        throw new RuntimeException("Vidu 任务轮询超时");
    }
}
