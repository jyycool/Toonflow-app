package com.toonflow.controller;

import com.toonflow.common.exception.BusinessException;
import com.toonflow.common.result.R;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * 技能管理控制器
 * 对应原项目 setting/skillManagement/*
 * 技能以 Markdown 文件形式存储在数据目录的 skills/ 下
 */
@Slf4j
@RestController
@RequestMapping("/api/setting/skillManagement")
@RequiredArgsConstructor
public class SkillController {

    @Value("${toonflow.data-dir}")
    private String dataDir;

    @PostMapping("/getSkillList")
    public R<List<String>> getSkillList() {
        Path skillsRoot = Paths.get(dataDir, "skills");
        try {
            if (!Files.exists(skillsRoot)) {
                Files.createDirectories(skillsRoot);
                return R.ok(List.of());
            }
            try (Stream<Path> stream = Files.walk(skillsRoot)) {
                List<String> mdFiles = stream
                        .filter(Files::isRegularFile)
                        .filter(p -> p.toString().endsWith(".md"))
                        .map(p -> skillsRoot.relativize(p).toString().replace("\\", "/"))
                        .toList();
                return R.ok(mdFiles);
            }
        } catch (IOException e) {
            throw new BusinessException("读取技能列表失败: " + e.getMessage());
        }
    }

    @GetMapping("/getSkillContent")
    public R<Map<String, String>> getSkillContent(@RequestParam String path) {
        Path skillsRoot = Paths.get(dataDir, "skills");
        Path target = skillsRoot.resolve(path).normalize();
        // 防止路径穿越
        if (!target.startsWith(skillsRoot)) {
            throw new BusinessException("非法路径");
        }
        try {
            if (!Files.exists(target)) throw new BusinessException("技能文件不存在");
            String content = Files.readString(target, StandardCharsets.UTF_8);
            return R.ok(Map.of("path", path, "content", content));
        } catch (IOException e) {
            throw new BusinessException("读取技能内容失败: " + e.getMessage());
        }
    }

    @PostMapping("/saveSkillContent")
    public R<Map<String, String>> saveSkillContent(@RequestBody Map<String, String> body) {
        String path = body.get("path");
        String content = body.getOrDefault("content", "");
        if (path == null || !path.endsWith(".md")) {
            throw new BusinessException("技能文件路径必须以 .md 结尾");
        }
        Path skillsRoot = Paths.get(dataDir, "skills");
        Path target = skillsRoot.resolve(path).normalize();
        if (!target.startsWith(skillsRoot)) {
            throw new BusinessException("非法路径");
        }
        try {
            Files.createDirectories(target.getParent());
            Files.writeString(target, content, StandardCharsets.UTF_8);
            return R.ok(Map.of("message", "保存技能成功"));
        } catch (IOException e) {
            throw new BusinessException("保存技能失败: " + e.getMessage());
        }
    }
}
