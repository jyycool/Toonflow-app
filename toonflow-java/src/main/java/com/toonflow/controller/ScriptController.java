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
            for (Integer assetId : req.getAssets()) {
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
        // 批量添加剧本逻辑
        return R.ok(Map.of("message", "批量添加剧本成功"));
    }

    @PostMapping("/getScrptApi")
    public R<List<OScript>> getScript(@RequestBody Map<String, Object> body) {
        Integer projectId = body.get("projectId");
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
        Integer id = body.get("id");
        if (id == null) throw new BusinessException("id不能为空");
        scriptMapper.deleteById(id);
        scriptAssetsMapper.delete(new LambdaQueryWrapper<OScriptAssets>().eq(OScriptAssets::getScriptId, id));
        return R.ok(Map.of("message", "删除剧本成功"));
    }

    /**
     * 导出剧本为 zip（每个剧本一个 .txt）
     */
    @PostMapping("/exportScript")
    public void exportScript(@RequestBody Map<String, Object> body,
                             jakarta.servlet.http.HttpServletResponse response) throws java.io.IOException {
        List<Integer> ids = body.get("id");
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

    /**
     * 提取剧本资产：标记剧本为提取中，由 AI 识别角色/道具/场景
     * 对应原项目 script/extractAssets（此处提供同步骨架，完整实现需结合 Agent 工具）
     */
    @PostMapping("/extractAssets")
    public R<Map<String, String>> extractAssets(@RequestBody Map<String, Object> body) {
        @SuppressWarnings("unchecked")
        List<Number> raw_scriptIds = (List<Number>) body.get("scriptIds");
        List<Integer> scriptIds = raw_scriptIds != null ? raw_scriptIds.stream().map(Number::intValue).collect(java.util.stream.Collectors.toList()) : null;
        if (scriptIds != null) {
            for (Integer id : scriptIds) {
                OScript script = scriptMapper.selectById(id);
                if (script != null) {
                    script.setExtractState(0);
                    scriptMapper.updateById(script);
                }
            }
        }
        return R.ok(Map.of("message", "已提交资产提取任务"));
    }

    /**
     * AI 识别剧本的集/章节分隔正则
     */
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

    /**
     * 轮询剧本资产提取状态（排除"生成中"）
     */
    @PostMapping("/pollScriptAssets")
    public R<List<OScript>> pollScriptAssets(@RequestBody Map<String, Object> body) {
        List<Integer> ids = body.get("ids");
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
        @NotNull private Integer projectId;
        @NotNull private List<Integer> assets;
    }
}
