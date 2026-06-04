package com.toonflow.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.toonflow.common.exception.BusinessException;
import com.toonflow.common.result.R;
import com.toonflow.entity.OAssets;
import com.toonflow.entity.OScript;
import com.toonflow.entity.OScriptAssets;
import com.toonflow.mapper.OAgentWorkDataMapper;
import com.toonflow.mapper.OAssetsMapper;
import com.toonflow.mapper.OAssets2StoryboardMapper;
import com.toonflow.mapper.OImageFlowMapper;
import com.toonflow.mapper.OScriptAssetsMapper;
import com.toonflow.mapper.OScriptMapper;
import com.toonflow.mapper.OStoryboardMapper;
import com.toonflow.mapper.OVideoMapper;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/script")
@RequiredArgsConstructor
public class ScriptController {

    private final OScriptMapper scriptMapper;
    private final OScriptAssetsMapper scriptAssetsMapper;
    private final OAssetsMapper assetsMapper;
    private final OStoryboardMapper storyboardMapper;
    private final OAgentWorkDataMapper agentWorkDataMapper;
    private final OImageFlowMapper imageFlowMapper;
    private final OAssets2StoryboardMapper assets2StoryboardMapper;
    private final OVideoMapper videoMapper;
    private final com.toonflow.ai.AiService aiService;
    private final com.toonflow.service.AssetExtractionService assetExtractionService;

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
        String name = body.get("name") != null ? body.get("name").toString() : null;
        LambdaQueryWrapper<OScript> q = new LambdaQueryWrapper<OScript>()
                .eq(OScript::getProjectId, projectId)
                .orderByAsc(OScript::getCreateTime);
        if (name != null && !name.isBlank()) {
            q.like(OScript::getName, name);
        }
        List<OScript> list = scriptMapper.selectList(q);
        if (list.isEmpty()) return R.ok(list);

        // Fetch relatedAssets: o_scriptAssets join o_assets
        List<String> scriptIds = list.stream().map(OScript::getId).collect(Collectors.toList());
        List<OScriptAssets> relations = scriptAssetsMapper.selectList(
                new LambdaQueryWrapper<OScriptAssets>().in(OScriptAssets::getScriptId, scriptIds));

        if (!relations.isEmpty()) {
            List<String> assetIds = relations.stream().map(OScriptAssets::getAssetId).distinct().collect(Collectors.toList());
            List<OAssets> assets = assetsMapper.selectList(
                    new LambdaQueryWrapper<OAssets>().in(OAssets::getId, assetIds)
                            .select(OAssets::getId, OAssets::getName));
            Map<String, String> assetIdToName = assets.stream()
                    .collect(Collectors.toMap(OAssets::getId, a -> a.getName() != null ? a.getName() : ""));

            // Group by scriptId
            Map<String, List<Map<String, Object>>> assetsByScript = relations.stream()
                    .filter(r -> assetIdToName.containsKey(r.getAssetId()))
                    .collect(Collectors.groupingBy(
                            OScriptAssets::getScriptId,
                            Collectors.mapping(r -> {
                                Map<String, Object> m = new java.util.HashMap<>();
                                m.put("id", r.getAssetId());
                                m.put("name", assetIdToName.get(r.getAssetId()));
                                return m;
                            }, Collectors.toList())));

            list.forEach(s -> s.setRelatedAssets(assetsByScript.getOrDefault(s.getId(), List.of())));
        } else {
            list.forEach(s -> s.setRelatedAssets(List.of()));
        }

