package com.toonflow.controller;

import com.baomidou.mybatisplus.annotation.IdType;
import com.toonflow.common.result.R;
import com.toonflow.entity.OAssets;
import com.toonflow.entity.OImage;
import com.toonflow.mapper.OAssetsMapper;
import com.toonflow.mapper.OImageMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class FileController {

    @Value("${toonflow.data-dir}")
    private String dataDir;

    private final OAssetsMapper assetsMapper;
    private final OImageMapper imageMapper;

    /**
     * TS uploadClip accepts JSON with base64Data, creates o_assets + o_image records.
     */
    @PostMapping("/assets/uploadClip")
    public R<String> uploadClip(@RequestBody Map<String, Object> body) throws IOException {
        String projectId = body.get("projectId") != null ? body.get("projectId").toString() : null;
        String base64Data = (String) body.get("base64Data");
        String type = body.get("type") != null ? body.get("type").toString() : "clip";
        String name = body.get("name") != null ? body.get("name").toString() : "";

        String ext = getExtFromBase64(base64Data);
        String savePath = "/" + projectId + "/assets/" + UUID.randomUUID() + "." + ext;
        saveBase64File(base64Data, savePath);

        OAssets asset = new OAssets();
        asset.setType(type);
        asset.setProjectId(projectId);
        asset.setName(name);
        asset.setStartTime(System.currentTimeMillis());
        assetsMapper.insert(asset);

        OImage image = new OImage();
        image.setFilePath(savePath);
        image.setType(type);
        image.setAssetsId(asset.getId());
        image.setState("已完成");
        imageMapper.insert(image);

        asset.setImageId(image.getId());
        assetsMapper.updateById(asset);

        return R.ok("上传成功");
    }

    /**
     * TS uploadImage accepts JSON with base64Data, returns the saved path.
     */
    @PostMapping("/production/editImage/uploadImage")
    public R<String> uploadImage(@RequestBody Map<String, Object> body) throws IOException {
        String projectId = body.get("projectId") != null ? body.get("projectId").toString() : null;
        String scriptId = body.get("scriptId") != null ? body.get("scriptId").toString() : null;
        String base64Data = (String) body.get("base64Data");

        String ext = getExtFromBase64(base64Data);
        if (!java.util.Set.of("jpeg", "jpg", "png").contains(ext)) {
            throw new com.toonflow.common.exception.BusinessException("不支持的文件类型");
        }
        String savePath = "/" + projectId + "/imageFlow/" + scriptId + "/" + UUID.randomUUID() + "." + ext;
        saveBase64File(base64Data, savePath);
        return R.ok(savePath);
    }

    @PostMapping("/common/getBigImage")
    public R<Map<String, String>> getBigImage(@RequestBody Map<String, String> body) {
        return R.ok(Map.of("url", body.getOrDefault("url", "")));
    }

    private void saveBase64File(String base64Data, String relativePath) throws IOException {
        // Strip data URI prefix if present
        String raw = base64Data;
        if (raw.contains(",")) {
            raw = raw.substring(raw.indexOf(",") + 1);
        }
        byte[] bytes = Base64.getDecoder().decode(raw);
        Path dest = Paths.get(dataDir, "oss", relativePath);
        Files.createDirectories(dest.getParent());
        Files.write(dest, bytes);
    }

    private String getExtFromBase64(String base64Data) {
        if (base64Data == null) return "bin";
        Matcher m = Pattern.compile("^data:([^;]+);base64,").matcher(base64Data);
        if (!m.find()) return "bin";
        String mime = m.group(1);
        return switch (mime) {
            case "image/jpeg", "image/jpg" -> "jpg";
            case "image/png" -> "png";
            case "audio/mpeg", "audio/mp3" -> "mp3";
            case "audio/wav" -> "wav";
            case "video/mp4" -> "mp4";
            case "video/webm" -> "webm";
            default -> "bin";
        };
    }
}
