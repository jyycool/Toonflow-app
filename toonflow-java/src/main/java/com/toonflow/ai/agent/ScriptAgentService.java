package com.toonflow.ai.agent;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.toonflow.ai.AiService;
import com.toonflow.ai.MemoryService;
import com.toonflow.ai.agent.tool.ScriptAgentTools;
import com.toonflow.entity.ONovel;
import com.toonflow.entity.OProject;
import com.toonflow.mapper.ONovelMapper;
import com.toonflow.mapper.OProjectMapper;
import com.toonflow.mapper.OScriptMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * 剧本 Agent 编排服务
 * 对应原项目 src/agents/scriptAgent/index.ts
 *
 * 决策 Agent 接收用户消息，结合项目信息和记忆，流式生成回复，
 * 并通过 WebSocket 推送到 /topic/agent/{sessionId}
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ScriptAgentService {

    private final AiService aiService;
    private final MemoryService memoryService;
    private final OProjectMapper projectMapper;
    private final ONovelMapper novelMapper;
    private final OScriptMapper scriptMapper;
    private final SimpMessagingTemplate messagingTemplate;

    private static final String AGENT_TYPE = "scriptAgent";

    /**
     * 运行决策 Agent，流式推送结果
     */
    public void runDecision(String sessionId, String isolationKey, Long projectId, String userText) {
        // 1. 记录用户消息
        memoryService.add(AGENT_TYPE, isolationKey, "user", userText);

        // 2. 构建记忆上下文
        MemoryService.MemoryContext mem = memoryService.get(isolationKey, userText);
        String memPrompt = memoryService.buildPrompt(mem);

        // 3. 构建项目信息
        String projectInfo = buildProjectInfo(projectId);

        // 4. 系统提示词（原项目从 skills/script_agent_decision.md 读取）
        String systemPrompt = loadDecisionPrompt();

        // 5. 流式生成
        List<AiService.ChatMessage> messages = List.of(
                new AiService.ChatMessage("system", systemPrompt),
                new AiService.ChatMessage("assistant", projectInfo + "\n" + memPrompt),
                new AiService.ChatMessage("user", userText));

        StringBuilder fullResponse = new StringBuilder();

        // 绑定当前会话的工具集，供大模型自主调用
        ScriptAgentTools tools = new ScriptAgentTools(novelMapper, scriptMapper, String.valueOf(projectId));

        aiService.streamTextWithTools(AGENT_TYPE + ":decisionAgent", messages, tools)
                .subscribe(
                        chunk -> {
                            fullResponse.append(chunk);
                            messagingTemplate.convertAndSend("/topic/agent/" + sessionId,
                                    Map.of("type", "chunk", "content", chunk));
                        },
                        error -> {
                            log.error("剧本 Agent 执行失败", error);
                            messagingTemplate.convertAndSend("/topic/agent/" + sessionId,
                                    Map.of("type", "error", "message", error.getMessage()));
                        },
                        () -> {
                            // 保存助手回复到记忆
                            memoryService.add(AGENT_TYPE, isolationKey, "assistant:decision",
                                    stripXmlTags(fullResponse.toString()));
                            messagingTemplate.convertAndSend("/topic/agent/" + sessionId,
                                    Map.of("type", "done"));
                        });
    }

    private String buildProjectInfo(Long projectId) {
        OProject project = projectMapper.selectById(projectId);
        Long novelCount = novelMapper.selectCount(
                new LambdaQueryWrapper<ONovel>().eq(ONovel::getProjectId, projectId));

        return String.join("\n",
                "## 项目信息",
                "小说名称：" + (project != null && project.getName() != null ? project.getName() : "未知"),
                "小说类型：" + (project != null && project.getType() != null ? project.getType() : "未知"),
                "小说简介：" + (project != null && project.getIntro() != null ? project.getIntro() : "无"),
                "目标改编影视视觉手册|画风：" + (project != null && project.getArtStyle() != null ? project.getArtStyle() : "无"),
                "目标改编视频画幅：" + (project != null && project.getVideoRatio() != null ? project.getVideoRatio() : "16:9"),
                "章节数量：" + novelCount + "章");
    }

    private String loadDecisionPrompt() {
        // 原项目从技能文件读取，此处提供默认提示词，可后续扩展为从文件/数据库加载
        return """
                你是 Toonflow 的剧本改编决策 Agent。你的职责是协助用户将小说改编为短剧/漫剧剧本。
                你需要理解用户意图，结合项目信息和历史记忆，给出专业的改编建议和决策。
                改编时关注：故事骨架、人物塑造、节奏控制、视觉呈现。
                """;
    }

    private String stripXmlTags(String text) {
        if (text == null) return "";
        return text.replaceAll("<[^>]+>", "").trim();
    }
}
