package com.toonflow.ai.vendor.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.toonflow.ai.vendor.VendorAdapter;
import com.toonflow.ai.vendor.dto.ImageConfig;
import com.toonflow.ai.vendor.dto.VideoConfig;
import io.jsonwebtoken.Jwts;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 可灵AI（klingai）厂商适配器
 *
 * 移植自 TS 模板 klingai.ts。可灵AI使用 JWT(HS256) 鉴权，
 * 由 accessKey(iss)/secretKey(签名) 生成 Bearer Token。
 *
 * 提交（异步）：POST {baseUrl}/v1/videos/{text2video|image2video|multi-image2video|omni-video}
 * 轮询：       GET  {baseUrl}/v1/videos/{...}/{task_id}
 * 轮询间隔 5s，最长 600s。任务状态 succeed/failed。
 * 最终视频地址：data.task_result.videos[0].url
 *
 * 用 @Component("klingai")，vendorId 与 o_vendorConfig.id 对应。
 */
@Slf4j
@Component("klingai")
public class KlingaiAdapter implements VendorAdapter {

    private static final String DEFAULT_BASE = "https://api-beijing.klingai.com";
    private static final int MAX_POLL = 120;
    private static final long POLL_INTERVAL_MS = 5000;

    private final RestClient restClient = RestClient.create();

    @Override
    public String vendorId() {
        return "klingai";
    }

    @Override
    public String imageRequest(ImageConfig config, Map<String, String> inputs) {
        // 对应 TS：imageRequest 抛错——可灵AI不支持图片模型
        throw new UnsupportedOperationException("可灵AI不支持图片模型");
    }

    @Override
    public String videoRequest(VideoConfig config, Map<String, String> inputs) {
        String accessKey = inputs.getOrDefault("accessKey", "");
        String secretKey = inputs.getOrDefault("secretKey", "");
        if (accessKey.isEmpty()) throw new RuntimeException("缺少Access Key");
        if (secretKey.isEmpty()) throw new RuntimeException("缺少Secret Key");

        String baseUrl = inputs.getOrDefault("baseUrl", "");
        if (baseUrl == null || baseUrl.isEmpty()) baseUrl = DEFAULT_BASE;

        // 解析 modelId，格式：kling-video-o1:pro => modelName=kling-video-o1, mode=pro
        String rawModel = config.getModelId() != null ? config.getModelId() : "kling-v1:std";
        int colonIdx = rawModel.indexOf(':');
        String modelName = colonIdx > -1 ? rawModel.substring(0, colonIdx) : rawModel;
        String mode = colonIdx > -1 ? rawModel.substring(colonIdx + 1) : "pro";

        boolean isOmniModel = "kling-video-o1".equals(modelName) || "kling-v3-omni".equals(modelName);

        List<String> refs = config.getReferenceList() != null
                ? config.getReferenceList() : new ArrayList<>();
        String prompt = config.getPrompt();
        String duration = String.valueOf(config.getDuration() != null ? config.getDuration() : 5);
        String aspectRatio = config.getAspectRatio() != null ? config.getAspectRatio() : "16:9";
        boolean sound = Boolean.TRUE.equals(config.getAudio());

        // =====================================================
        // Omni 模型 —— /v1/videos/omni-video
        // =====================================================
        if (isOmniModel) {
            Map<String, Object> body = new HashMap<>();
            body.put("model_name", modelName);
            body.put("mode", mode);
            body.put("duration", duration);
            body.put("sound", sound ? "on" : "off");
            if (prompt != null && !prompt.isEmpty()) body.put("prompt", prompt);

            if (!refs.isEmpty()) {
                List<Map<String, Object>> imageList = new ArrayList<>();
                if (refs.size() == 1) {
                    imageList.add(imageEntry(extractImageUrl(refs.get(0)), "first_frame"));
                    if (!body.containsKey("prompt")) body.put("prompt", "根据图片生成视频");
                } else {
                    // 首帧 + 尾帧
                    imageList.add(imageEntry(extractImageUrl(refs.get(0)), "first_frame"));
                    imageList.add(imageEntry(extractImageUrl(refs.get(1)), "end_frame"));
                    if (!body.containsKey("prompt")) body.put("prompt", "根据首尾帧图片生成过渡视频");
                }
                body.put("image_list", imageList);
            } else {
                body.put("aspect_ratio", aspectRatio);
                if (!body.containsKey("prompt")) throw new RuntimeException("文生视频模式需要提供提示词");
            }

            String url = baseUrl + "/v1/videos/omni-video";
            return submitAndPoll(url, url, body, accessKey, secretKey);
        }

        // =====================================================
        // 多图参考模式 —— /v1/videos/multi-image2video（>=2 张参考图）
        // =====================================================
        if (refs.size() >= 2) {
            List<Map<String, Object>> imageList = new ArrayList<>();
            for (String ref : refs) {
                Map<String, Object> e = new HashMap<>();
                e.put("image", extractRawBase64(ref));
                imageList.add(e);
            }
            Map<String, Object> body = new HashMap<>();
            body.put("model_name", modelName);
            body.put("image_list", imageList);
            body.put("prompt", (prompt != null && !prompt.isEmpty()) ? prompt : "根据参考图片生成视频");
            body.put("mode", mode);
            body.put("duration", duration);
            body.put("aspect_ratio", aspectRatio);

            String url = baseUrl + "/v1/videos/multi-image2video";
            return submitAndPoll(url, url, body, accessKey, secretKey);
        }

        // =====================================================
        // 图生视频模式（单图）—— /v1/videos/image2video
        // =====================================================
        if (refs.size() == 1) {
            Map<String, Object> body = new HashMap<>();
            body.put("model_name", modelName);
            body.put("prompt", (prompt != null && !prompt.isEmpty()) ? prompt : "根据图片生成视频");
            body.put("mode", mode);
            body.put("duration", duration);
            body.put("sound", sound ? "on" : "off");
            body.put("image", extractRawBase64(refs.get(0)));

            String url = baseUrl + "/v1/videos/image2video";
            return submitAndPoll(url, url, body, accessKey, secretKey);
        }

        // =====================================================
        // 文生视频模式 —— /v1/videos/text2video
        // =====================================================
        if (prompt == null || prompt.isEmpty()) throw new RuntimeException("文生视频模式需要提供提示词");
        Map<String, Object> body = new HashMap<>();
        body.put("model_name", modelName);
        body.put("prompt", prompt);
        body.put("mode", mode);
        body.put("duration", duration);
        body.put("aspect_ratio", aspectRatio);
        body.put("sound", sound ? "on" : "off");

        String url = baseUrl + "/v1/videos/text2video";
        return submitAndPoll(url, url, body, accessKey, secretKey);
    }

