package com.toonflow.ai;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.toonflow.entity.OAssets;
import com.toonflow.entity.OAssetsRole2Audio;
import com.toonflow.entity.OPrompt;
import com.toonflow.mapper.OAssetsMapper;
import com.toonflow.mapper.OAssetsRole2AudioMapper;
import com.toonflow.mapper.OPromptMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class AudioBindService {

    private final AiService aiService;
    private final OAssetsMapper assetsMapper;
    private final OAssetsRole2AudioMapper assetsRole2AudioMapper;
    private final OPromptMapper promptMapper;

    @Async
    public void bindAudioAsync(String projectId, List<String> assetsIds, int concurrentCount) {
        List<OAssets> assetsData = assetsMapper.selectList(
                new LambdaQueryWrapper<OAssets>()
                        .in(OAssets::getId, assetsIds)
                        .eq(OAssets::getProjectId, projectId)
                        .select(OAssets::getId, OAssets::getName, OAssets::getDescribe, OAssets::getType));

        List<OAssets> audioData = assetsMapper.selectList(
                new LambdaQueryWrapper<OAssets>()
                        .eq(OAssets::getProjectId, projectId)
                        .eq(OAssets::getType, "audio")
                        .isNull(OAssets::getAssetsId));
        if (audioData.isEmpty()) return;

        String audioBindPrompt = loadAudioBindPrompt();
        String audioList = audioData.stream()
                .map(a -> "- ID:" + a.getId() + " | 名称:" + a.getName() + " | 描述:" + (a.getDescribe() != null ? a.getDescribe() : "无"))
                .collect(Collectors.joining("\n"));

        for (OAssets asset : assetsData) {
            OAssets upd = new OAssets();
            upd.setId(asset.getId());
            upd.setAudioBindState("生成中");
            assetsMapper.updateById(upd);
        }

        // Process in batches of concurrentCount
        for (int i = 0; i < assetsData.size(); i += Math.max(1, concurrentCount)) {
            int end = Math.min(i + concurrentCount, assetsData.size());
            List<OAssets> batch = assetsData.subList(i, end);
            batch.parallelStream().forEach(asset -> processAsset(asset, audioList, audioBindPrompt));
        }
    }

    private void processAsset(OAssets asset, String audioList, String systemPrompt) {
        try {
            AtomicReference<String> matchedAudioId = new AtomicReference<>(null);

            Object toolObj = new Object() {
                @Tool(name = "resultTool", description = "匹配完成后必须调用此工具提交结果")
                public String submitResult(
                        @ToolParam(description = "与该资产匹配的音频ID，若无合适匹配则为null") String audioId) {
                    matchedAudioId.set(audioId);
                    return "无需回复用户任何内容";
                }
            };

            String userContent = "## 候选音频列表\n" + audioList +
                    "\n## 待匹配资产\n- ID:" + asset.getId() +
                    " | 名称:" + asset.getName() +
                    " | 描述:" + (asset.getDescribe() != null ? asset.getDescribe() : "无") +
                    " | 类型：" + asset.getType() +
                    "\n请从候选音频列表中为该资产选出来一个最符合该角色设定的音色，并调用 resultTool 提交结果。";

            List<AiService.ChatMessage> messages = List.of(
                    new AiService.ChatMessage("system", systemPrompt),
                    new AiService.ChatMessage("user", userContent));

            aiService.generateTextWithTools("universalAi", messages, toolObj);

            assetsRole2AudioMapper.delete(new LambdaQueryWrapper<OAssetsRole2Audio>()
                    .eq(OAssetsRole2Audio::getAssetsRoleId, asset.getId()));
            String audioId = matchedAudioId.get();
            if (audioId != null && !audioId.isBlank()) {
                OAssetsRole2Audio bind = new OAssetsRole2Audio();
                bind.setAssetsRoleId(asset.getId());
                bind.setAssetsAudioId(audioId);
                assetsRole2AudioMapper.insert(bind);
            }
            OAssets upd = new OAssets();
            upd.setId(asset.getId());
            upd.setAudioBindState("已完成");
            assetsMapper.updateById(upd);
        } catch (Exception e) {
            log.error("[bindAudio] 资产 {} 处理失败", asset.getId(), e);
            OAssets upd = new OAssets();
            upd.setId(asset.getId());
            upd.setAudioBindState("生成失败");
            assetsMapper.updateById(upd);
        }
    }

    private String loadAudioBindPrompt() {
        try {
            OPrompt prompt = promptMapper.selectOne(
                    new LambdaQueryWrapper<OPrompt>().eq(OPrompt::getType, "audioBindPrompt").last("LIMIT 1"));
            if (prompt != null) {
                return prompt.getUseData() != null ? prompt.getUseData() :
                       prompt.getData() != null ? prompt.getData() : defaultPrompt();
            }
        } catch (Exception e) {
            log.warn("加载音频绑定提示词失败: {}", e.getMessage());
        }
        return defaultPrompt();
    }

    private String defaultPrompt() {
        return "你是一个专业的音频匹配助手，请根据资产信息从候选音频列表中选择最合适的音频。";
    }
}
