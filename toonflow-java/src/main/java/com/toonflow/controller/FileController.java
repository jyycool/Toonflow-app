package com.toonflow.controller;

import com.toonflow.common.result.R;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/api")
public class FileController {

    @Value("${toonflow.data-dir}")
    private String dataDir;

    @PostMapping("/assets/uploadClip")
    public R<Map<String, String>> uploadClip(@RequestParam("file") MultipartFile file) throws IOException {
        return saveFile(file, "assets");
    }

    @PostMapping("/production/editImage/uploadImage")
    public R<Map<String, String>> uploadImage(@RequestParam("file") MultipartFile file) throws IOException {
        return saveFile(file, "oss");
    }

    private R<Map<String, String>> saveFile(MultipartFile file, String subDir) throws IOException {
        String ext = "";
        String original = file.getOriginalFilename();
        if (original != null && original.contains(".")) {
            ext = original.substring(original.lastIndexOf("."));
        }
        String fileName = UUID.randomUUID() + ext;
        Path dir = Paths.get(dataDir, subDir);
        Files.createDirectories(dir);
        Path dest = dir.resolve(fileName);
        file.transferTo(dest.toFile());
        String url = "/" + subDir + "/" + fileName;
        return R.ok(Map.of("url", url, "fileName", fileName));
    }

    @GetMapping("/common/getBigImage")
    public R<Map<String, String>> getBigImage(@RequestParam String url) {
        return R.ok(Map.of("url", url));
    }
}
