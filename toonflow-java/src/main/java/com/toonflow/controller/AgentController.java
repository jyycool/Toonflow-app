package com.toonflow.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.toonflow.ai.AiService;
import com.toonflow.ai.MemoryService;
import com.toonflow.common.exception.BusinessException;
import com.toonflow.common.result.R;
import com.toonflow.entity.Memories;
import com.toonflow.entity.OAgentWorkData;
import com.toonflow.entity.OScript;
import com.toonflow.mapper.MemoriesMapper;
import com.toonflow.mapper.OAgentWorkDataMapper;
import com.toonflow.mapper.OScriptMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;

@RestController
@RequiredArgsConstructor
public class AgentController {

    private final AiService aiService;
    private final MemoryService memoryService;
    private final MemoriesMapper memoriesMapper;
    private final OAgentWorkDataMapper agentWorkDataMapper;
    private final OScriptMapper scriptMapper;
    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper;

    private static final DateTimeFormatter DATETIME_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());

    // Build isolationKey from projectId, agentType, and optional episodesId
    private String buildIsolationKey(Object projectId, String agentType, Object episodesId) {
        String key = projectId + ":" + agentType;
        if (episodesId != null) {
            key += ":" + episodesId;
        }
        return key;
    }

    @PostMapping("/api/agents/clearMemory")
    public R<Object> clearMemory(@RequestBody Map<String, Object> body) {
        Object projectId = body.get("projectId");
        String agentType = (String) body.get("agentType");
        Object episodesId = body.get("episodesId");
        String type = body.containsKey("type") ? (String) body.get("type") : "all";

        String isolationKey = buildIsolationKey(projectId, agentType, episodesId);

        if ("all".equals(type)) {
            // Delete all memories for this isolationKey
            memoriesMapper.delete(new LambdaQueryWrapper<Memories>()
                    .eq(Memories::getIsolationKey, isolationKey));
        } else if ("message".equals(type)) {
            // Delete message and summary records
            memoriesMapper.delete(new LambdaQueryWrapper<Memories>()
                    .eq(Memories::getIsolationKey, isolationKey)
                    .eq(Memories::getType, "message"));
            memoriesMapper.delete(new LambdaQueryWrapper<Memories>()
                    .eq(Memories::getIsolationKey, isolationKey)
                    .eq(Memories::getType, "summary"));
        } else {
            // "summary": reset summarized=0 for summarized messages, delete summary records
            memoriesMapper.update(null, new LambdaUpdateWrapper<Memories>()
                    .eq(Memories::getIsolationKey, isolationKey)
                    .eq(Memories::getType, "message")
                    .eq(Memories::getSummarized, 1)
                    .set(Memories::getSummarized, 0));
            memoriesMapper.delete(new LambdaQueryWrapper<Memories>()
                    .eq(Memories::getIsolationKey, isolationKey)
                    .eq(Memories::getType, "summary"));
        }

        return R.ok(null);
    }

    @PostMapping("/api/agents/getMemory")
    public R<List<Map<String, Object>>> getMemory(@RequestBody Map<String, Object> body) {
        Object projectId = body.get("projectId");
        String agentType = (String) body.get("agentType");
        Object episodesId = body.get("episodesId");

        String isolationKey = buildIsolationKey(projectId, agentType, episodesId);

        List<Memories> rows = memoriesMapper.selectList(
                new LambdaQueryWrapper<Memories>()
                        .eq(Memories::getIsolationKey, isolationKey)
                        .eq(Memories::getType, "message")
                        .orderByAsc(Memories::getCreateTime));

        List<Map<String, Object>> history = new ArrayList<>();
        for (Memories row : rows) {
            String role = row.getRole() != null && row.getRole().startsWith("assistant") ? "assistant" : "user";
            String datetime = row.getCreateTime() != null
                    ? DATETIME_FORMATTER.format(Instant.ofEpochMilli(row.getCreateTime()))
                    : null;
            Map<String, Object> contentItem = new LinkedHashMap<>();
            contentItem.put("type", "markdown");
            contentItem.put("status", "complete");
            contentItem.put("data", row.getContent());

            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", row.getId());
            item.put("role", role);
            if (row.getName() != null) {
                item.put("name", row.getName());
            }
            item.put("status", "complete");
            item.put("datetime", datetime);
            item.put("content", List.of(contentItem));
            item.put("createTime", row.getCreateTime());
            history.add(item);
        }

        return R.ok(history);
    }

    @PostMapping("/api/scriptAgent/getPlanData")
    public R<Map<String, Object>> getPlanData(@RequestBody Map<String, Object> body) {
        Object projectId = body.get("projectId");
        String agentType = (String) body.get("agentType");

        OAgentWorkData row = agentWorkDataMapper.selectOne(
                new LambdaQueryWrapper<OAgentWorkData>()
                        .eq(OAgentWorkData::getProjectId, projectId)
                        .eq(OAgentWorkData::getKey, agentType));

        Map<String, Object> result = new LinkedHashMap<>();

        if (row == null) {
            // Insert default record
            OAgentWorkData newRow = new OAgentWorkData();
            newRow.setProjectId(projectId != null ? projectId.toString() : null);
            newRow.setKey(agentType);
            Map<String, Object> defaultData = new LinkedHashMap<>();
            defaultData.put("storySkeleton", "");
            defaultData.put("adaptationStrategy", "");
            try {
                newRow.setData(objectMapper.writeValueAsString(defaultData));
            } catch (Exception e) {
                newRow.setData("{\"storySkeleton\":\"\",\"adaptationStrategy\":\"\"}");
            }
            newRow.setCreateTime(System.currentTimeMillis());
            newRow.setUpdateTime(System.currentTimeMillis());
            agentWorkDataMapper.insert(newRow);

            result.put("data", defaultData);
            result.put("id", newRow.getId());
        } else {
            Map<String, Object> parsedData;
            try {
                parsedData = objectMapper.readValue(row.getData() != null ? row.getData() : "{}",
                        new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
            } catch (Exception e) {
                parsedData = new LinkedHashMap<>();
            }

            // Fetch script rows
            List<OScript> scripts = scriptMapper.selectList(
                    new LambdaQueryWrapper<OScript>()
                            .eq(OScript::getProjectId, projectId != null ? projectId.toString() : null)
                            .select(OScript::getId, OScript::getName, OScript::getContent));

            List<Map<String, Object>> scriptList = new ArrayList<>();
            for (OScript s : scripts) {
                Map<String, Object> scriptItem = new LinkedHashMap<>();
                scriptItem.put("id", s.getId());
                scriptItem.put("name", s.getName());
                scriptItem.put("content", s.getContent());
                scriptList.add(scriptItem);
            }
            parsedData.put("script", scriptList);

            result.put("data", parsedData);
            result.put("id", row.getId());
        }

        return R.ok(result);
    }

    @PostMapping("/api/scriptAgent/setPlanData")
    @SuppressWarnings("unchecked")
    public R<Object> setPlanData(@RequestBody Map<String, Object> body) {
        Object projectId = body.get("projectId");
        String agentType = (String) body.get("agentType");
        Map<String, Object> data = (Map<String, Object>) body.get("data");

        // Extract script array before storing, then remove it from data
        List<Map<String, Object>> scriptItems = null;
        if (data != null && data.containsKey("script")) {
            Object scriptVal = data.get("script");
            if (scriptVal instanceof List) {
                scriptItems = (List<Map<String, Object>>) scriptVal;
            }
        }

        // Store data WITHOUT script in agentWorkData
        Map<String, Object> dataToStore = new LinkedHashMap<>();
        if (data != null) {
            dataToStore.putAll(data);
            dataToStore.remove("script");
        }
        String dataJson;
        try {
            dataJson = objectMapper.writeValueAsString(dataToStore);
        } catch (Exception e) {
            throw new BusinessException("序列化数据失败: " + e.getMessage());
        }

        OAgentWorkData existing = agentWorkDataMapper.selectOne(
                new LambdaQueryWrapper<OAgentWorkData>()
                        .eq(OAgentWorkData::getProjectId, projectId)
                        .eq(OAgentWorkData::getKey, agentType));

        if (existing == null) {
            OAgentWorkData newRow = new OAgentWorkData();
            newRow.setProjectId(projectId != null ? projectId.toString() : null);
            newRow.setKey(agentType);
            newRow.setData(dataJson);
            newRow.setCreateTime(System.currentTimeMillis());
            newRow.setUpdateTime(System.currentTimeMillis());
            agentWorkDataMapper.insert(newRow);
        } else {
            existing.setData(dataJson);
            existing.setUpdateTime(System.currentTimeMillis());
            agentWorkDataMapper.updateById(existing);
        }

        // Update o_script entries by id
        if (scriptItems != null) {
            for (Map<String, Object> s : scriptItems) {
                String scriptId = (String) s.get("id");
                String content = (String) s.get("content");
                if (scriptId != null) {
                    OScript scriptRow = scriptMapper.selectById(scriptId);
                    if (scriptRow != null) {
                        scriptRow.setContent(content);
                        scriptMapper.updateById(scriptRow);
                    }
                }
            }
        }

        return R.ok(null);
    }

    @PostMapping("/api/scriptAgent/updateData")
    public R<String> updateData(@RequestBody Map<String, Object> body) {
        String id = (String) body.get("id");
        Object data = body.get("data");
        OAgentWorkData work = agentWorkDataMapper.selectById(id);
        if (work == null) throw new BusinessException("工作数据不存在");
        try {
            work.setData(objectMapper.writeValueAsString(data));
            work.setUpdateTime(System.currentTimeMillis());
            agentWorkDataMapper.updateById(work);
        } catch (Exception e) {
            throw new BusinessException("更新失败: " + e.getMessage());
        }
        return R.ok("更新成功");
    }

    /**
     * 流式 AI 文本生成接口（SSE）
     */
    @GetMapping(value = "/api/agents/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> streamGenerate(@RequestParam String agentType,
                                        @RequestParam String prompt) {
        List<AiService.ChatMessage> messages = List.of(
                new AiService.ChatMessage("user", prompt));
        return aiService.streamText(agentType, messages)
                .map(chunk -> "data: " + chunk + "\n\n");
    }

}

