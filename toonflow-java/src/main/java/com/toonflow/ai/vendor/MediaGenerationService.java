package com.toonflow.ai.vendor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.UUID;

/**
 * 图片/视频/语音生成服务
 * 对应原项目 ai.ts 中的 imageRequest / videoRequest / ttsRequest
 *
 * 采用 OpenAI 兼容的图片生成接口；不同厂商可按 modelId 前缀扩展分支。
 * 生成的媒体文件落盘到数据目录，返回相对 URL。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MediaGenerationService {

    private final VendorService vendorService;
    private final ObjectMapper objectMapper;

    @Value("${toonflow.data-dir}")
    private String dataDir;

    private final RestClient restClient = RestClient.create();

    /**
     * 图片生成
     * @param vendorModel 格式 vendorId:modelId
     * @param prompt 提示词
     * @return 生成图片的相对 URL
     */
    public String generateImage(String vendorModel, String prompt, String size) {
        String[] parts = vendorModel.split(":", 2);
        String vendorId = parts[0];
        String modelId = parts.length > 1 ? parts[1] : "";
        Map<String, String> inputs = vendorService.getInputs(vendorId);
        String apiKey = inputs.getOrDefault("apiKey", "");
        String baseUrl = inputs.getOrDefault("baseUrl", "https://api.openai.com");

        try {
            JsonNode response = restClient.post()
                    .uri(baseUrl + "/v1/images/generations")
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .body(Map.of(
                            "model", modelId,
                            "prompt", prompt,
                            "size", size != null ? size : "1024x1024",
                            "n", 1))
                    .retrieve()
                    .body(JsonNode.class);

            JsonNode dataNode = response != null ? response.path("data").path(0) : null;
            if (dataNode == null || dataNode.isMissingNode()) {
                throw new RuntimeException("图片生成响应为空");
            }

            // 支持 url 或 b64_json 两种返回
            if (dataNode.has("url")) {
                return downloadToLocal(dataNode.get("url").asText(), "oss", ".png");
            } else if (dataNode.has("b64_json")) {
                return saveBase64(dataNode.get("b64_json").asText(), "oss", ".png");
            }
            throw new RuntimeException("未识别的图片返回格式");
        } catch (Exception e) {
            log.error("图片生成失败: {}", e.getMessage());
            throw new RuntimeException("图片生成失败: " + e.getMessage(), e);
        }
    }

    /**
     * 视频生成（异步任务，返回任务标识，需轮询）
     * 不同厂商接口差异较大，此处提供通用提交骨架。
     */
    public String submitVideoTask(String vendorModel, String prompt, String imageUrl) {
        String[] parts = vendorModel.split(":", 2);
        String vendorId = parts[0];
        String modelId = parts.length > 1 ? parts[1] : "";
        Map<String, String> inputs = vendorService.getInputs(vendorId);
        String apiKey = inputs.getOrDefault("apiKey", "");
        String baseUrl = inputs.getOrDefault("baseUrl", "");

        try {
            JsonNode response = restClient.post()
                    .uri(baseUrl + "/v1/video/generations")
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .body(Map.of("model", modelId, "prompt", prompt,
                            "image", imageUrl != null ? imageUrl : ""))
                    .retrieve()
                    .body(JsonNode.class);
            return response != null && response.has("id") ? response.get("id").asText() : null;
        } catch (Exception e) {
            log.error("视频任务提交失败: {}", e.getMessage());
            throw new RuntimeException("视频任务提交失败: " + e.getMessage(), e);
        }
    }

    private String downloadToLocal(String url, String subDir, String ext) throws IOException, InterruptedException {
        HttpClient client = HttpClient.newHttpClient();
        HttpResponse<byte[]> resp = client.send(
                HttpRequest.newBuilder(URI.create(url)).GET().build(),
                HttpResponse.BodyHandlers.ofByteArray());
        return writeBytes(resp.body(), subDir, ext);
    }

    private String saveBase64(String b64, String subDir, String ext) throws IOException {
        byte[] bytes = java.util.Base64.getDecoder().decode(b64);
        return writeBytes(bytes, subDir, ext);
    }

    private String writeBytes(byte[] bytes, String subDir, String ext) throws IOException {
        String fileName = UUID.randomUUID() + ext;
        Path dir = Paths.get(dataDir, subDir);
        Files.createDirectories(dir);
        Files.write(dir.resolve(fileName), bytes);
        return "/" + subDir + "/" + fileName;
    }
}
