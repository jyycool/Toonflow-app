package com.toonflow.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.toonflow.ai.AiService;
import com.toonflow.ai.MemoryService;
import com.toonflow.common.result.R;
import com.toonflow.entity.Memories;
import com.toonflow.entity.OAgentWorkData;
import com.toonflow.mapper.MemoriesMapper;
import com.toonflow.mapper.OAgentWorkDataMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/agents")
@RequiredArgsConstructor
public class AgentController {

    private final AiService aiService;
    private final MemoryService memoryService;
    private final MemoriesMapper memoriesMapper;
    private final OAgentWorkDataMapper agentWorkDataMapper;
    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper;

    @GetMapping("/getMemory")
    public R<List<Memories>> getMemory(@RequestParam String isolationKey) {
        return R.ok(memoriesMapper.selectList(
                new LambdaQueryWrapper<Memories>()
                        .eq(Memories::getIsolationKey, isolationKey)
                        .orderByDesc(Memories::getCreateTime)));
    }

    @PostMapping("/clearMemory")
    public R<Map<String, String>> clearMemory(@RequestBody Map<String, String> body) {
        memoryService.clear(body.get("isolationKey"));
        return R.ok(Map.of("message", "记忆已清除"));
    }

    @GetMapping("/scriptAgent/getPlanData")
    public R<OAgentWorkData> getPlanData(@RequestParam Integer projectId,
                                          @RequestParam Integer episodesId,
                                          @RequestParam String key) {
        return R.ok(agentWorkDataMapper.selectOne(
                new LambdaQueryWrapper<OAgentWorkData>()
                        .eq(OAgentWorkData::getProjectId, projectId)
                        .eq(OAgentWorkData::getEpisodesId, episodesId)
                        .eq(OAgentWorkData::getKey, key)));
    }

    @PostMapping("/scriptAgent/setPlanData")
    public R<Map<String, String>> setPlanData(@RequestBody OAgentWorkData data) {
        OAgentWorkData existing = agentWorkDataMapper.selectOne(
                new LambdaQueryWrapper<OAgentWorkData>()
                        .eq(OAgentWorkData::getProjectId, data.getProjectId())
                        .eq(OAgentWorkData::getEpisodesId, data.getEpisodesId())
                        .eq(OAgentWorkData::getKey, data.getKey()));
        if (existing == null) {
            data.setCreateTime(System.currentTimeMillis());
            data.setUpdateTime(System.currentTimeMillis());
            agentWorkDataMapper.insert(data);
        } else {
            existing.setData(data.getData());
            existing.setUpdateTime(System.currentTimeMillis());
            agentWorkDataMapper.updateById(existing);
        }
        return R.ok(Map.of("message", "保存成功"));
    }

    /**
     * 更新工作区数据（剧本骨架/改编策略/剧本）
     */
    @PostMapping("/scriptAgent/updateData")
    public R<Map<String, String>> updateData(@RequestBody Map<String, Object> body) {
        Integer id = (Integer) body.get("id");
        Object data = body.get("data");
        OAgentWorkData work = agentWorkDataMapper.selectById(id);
        if (work == null) throw new com.toonflow.common.exception.BusinessException("工作数据不存在");
        try {
            work.setData(objectMapper.writeValueAsString(data));
            work.setUpdateTime(System.currentTimeMillis());
            agentWorkDataMapper.updateById(work);
        } catch (Exception e) {
            throw new com.toonflow.common.exception.BusinessException("更新失败: " + e.getMessage());
        }
        return R.ok(Map.of("message", "更新成功"));
    }

    /**
     * 流式 AI 文本生成接口（SSE）
     */
    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> streamGenerate(@RequestParam String agentType,
                                        @RequestParam String prompt) {
        List<AiService.ChatMessage> messages = List.of(
                new AiService.ChatMessage("user", prompt));
        return aiService.streamText(agentType, messages)
                .map(chunk -> "data: " + chunk + "\n\n");
    }
}