    // ============================================================
    // 辅助工具
    // ============================================================

    private Map<String, Object> imageEntry(String imageUrl, String type) {
        Map<String, Object> e = new HashMap<>();
        e.put("image_url", imageUrl);
        if (type != null) e.put("type", type);
        return e;
    }

    /** 去掉 data: 前缀，返回纯 base64 */
    private String extractRawBase64(String ref) {
        if (ref == null) return null;
        return ref.replaceFirst("^data:[^;]+;base64,", "");
    }

    /** omni-video 接口的 image_url 支持带前缀 base64 或 url */
    private String extractImageUrl(String ref) {
        if (ref == null) return null;
        if (ref.startsWith("data:") || ref.startsWith("http")) return ref;
        return "data:image/jpeg;base64," + ref;
    }

    /**
     * 生成可灵AI的JWT鉴权Token（HS256）
     * payload: iss=accessKey, exp=now+1800, nbf=now-5
     */
    private String generateAuthToken(String accessKey, String secretKey) {
        long now = System.currentTimeMillis() / 1000L;
        SecretKey key = new SecretKeySpec(secretKey.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
        return Jwts.builder()
                .header().add("typ", "JWT").and()
                .claim("iss", accessKey)
                .expiration(new Date((now + 1800) * 1000))
                .notBefore(new Date((now - 5) * 1000))
                .signWith(key, Jwts.SIG.HS256)
                .compact();
    }

    /**
     * 提交任务并轮询获取结果
     * 提交成功条件 code==0；任务状态 succeed/failed；
     * 视频地址：data.task_result.videos[0].url
     */
    private String submitAndPoll(String submitUrl, String queryUrlBase,
                                 Map<String, Object> requestBody,
                                 String accessKey, String secretKey) {
        String token = generateAuthToken(accessKey, secretKey);
        log.info("开始提交可灵AI视频生成任务: {}", submitUrl);

        JsonNode submitResp = restClient.post()
                .uri(submitUrl)
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + token)
                .body(requestBody)
                .retrieve()
                .body(JsonNode.class);

        if (submitResp == null || submitResp.path("code").asInt(-1) != 0) {
            String msg = submitResp != null ? submitResp.path("message").asText() : "无响应";
            throw new RuntimeException("提交任务失败: " + msg);
        }

        String taskId = submitResp.path("data").path("task_id").asText(null);
        if (taskId == null) {
            throw new RuntimeException("提交任务失败: 未获取到 task_id");
        }
        log.info("任务已提交，任务ID: {}", taskId);

        for (int i = 0; i < MAX_POLL; i++) {
            try {
                Thread.sleep(POLL_INTERVAL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("轮询被中断");
            }

            String freshToken = generateAuthToken(accessKey, secretKey);
            JsonNode queryResp = restClient.get()
                    .uri(queryUrlBase + "/" + taskId)
                    .header("Authorization", "Bearer " + freshToken)
                    .retrieve()
                    .body(JsonNode.class);

            if (queryResp == null || queryResp.path("code").asInt(-1) != 0) {
                String msg = queryResp != null ? queryResp.path("message").asText() : "无响应";
                throw new RuntimeException("查询任务失败: " + msg);
            }

            JsonNode taskData = queryResp.path("data");
            String status = taskData.path("task_status").asText("");
            log.debug("轮询中... 任务状态: {} ({}/{})", status, i + 1, MAX_POLL);

            if ("succeed".equals(status)) {
                String videoUrl = taskData.path("task_result").path("videos")
                        .path(0).path("url").asText(null);
                if (videoUrl == null || videoUrl.isEmpty()) {
                    throw new RuntimeException("任务完成但未获取到视频URL");
                }
                log.info("视频生成完成: {}", videoUrl);
                return videoUrl;
            }

            if ("failed".equals(status)) {
                String msg = taskData.path("task_status_msg").asText("未知错误");
                throw new RuntimeException("视频生成失败: " + msg);
            }
        }
        throw new RuntimeException("可灵AI任务轮询超时");
    }
}
