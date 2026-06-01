package com.toonflow.ai.agent;

import com.toonflow.ai.AiService;
import com.toonflow.ai.MemoryService;
import com.toonflow.entity.OProject;
import com.toonflow.mapper.OProjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * 制作 Agent 编排服务
 * 对应原项目 src/agents/productionAgent/index.ts
 *
 * 决策 Agent 根据用户意图调度多个子 Agent：
 *  - deriveAssetsAgent     素材提取
 *  - generateAssetsAgent   素材生成
 *  - directorPlanAgent     导演规划
 *  - storyboardGenAgent    分镜生成
 *  - storyboardPanelAgent  分镜面板
 *  - storyboardTableAgent  分镜表格
 *  - supervisionAgent      监制审核
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProductionAgentService {

    private final AiService aiService;
    private final MemoryService memoryService;
    private final OProjectMapper projectMapper;
    private final SimpMessagingTemplate messagingTemplate;

    private static final String AGENT_TYPE = "productionAgent";

    /**
     * 子 Agent 定义
     */
    public enum SubAgent {
        DERIVE_ASSETS("productionAgent:deriveAssetsAgent", "素材提取", "assistant:execution"),
        GENERATE_ASSETS("productionAgent:generateAssetsAgent", "素材生成", "assistant:execution"),
        DIRECTOR_PLAN("productionAgent:directorPlanAgent", "导演规划", "assistant:execution"),
        STORYBOARD_GEN("productionAgent:storyboardGenAgent", "分镜生成", "assistant:execution"),
        STORYBOARD_PANEL("productionAgent:storyboardPanelAgent", "分镜面板", "assistant:execution"),
        STORYBOARD_TABLE("productionAgent:storyboardTableAgent", "分镜表格", "assistant:execution"),
        SUPERVISION("productionAgent:supervisionAgent", "监制", "assistant:supervision");

        public final String key;
        public final String name;
        public final String memoryKey;

        SubAgent(String key, String name, String memoryKey) {
            this.key = key;
            this.name = name;
            this.memoryKey = memoryKey;
        }
    }

    /**
     * 运行决策 Agent（主入口），流式推送
     */
    public void runDecision(String sessionId, String isolationKey, Long projectId, String userText) {
        memoryService.add(AGENT_TYPE, isolationKey, "user", userText);

        MemoryService.MemoryContext mem = memoryService.get(isolationKey, userText);
        String memPrompt = memoryService.buildPrompt(mem);
        String projectInfo = buildProjectInfo(projectId);

        List<AiService.ChatMessage> messages = List.of(
                new AiService.ChatMessage("system", loadDecisionPrompt()),
                new AiService.ChatMessage("assistant", projectInfo + "\n" + memPrompt),
                new AiService.ChatMessage("user", userText));

        streamAndSave(sessionId, isolationKey, AGENT_TYPE + ":decisionAgent",
                messages, "assistant:decision");
    }

    /**
     * 运行指定子 Agent
     */
    public void runSubAgent(String sessionId, String isolationKey, Long projectId,
                            SubAgent subAgent, String prompt) {
        messagingTemplate.convertAndSend("/topic/agent/" + sessionId,
                Map.of("type", "agentStart", "name", subAgent.name));

        List<AiService.ChatMessage> messages = List.of(
                new AiService.ChatMessage("user", prompt));

        streamAndSave(sessionId, isolationKey, subAgent.key, messages, subAgent.memoryKey);
    }

    private void streamAndSave(String sessionId, String isolationKey, String agentKey,
                               List<AiService.ChatMessage> messages, String memoryKey) {
        StringBuilder full = new StringBuilder();
        aiService.streamText(agentKey, messages)
                .subscribe(
                        chunk -> {
                            full.append(chunk);
                            messagingTemplate.convertAndSend("/topic/agent/" + sessionId,
                                    Map.of("type", "chunk", "content", chunk));
                        },
                        error -> {
                            log.error("制作 Agent 执行失败", error);
                            messagingTemplate.convertAndSend("/topic/agent/" + sessionId,
                                    Map.of("type", "error", "message", error.getMessage()));
                        },
                        () -> {
                            memoryService.add(AGENT_TYPE, isolationKey, memoryKey,
                                    stripXmlTags(full.toString()));
                            messagingTemplate.convertAndSend("/topic/agent/" + sessionId,
                                    Map.of("type", "done"));
                        });
    }

    private String buildProjectInfo(Long projectId) {
        OProject project = projectMapper.selectById(projectId);
        if (project == null) return "## 项目信息\n（项目不存在）";
        return String.join("\n",
                "## 项目信息",
                "项目名称：" + nv(project.getName()),
                "项目类型：" + nv(project.getType()),
                "画风：" + nv(project.getArtStyle()),
                "画幅：" + nv(project.getVideoRatio()),
                "图片模型：" + nv(project.getImageModel()),
                "视频模型：" + nv(project.getVideoModel()));
    }

    private String loadDecisionPrompt() {
        return """
                你是 Toonflow 的制作总导演决策 Agent。你负责把已完成的剧本制作成视频成片。
                你可以调度以下子 Agent 完成工作：素材提取、素材生成、导演规划、
                分镜生成、分镜面板、分镜表格、监制审核。
                请根据用户意图判断下一步该执行哪个环节，并给出专业的制作决策。
                """;
    }

    private String nv(String s) { return s != null ? s : "未知"; }

    private String stripXmlTags(String text) {
        return text == null ? "" : text.replaceAll("<[^>]+>", "").trim();
    }
}
