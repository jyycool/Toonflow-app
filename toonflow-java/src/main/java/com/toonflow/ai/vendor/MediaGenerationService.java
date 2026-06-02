package com.toonflow.ai.vendor;

import com.toonflow.ai.vendor.dto.ImageConfig;
import com.toonflow.ai.vendor.dto.VideoConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 图片/视频/语音生成服务
 * 对应原项目 ai.ts 中的 imageRequest / videoRequest / ttsRequest
 *
 * 通过 VendorAdapterRegistry 按 vendorId 路由到对应厂商适配器，
 * 适配器返回 URL 或 base64，本服务负责统一落盘并返回相对 URL。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MediaGenerationService {

    private final VendorService vendorService;
    private final VendorAdapterRegistry adapterRegistry;

    @Value("${toonflow.data-dir}")
    private String dataDir;

    /**
     * 图片生成
     * @param vendorModel 格式 vendorId:modelId
     * @return 生成图片的相对 URL（落盘后）
     */
    public String generateImage(String vendorModel, String prompt, String size) {
        String[] parts = vendorModel.split(":", 2);
        String vendorId = parts[0];
        String modelId = parts.length > 1 ? parts[1] : "";
        Map<String, String> inputs = vendorService.getInputs(vendorId);

        ImageConfig config = new ImageConfig();
        config.setPrompt(prompt);
        config.setResolution(size != null ? size : "1024x1024");
        config.setModelId(modelId);

        try {
            VendorAdapter adapter = adapterRegistry.get(vendorId);
            String result = adapter.imageRequest(config, inputs);
            return persist(result, "oss", ".png");
        } catch (Exception e) {
            log.error("图片生成失败: {}", e.getMessage());
            throw new RuntimeException("图片生成失败: " + e.getMessage(), e);
        }
    }

    /**
     * 视频生成（适配器内部完成提交+轮询，返回最终地址）
     * @param vendorModel 格式 vendorId:modelId
     * @return 生成视频的相对 URL（落盘后）
     */
    public String generateVideo(String vendorModel, String prompt, String imageUrl, String aspectRatio) {
        return generateVideo(vendorModel, prompt, imageUrl, aspectRatio, null, null);
    }

    public String generateVideo(String vendorModel, String prompt, String imageUrl, String aspectRatio,
                                Integer duration, String resolution) {
        String[] parts = vendorModel.split(":", 2);
        String vendorId = parts[0];
        String modelId = parts.length > 1 ? parts[1] : "";
        Map<String, String> inputs = vendorService.getInputs(vendorId);

        VideoConfig config = new VideoConfig();
        config.setPrompt(prompt);
        config.setModelId(modelId);
        config.setAspectRatio(aspectRatio != null ? aspectRatio : "16:9");
        if (duration != null) config.setDuration(duration);
        if (resolution != null) config.setResolution(resolution);
        if (imageUrl != null) config.setReferenceList(List.of(imageUrl));

        try {
            VendorAdapter adapter = adapterRegistry.get(vendorId);
            String result = adapter.videoRequest(config, inputs);
            return persist(result, "oss", ".mp4");
        } catch (Exception e) {
            log.error("视频生成失败: {}", e.getMessage());
            throw new RuntimeException("视频生成失败: " + e.getMessage(), e);
        }
    }

    /**
     * 兼容旧调用：视频任务提交（同步等待返回最终地址）
     * @deprecated 改用 generateVideo
     */
    @Deprecated
    public String submitVideoTask(String vendorModel, String prompt, String imageUrl) {
        return generateVideo(vendorModel, prompt, imageUrl, "16:9");
    }

    /**
     * 将适配器返回结果（url 或 base64）落盘，返回相对 URL
     */
    private String persist(String result, String subDir, String ext) throws IOException, InterruptedException {
        if (result == null || result.isEmpty()) {
            throw new RuntimeException("生成结果为空");
        }
        if (result.startsWith("http://") || result.startsWith("https://")) {
            return downloadToLocal(result, subDir, ext);
        }
        // data:image/png;base64,xxx 或纯 base64
        String b64 = result.contains(",") ? result.substring(result.indexOf(',') + 1) : result;
        return saveBase64(b64, subDir, ext);
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
