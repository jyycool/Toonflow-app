package com.toonflow.ai.vendor;

import com.fasterxml.jackson.databind.JsonNode;
import com.toonflow.ai.vendor.dto.ImageConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.HashMap;
import java.util.Map;

/**
 * OpenAI 兼容厂商适配器（默认/回退适配器）
 *
 * 适用于所有遵循 OpenAI /v1/images/generations 协议的厂商。
 * 未注册专用适配器的 vendorId 都会回退到这里。
 */
@Slf4j
@Component
public class OpenAiCompatibleAdapter implements VendorAdapter {

    private final RestClient restClient = RestClient.create();

    @Override
    public String vendorId() {
        return "openai-compatible";
    }

    @Override
    public String imageRequest(ImageConfig config, Map<String, String> inputs) {
        String apiKey = inputs.getOrDefault("apiKey", "");
        String baseUrl = inputs.getOrDefault("baseUrl", "https://api.openai.com");
        String size = config.getResolution() != null ? config.getResolution() : "1024x1024";

        Map<String, Object> reqBody = new HashMap<>();
        reqBody.put("model", config.getModelId());
        reqBody.put("prompt", config.getPrompt());
        reqBody.put("size", size);
        reqBody.put("n", 1);

        JsonNode response = restClient.post()
                .uri(baseUrl + "/v1/images/generations")
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .body(reqBody)
                .retrieve()
                .body(JsonNode.class);

        JsonNode data = response != null ? response.path("data").path(0) : null;
        if (data == null || data.isMissingNode()) {
            throw new RuntimeException("图片生成响应为空");
        }
        if (data.has("url")) return data.get("url").asText();
        if (data.has("b64_json")) return "data:image/png;base64," + data.get("b64_json").asText();
        throw new RuntimeException("未识别的图片返回格式");
    }
}
