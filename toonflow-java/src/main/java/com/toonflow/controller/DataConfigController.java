package com.toonflow.controller;

import com.toonflow.common.exception.BusinessException;
import com.toonflow.common.result.R;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;

/**
 * 数据库配置控制器
 * 对应原项目 setting/dbConfig/*
 * 提供数据库的导出（下载 SQLite 文件）、导入、清空表等管理功能。
 */
@Slf4j
@RestController
@RequestMapping("/api/setting/dbConfig")
@RequiredArgsConstructor
public class DataConfigController {

    private final JdbcTemplate jdbcTemplate;

    @Value("${toonflow.data-dir}")
    private String dataDir;

    private static final List<String> ALL_TABLES = List.of(
            "o_user", "o_project", "o_novel", "o_script", "o_assets", "o_storyboard",
            "o_image", "o_video", "o_videoTrack", "o_artStyle", "o_agentDeploy",
            "o_agentWorkData", "o_prompt", "o_modelPrompt", "o_setting", "o_vendorConfig",
            "o_tasks", "o_event", "o_eventChapter", "o_outline", "o_outlineNovel",
            "o_imageFlow", "o_assets2Storyboard", "o_scriptAssets", "o_assetsRole2Audio",
            "o_outlineNovel", "o_skillList", "o_skillAttribution", "memories");

    /**
     * 导出数据库文件
     */
    @GetMapping("/exportData")
    public void exportData(HttpServletResponse response) throws IOException {
        Path dbFile = Paths.get(dataDir, "toonflow.db");
        if (!Files.exists(dbFile)) throw new BusinessException("数据库文件不存在");

        response.setContentType("application/octet-stream");
        response.setHeader("Content-Disposition", "attachment; filename=\"toonflow.db\"");
        try (OutputStream os = response.getOutputStream()) {
            Files.copy(dbFile, os);
            os.flush();
        }
    }

    /**
     * 导入数据库文件（覆盖现有，需重启生效）
     */
    @PostMapping("/importData")
    public R<Map<String, String>> importData(@RequestParam("file") MultipartFile file)
            throws IOException {
        if (file.isEmpty()) throw new BusinessException("上传文件为空");
        Path target = Paths.get(dataDir, "toonflow_import.db");
        file.transferTo(target.toFile());
        return R.ok(Map.of("message", "导入文件已保存为 toonflow_import.db，重启服务后生效"));
    }

    /**
     * 清空指定表
     */
    @PostMapping("/clearTable")
    public R<Map<String, String>> clearTable(@RequestBody Map<String, String> body) {
        String table = body.get("table");
        if (table == null || !ALL_TABLES.contains(table)) {
            throw new BusinessException("非法表名");
        }
        jdbcTemplate.execute("DELETE FROM " + table);
        return R.ok(Map.of("message", "已清空表 " + table));
    }

    /**
     * 清空全部业务数据（保留用户与配置）
     */
    @PostMapping("/clearData")
    public R<Map<String, String>> clearData() {
        List<String> preserve = List.of("o_user", "o_setting", "o_vendorConfig", "o_agentDeploy");
        for (String table : ALL_TABLES) {
            if (!preserve.contains(table)) {
                jdbcTemplate.execute("DELETE FROM " + table);
            }
        }
        return R.ok(Map.of("message", "已清空全部业务数据"));
    }

    /**
     * 数据库统计信息
     */
    @GetMapping("/dbInfo")
    public R<Map<String, Object>> dbInfo() {
        long total = 0;
        Map<String, Long> tableCounts = new java.util.HashMap<>();
        for (String table : ALL_TABLES.stream().distinct().toList()) {
            try {
                Long count = jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM " + table, Long.class);
                tableCounts.put(table, count != null ? count : 0);
                total += count != null ? count : 0;
            } catch (Exception ignored) {}
        }
        return R.ok(Map.of("total", total, "tables", tableCounts));
    }
}
