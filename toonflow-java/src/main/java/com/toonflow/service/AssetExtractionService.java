package com.toonflow.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.toonflow.ai.AiService;
import com.toonflow.entity.OAssets;
import com.toonflow.entity.OPrompt;
import com.toonflow.entity.OScript;
import com.toonflow.entity.OScriptAssets;
import com.toonflow.mapper.OAssetsMapper;
import com.toonflow.mapper.OPromptMapper;
import com.toonflow.mapper.OScriptAssetsMapper;
import com.toonflow.mapper.OScriptMapper;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class AssetExtractionService {

    private final AiService aiService;
    private final OScriptMapper scriptMapper;
    private final OAssetsMapper assetsMapper;
    private final OScriptAssetsMapper scriptAssetsMapper;
    private final OPromptMapper promptMapper;
    private final ObjectMapper objectMapper;

    @Async
    public void extractAssetsAsync(List<String> scriptIds, String projectId, int groupSize) {
        if (scriptIds == null || scriptIds.isEmpty()) return;

        // Mark all as in-progress (state=2 = queued, 0 = in-progress)
        for (String id : scriptIds) {
            OScript s = scriptMapper.selectById(id);
            if (s != null) {
                s.setExtractState(2);
                scriptMapper.updateById(s);
            }
        }

        // Load system prompt
        String systemPrompt = loadSystemPrompt();

        // Process in groups
        List<List<String>> groups = partition(scriptIds, groupSize);
        for (List<String> group : groups) {
            processGroup(group, projectId, systemPrompt);
        }
    }

    private void processGroup(List<String> groupIds, String projectId, String systemPrompt) {
        List<OScript> scripts = scriptMapper.selectList(
                new LambdaQueryWrapper<OScript>().in(OScript::getId, groupIds));
        if (scripts.isEmpty()) return;

        // Only process those still in queued state (extractState=2)
        List<OScript> toProcess = scripts.stream()
                .filter(s -> s.getExtractState() != null && s.getExtractState() == 2)
                .collect(Collectors.toList());
        if (toProcess.isEmpty()) return;

        List<String> validIds = toProcess.stream().map(OScript::getId).collect(Collectors.toList());

        // Mark as in-progress
        for (String id : validIds) {
            OScript s = scriptMapper.selectById(id);
            if (s != null) { s.setExtractState(0); scriptMapper.updateById(s); }
        }

        // Load existing assets for reference
        List<OAssets> existingAssets = assetsMapper.selectList(
                new LambdaQueryWrapper<OAssets>().eq(OAssets::getProjectId, projectId)
                        .select(OAssets::getName, OAssets::getType));
        String existingHint = existingAssets.stream()
                .map(a -> a.getName() + "(" + a.getType() + ")")
                .collect(Collectors.joining("、"));

        // Build scripts content
        String scriptsContent = toProcess.stream()
                .map(s -> "===== 【剧本ID: " + s.getId() + "】" + (s.getName() != null ? s.getName() : "") + " =====\n" + (s.getContent() != null ? s.getContent() : ""))
                .collect(Collectors.joining("\n\n"));

        String extractionInstruction = "\n\n提取剧本中涉及的资产（角色、场景、道具）。\n" +
                "你必须以如下JSON格式返回，不要添加任何其他内容：\n" +
                "{\n" +
                "  \"newAssets\": [{\"name\":\"资产名称\",\"desc\":\"资产描述\",\"type\":\"role|tool|scene\",\"scriptIds\":[\"剧本ID\"]}],\n" +
                "  \"existingAssetRefs\": [{\"name\":\"已有资产名称\",\"scriptIds\":[\"剧本ID\"]}]\n" +
                "}\n" +
                "规则：\n" +
                "- 已有资产（在已有列表中存在的）放入 existingAssetRefs，只需名称和 scriptIds\n" +
                "- 新资产（不在已有列表中）放入 newAssets，需要 name、desc、type 和 scriptIds\n" +
                "- type 只能是: role（角色）、tool（道具）、scene（场景）\n" +
                "- scriptIds 数组中填写该资产出现的剧本ID";

        String existingHintStr = existingHint.isEmpty() ? "" :
                "\n\n【已有资产列表】：" + existingHint + "\n对于已有资产，只需在 existingAssetRefs 中给出名称和 scriptIds。";

        try {
            String response = aiService.generateText("universalAi", List.of(
                    new AiService.ChatMessage("system", systemPrompt + extractionInstruction),
                    new AiService.ChatMessage("user",
                            "当前已有资产：" + existingHintStr +
                            "\n\n请根据以下" + toProcess.size() + "集剧本提取资产：\n\n" + scriptsContent)));

            ExtractionResult result = parseResult(response);
            if (result == null || (result.newAssets.isEmpty() && result.existingAssetRefs.isEmpty())) {
                markError(validIds, "AI 未返回任何资产");
                return;
            }

            persistResult(validIds, projectId, result);

        } catch (Exception e) {
            log.error("[extractAssets] group={} 提取失败", validIds, e);
            markError(validIds, e.getMessage() != null ? e.getMessage() : "提取失败");
        }
    }

    private void persistResult(List<String> batchIds, String projectId, ExtractionResult result) {
        // Load existing name -> id map
        List<OAssets> existingList = assetsMapper.selectList(
                new LambdaQueryWrapper<OAssets>().eq(OAssets::getProjectId, projectId)
                        .select(OAssets::getId, OAssets::getName));
        Map<String, String> nameToId = existingList.stream()
                .collect(Collectors.toMap(OAssets::getName, OAssets::getId, (a, b) -> a));

        // Insert new assets
        for (NewAsset asset : result.newAssets) {
            if (!nameToId.containsKey(asset.name)) {
                OAssets newAsset = new OAssets();
                newAsset.setProjectId(projectId);
                newAsset.setName(asset.name);
                newAsset.setType(asset.type);
                newAsset.setDescribe(asset.desc);
                newAsset.setStartTime(System.currentTimeMillis());
                assetsMapper.insert(newAsset);
                nameToId.put(asset.name, newAsset.getId());
            }
        }

        // Build script-asset relations
        List<OScriptAssets> rows = new ArrayList<>();
        Set<String> seen = new HashSet<>();

        for (NewAsset asset : result.newAssets) {
            String assetId = nameToId.get(asset.name);
            if (assetId != null && asset.scriptIds != null) {
                for (String sid : asset.scriptIds) {
                    String key = sid + "_" + assetId;
                    if (seen.add(key)) {
                        OScriptAssets sa = new OScriptAssets();
                        sa.setScriptId(sid);
                        sa.setAssetId(assetId);
                        rows.add(sa);
                    }
                }
            }
        }

        for (ExistingRef ref : result.existingAssetRefs) {
            String assetId = nameToId.get(ref.name);
            if (assetId != null && ref.scriptIds != null) {
                for (String sid : ref.scriptIds) {
                    String key = sid + "_" + assetId;
                    if (seen.add(key)) {
                        OScriptAssets sa = new OScriptAssets();
                        sa.setScriptId(sid);
                        sa.setAssetId(assetId);
                        rows.add(sa);
                    }
                }
            }
        }

        // Delete old relations for this batch, insert new
        scriptAssetsMapper.delete(new LambdaQueryWrapper<OScriptAssets>()
                .in(OScriptAssets::getScriptId, batchIds));
        if (!rows.isEmpty()) {
            for (OScriptAssets sa : rows) {
                scriptAssetsMapper.insert(sa);
            }
        }

        // Mark success
        for (String id : batchIds) {
            OScript s = scriptMapper.selectById(id);
            if (s != null) { s.setExtractState(1); s.setErrorReason(null); scriptMapper.updateById(s); }
        }
    }

    private void markError(List<String> ids, String reason) {
        for (String id : ids) {
            OScript s = scriptMapper.selectById(id);
            if (s != null) { s.setExtractState(-1); s.setErrorReason(reason); scriptMapper.updateById(s); }
        }
    }

    private String loadSystemPrompt() {
        OPrompt prompt = promptMapper.selectOne(
                new LambdaQueryWrapper<OPrompt>().eq(OPrompt::getType, "scriptAssetExtraction").last("LIMIT 1"));
        if (prompt != null) {
            if (prompt.getUseData() != null && !prompt.getUseData().isBlank()) return prompt.getUseData();
            if (prompt.getData() != null && !prompt.getData().isBlank()) return prompt.getData();
        }
        return "你是一个剧本资产提取专家，负责从剧本文本中识别角色、道具、场景等资产。";
    }

    private ExtractionResult parseResult(String response) {
        if (response == null || response.isBlank()) return null;
        try {
            // Extract JSON block if wrapped in markdown
            String json = response.trim();
            int start = json.indexOf('{');
            int end = json.lastIndexOf('}');
            if (start >= 0 && end > start) {
                json = json.substring(start, end + 1);
            }
            return objectMapper.readValue(json, ExtractionResult.class);
        } catch (Exception e) {
            log.warn("[extractAssets] JSON 解析失败: {}", e.getMessage());
            return null;
        }
    }

    private static <T> List<List<T>> partition(List<T> list, int size) {
        List<List<T>> result = new ArrayList<>();
        for (int i = 0; i < list.size(); i += size) {
            result.add(list.subList(i, Math.min(i + size, list.size())));
        }
        return result;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    static class ExtractionResult {
        @JsonProperty("newAssets") List<NewAsset> newAssets = new ArrayList<>();
        @JsonProperty("existingAssetRefs") List<ExistingRef> existingAssetRefs = new ArrayList<>();
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    static class NewAsset {
        String name;
        String desc;
        String type;
        List<String> scriptIds = new ArrayList<>();
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    static class ExistingRef {
        String name;
        List<String> scriptIds = new ArrayList<>();
    }
}