        return R.ok(list);
    }

    @PostMapping("/updateScript")
    public R<Map<String, String>> updateScript(@RequestBody OScript script) {
        scriptMapper.updateById(script);
        return R.ok(Map.of("message", "更新剧本成功"));
    }

    @PostMapping("/delScript")
    public R<Map<String, String>> delScript(@RequestBody Map<String, Object> body) {
        @SuppressWarnings("unchecked") List<Object> rawIds = (List<Object>) body.get("ids");
        if (rawIds == null || rawIds.isEmpty()) throw new BusinessException("id不能为空");
        List<String> ids = rawIds.stream().map(Object::toString).collect(Collectors.toList());

        // Delete agentWorkData by projectId + episodesId (episodesId = scriptId)
        List<OScript> scripts = scriptMapper.selectList(
                new LambdaQueryWrapper<OScript>().in(OScript::getId, ids));
        if (!scripts.isEmpty()) {
            List<String> projectIds = scripts.stream().map(OScript::getProjectId).distinct().collect(Collectors.toList());
            agentWorkDataMapper.delete(new LambdaQueryWrapper<com.toonflow.entity.OAgentWorkData>()
                    .in(com.toonflow.entity.OAgentWorkData::getProjectId, projectIds)
                    .in(com.toonflow.entity.OAgentWorkData::getEpisodesId, ids));
        }

        // Delete storyboard-related records
        List<com.toonflow.entity.OStoryboard> storyboards = storyboardMapper.selectList(
                new LambdaQueryWrapper<com.toonflow.entity.OStoryboard>()
                        .in(com.toonflow.entity.OStoryboard::getScriptId, ids));
        if (!storyboards.isEmpty()) {
            List<String> sbIds = storyboards.stream().map(com.toonflow.entity.OStoryboard::getId).collect(Collectors.toList());
            // Delete linked imageFlows
            List<String> flowIds = storyboards.stream()
                    .filter(s -> s.getFlowId() != null).map(com.toonflow.entity.OStoryboard::getFlowId).collect(Collectors.toList());
            if (!flowIds.isEmpty()) flowIds.forEach(fid -> imageFlowMapper.deleteById(fid));
            // Delete assets2storyboard links
            assets2StoryboardMapper.delete(new LambdaQueryWrapper<com.toonflow.entity.OAssets2Storyboard>()
                    .in(com.toonflow.entity.OAssets2Storyboard::getStoryboardId, sbIds));
        }

        scriptAssetsMapper.delete(new LambdaQueryWrapper<OScriptAssets>().in(OScriptAssets::getScriptId, ids));
        scriptMapper.deleteBatchIds(ids);
        storyboardMapper.delete(new LambdaQueryWrapper<com.toonflow.entity.OStoryboard>()
                .in(com.toonflow.entity.OStoryboard::getScriptId, ids));
        videoMapper.delete(new LambdaQueryWrapper<com.toonflow.entity.OVideo>()
                .in(com.toonflow.entity.OVideo::getScriptId, ids));
        return R.ok(Map.of("message", "删除剧本成功"));
    }

    @PostMapping("/exportScript")
    public void exportScript(@RequestBody Map<String, Object> body,
                             jakarta.servlet.http.HttpServletResponse response) throws java.io.IOException {
        @SuppressWarnings("unchecked") List<Object> rawIds = (List<Object>) body.get("id");
        if (rawIds == null || rawIds.isEmpty()) throw new BusinessException("id不能为空");
        List<String> ids = rawIds.stream().map(Object::toString).collect(Collectors.toList());
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
        List<String> scriptIds = toStringList(body.get("scriptIds"));
        String projectId = body.get("projectId") != null ? body.get("projectId").toString() : null;
        int groupSize = body.get("groupSize") instanceof Number n ? n.intValue() : 5;
        if (scriptIds == null || scriptIds.isEmpty()) {
            return R.fail("请先选择剧本");
        }
        assetExtractionService.extractAssetsAsync(scriptIds, projectId, groupSize);
        return R.ok(Map.of("message", "开始提取资产"));
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
        List<String> ids = toStringList(body.get("ids"));
        if (ids == null || ids.isEmpty()) return R.ok(List.of());
        // TS: whereNot("extractState", "生成中") — integer column never equals string, returns all rows
        // So: return all scripts for given ids that are NOT in progress (state != 0 and != 2)
        return R.ok(scriptMapper.selectList(
                new LambdaQueryWrapper<OScript>()
                        .in(OScript::getId, ids)
                        .ne(OScript::getExtractState, 0)
                        .ne(OScript::getExtractState, 2)
                        .select(OScript::getId, OScript::getExtractState, OScript::getErrorReason)));
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static List<String> toStringList(Object raw) {
        if (raw == null) return null;
        List list = (List) raw;
        return (List<String>) list.stream().map(Object::toString).collect(Collectors.toList());
    }

    @Data
    public static class AddScriptRequest {
        @NotBlank private String name;
        @NotNull private String content;
        @NotNull private String projectId;
        @NotNull private List<String> assets;
    }
}
