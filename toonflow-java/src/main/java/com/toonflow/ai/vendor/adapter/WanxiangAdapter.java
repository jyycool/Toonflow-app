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
 * 通义万相（阿里云 DashScope）厂商适配器
 *
 * 演示「异步任务 + 轮询」模式，对应原项目厂商模板里 imageRequest/videoRequest
 * 自行处理提交任务与轮询的逻辑。
 *
 * 图片：POST /api/v1/services/aigc/text2image/image-synthesis（异步）
 * 轮询：GET  /api/v1/tasks/{task_id}
 *
 * 用 @Component("wanxiang")，vendorId 与 o_vendorConfig.id 对应。
 */
@Slf4j
@Component("wanxiang")
public class WanxiangAdapter implements VendorAdapter {

    private static final String BASE = "https://dashscope.aliyuncs.com";
    private static final int MAX_POLL = 60;
    private static final long POLL_INTERVAL_MS = 3000;

    private final RestClient restClient = RestClient.create();

    @Override
    public String vendorId() {
        return "wanxiang";
    }

    @Override
    public String imageRequest(ImageConfig config, Map<String, String> inputs) {
        String apiKey = inputs.getOrDefault("apiKey", "");
        String size = config.getResolution() != null
                ? config.getResolution().replace("x", "*") : "1024*1024";

        // 1. 提交异步任务
        Map<String, Object> input = new HashMap<>();
        input.put("prompt", config.getPrompt());
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("size", size);
        parameters.put("n", 1);

        Map<String, Object> reqBody = new HashMap<>();
        reqBody.put("model", config.getModelId() != null ? config.getModelId() : "wanx-v1");
        reqBody.put("input", input);
        reqBody.put("parameters", parameters);

        JsonNode submitResp = restClient.post()
                .uri(BASE + "/api/v1/services/aigc/text2image/image-synthesis")
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .header("X-DashScope-Async", "enable")
                .body(reqBody)
                .retrieve()
                .body(JsonNode.class);

        String taskId = submitResp != null
                ? submitResp.path("output").path("task_id").asText(null) : null;
        if (taskId == null) {
            throw new RuntimeException("通义万相任务提交失败: " + submitResp);
        }

        // 2. 轮询任务结果
        return pollTask(taskId, apiKey, "image");
    }

    @Override
    public String videoRequest(VideoConfig config, Map<String, String> inputs) {
        String apiKey = inputs.getOrDefault("apiKey", "");

        Map<String, Object> input = new HashMap<>();
        input.put("prompt", config.getPrompt());
        if (config.getReferenceList() != null && !config.getReferenceList().isEmpty()) {
            input.put("img_url", config.getReferenceList().get(0));
        }
        Map<String, Object> reqBody = new HashMap<>();
        reqBody.put("model", config.getModelId() != null ? config.getModelId() : "wanx2.1-t2v-turbo");
        reqBody.put("input", input);

        JsonNode submitResp = restClient.post()
                .uri(BASE + "/api/v1/services/aigc/video-generation/video-synthesis")
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .header("X-DashScope-Async", "enable")
                .body(reqBody)
                .retrieve()
                .body(JsonNode.class);

        String taskId = submitResp != null
                ? submitResp.path("output").path("task_id").asText(null) : null;
        if (taskId == null) {
            throw new RuntimeException("通义万相视频任务提交失败: " + submitResp);
        }
        return pollTask(taskId, apiKey, "video");
    }

    /**
     * 轮询 DashScope 异步任务直到完成
     */
    private String pollTask(String taskId, String apiKey, String type) {
        for (int i = 0; i < MAX_POLL; i++) {
            try {
                Thread.sleep(POLL_INTERVAL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("轮询被中断");
            }

            JsonNode resp = restClient.get()
                    .uri(BASE + "/api/v1/tasks/" + taskId)
                    .header("Authorization", "Bearer " + apiKey)
                    .retrieve()
                    .body(JsonNode.class);

            String status = resp != null
                    ? resp.path("output").path("task_status").asText("") : "";
            switch (status) {
                case "SUCCEEDED" -> {
                    JsonNode output = resp.path("output");
                    if ("video".equals(type)) {
                        return output.path("video_url").asText();
                    }
                    JsonNode results = output.path("results");
                    if (results.isArray() && results.size() > 0) {
                        return results.get(0).path("url").asText();
                    }
                    throw new RuntimeException("任务成功但无结果");
                }
                case "FAILED" -> throw new RuntimeException("通义万相任务失败: "
                        + resp.path("output").path("message").asText(""));
                default -> log.debug("通义万相任务 {} 状态: {} ({}/{})", taskId, status, i + 1, MAX_POLL);
            }
        }
        throw new RuntimeException("通义万相任务轮询超时");
    }
}
