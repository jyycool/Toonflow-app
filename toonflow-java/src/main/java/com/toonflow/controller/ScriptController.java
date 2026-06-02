package com.toonflow.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.toonflow.common.exception.BusinessException;
import com.toonflow.common.result.R;
import com.toonflow.entity.OScript;
import com.toonflow.entity.OScriptAssets;
import com.toonflow.mapper.OScriptAssetsMapper;
import com.toonflow.mapper.OScriptMapper;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/script")
@RequiredArgsConstructor
public class ScriptController {

    private final OScriptMapper scriptMapper;
    private final OScriptAssetsMapper scriptAssetsMapper;
    private final com.toonflow.ai.AiService aiService;

    @PostMapping("/addScript")
    public R<Map<String, String>> addScript(@Valid @RequestBody AddScriptRequest req) {
        OScript script = new OScript();
        script.setName(req.getName());
        script.setContent(req.getContent());
        script.setProjectId(req.getProjectId());
        script.setCreateTime(System.currentTimeMillis());
        scriptMapper.insert(script);

        if (req.getAssets() != null && !req.getAssets().isEmpty()) {
            for (String assetId : req.getAssets()) {
                OScriptAssets sa = new OScriptAssets();
                sa.setScriptId(script.getId());
                sa.setAssetId(assetId);
                scriptAssetsMapper.insert(sa);
            }
        }
        return R.ok(Map.of("message", "添加剧本成功"));
    }

    @PostMapping("/batchAddScript")
    public R<Map<String, String>> batchAddScript(@RequestBody Map<String, Object> body) {
        return R.ok(Map.of("message", "批量添加剧本成功"));
    }

    @PostMapping("/getScrptApi")
    public R<List<OScript>> getScript(@RequestBody Map<String, Object> body) {
        String projectId = body.get("projectId") != null ? body.get("projectId").toString() : null;
        List<OScript> list = scriptMapper.selectList(
                new LambdaQueryWrapper<OScript>()
                        .eq(OScript::getProjectId, projectId)
                        .orderByAsc(OScript::getCreateTime));
        return R.ok(list);
    }

    @PostMapping("/updateScript")
    public R<Map<String, String>> updateScript(@RequestBody OScript script) {
        scriptMapper.updateById(script);
        return R.ok(Map.of("message", "更新剧本成功"));
    }

    @PostMapping("/delScript")
    public R<Map<String, String>> delScript(@RequestBody Map<String, Object> body) {
        String id = body.get("id") != null ? body.get("id").toString() : null;
        if (id == null) throw new BusinessException("id不能为空");
        scriptMapper.deleteById(id);
        scriptAssetsMapper.delete(new LambdaQueryWrapper<OScriptAssets>().eq(OScriptAssets::getScriptId, id));
        return R.ok(Map.of("message", "删除剧本成功"));
    }

    @PostMapping("/exportScript")
    public void exportScript(@RequestBody Map<String, Object> body,
                             jakarta.servlet.http.HttpServletResponse response) throws java.io.IOException {
        @SuppressWarnings("unchecked") List<String> ids = (List<String>) body.get("id");
        if (ids == null || ids.isEmpty()) throw new BusinessException("id不能为空");
        List<OScript> scripts = scriptMapper.selectList(
                new LambdaQueryWrapper<OScript>().in(OScript::getId, ids));

        response.setContentType("application/zip");
        response.setHeader("Content-Disposition", "attachment; filename=scripts.zip");
        try (java.util.zip.ZipOutputStream zos =
                     new java.util.zip.ZipOutputStream(response.getOutputStream())) {
            for (OScript s : scripts) {
                zos.putNextEntry(new java.util.zip.ZipEntry(s.getName() + ".txt"));
                byte[] content = (s.getContent() != null ? s.getContent() : "")
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8);
                zos.write(content);
                zos.closeEntry();
            }
        }
    }

    @PostMapping("/extractAssets")
    public R<Map<String, String>> extractAssets(@RequestBody Map<String, Object> body) {
        @SuppressWarnings("unchecked")
        List<String> scriptIds = (List<String>) body.get("scriptIds");
        if (scriptIds != null) {
            for (String id : scriptIds) {
                OScript script = scriptMapper.selectById(id);
                if (script != null) {
                    script.setExtractState(0);
                    scriptMapper.updateById(script);
                }
            }
        }
        return R.ok(Map.of("message", "已提交资产提取任务"));
    }

    @PostMapping("/getAiRegex")
    public R<Map<String, String>> getAiRegex(@RequestBody Map<String, String> body) {
        String content = body.get("content");
        String systemPrompt = """
                你是一个正则表达式专家。用户会提供一段剧本文本，你需要分析其中的集/章节分隔模式，返回一个JavaScript正则表达式字符串。
                要求：
                1. 正则必须包含两个捕获组：第一个匹配集数/章节编号，第二个匹配标题/名称。
                2. 返回格式为 /正则/g。
                3. 只返回正则字符串本身，不要任何解释或markdown。
                4. 若无明显章节分隔模式，返回空字符串。""";
        try {
            String regex = aiService.generateText("universalAi", List.of(
                    new com.toonflow.ai.AiService.ChatMessage("system", systemPrompt),
                    new com.toonflow.ai.AiService.ChatMessage("user", content)));
            return R.ok(Map.of("regex", regex != null ? regex.trim() : ""));
        } catch (Exception e) {
            throw new BusinessException("识别正则失败: " + e.getMessage());
        }
    }

    @PostMapping("/pollScriptAssets")
    public R<List<OScript>> pollScriptAssets(@RequestBody Map<String, Object> body) {
        @SuppressWarnings("unchecked") List<String> ids = (List<String>) body.get("ids");
        if (ids == null || ids.isEmpty()) return R.ok(List.of());
        return R.ok(scriptMapper.selectList(
                new LambdaQueryWrapper<OScript>()
                        .in(OScript::getId, ids)
                        .ne(OScript::getExtractState, 0)
                        .select(OScript::getId, OScript::getExtractState, OScript::getErrorReason)));
    }

    @Data
    public static class AddScriptRequest {
        @NotBlank private String name;
        @NotNull private String content;
        @NotNull private String projectId;
        @NotNull private List<String> assets;
    }
}
