package com.toonflow.ai.agent;

import com.toonflow.ai.AiService;
import com.toonflow.ai.MemoryService;
import com.toonflow.ai.agent.tool.ProductionAgentTools;
import com.toonflow.ai.vendor.MediaGenerationService;
import com.toonflow.entity.OProject;
import com.toonflow.mapper.OAssetsMapper;
import com.toonflow.mapper.OImageFlowMapper;
import com.toonflow.mapper.OProjectMapper;
import com.toonflow.mapper.OScriptAssetsMapper;
import com.toonflow.mapper.OStoryboardMapper;
import com.toonflow.websocket.SocketIoWebSocketHandler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.socket.WebSocketSession;

import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class ProductionAgentService {

    private final AiService aiService;
    private final MemoryService memoryService;
    private final OProjectMapper projectMapper;
    private final OAssetsMapper assetsMapper;
    private final OScriptAssetsMapper scriptAssetsMapper;
    private final OStoryboardMapper storyboardMapper;
    private final OImageFlowMapper imageFlowMapper;
    private final MediaGenerationService mediaGenerationService;
    private final SocketIoWebSocketHandler socketIoHandler;

    private static final String AGENT_TYPE = "productionAgent";

    public void runDecision(WebSocketSession session, String namespace, String sid,
                            String isolationKey, String projectId, String userText) {
        runDecision(session, namespace, sid, isolationKey, projectId, null, userText);
    }

    public void runDecision(WebSocketSession session, String namespace, String sid,
                            String isolationKey, String projectId, String scriptId, String userText) {
        memoryService.add(AGENT_TYPE, isolationKey, "user", userText);

        MemoryService.MemoryContext mem = memoryService.get(isolationKey, userText);
        String memPrompt = memoryService.buildPrompt(mem);
        String projectInfo = buildProjectInfo(projectId);

        List<AiService.ChatMessage> messages = List.of(
                new AiService.ChatMessage("system", loadDecisionPrompt()),
                new AiService.ChatMessage("assistant", projectInfo + "\n" + memPrompt),
                new AiService.ChatMessage("user", userText));

        OProject project = projectMapper.selectById(projectId);
        String imageModel = project != null ? project.getImageModel() : null;
        ProductionAgentTools tools = new ProductionAgentTools(assetsMapper, scriptAssetsMapper,
                storyboardMapper, imageFlowMapper, mediaGenerationService,
                projectId, scriptId, imageModel);

        String messageId = UUID.randomUUID().toString();
        String contentId = UUID.randomUUID().toString();
        String datetime = new java.util.Date().toString();

        socketIoHandler.emit(session, namespace, "message", Map.of(
                "id", messageId, "role", "assistant", "name", "视频策划",
                "status", "pending", "datetime", datetime, "content", new ArrayList<>()));

        socketIoHandler.emit(session, namespace, "content:add", Map.of(
                "messageId", messageId,
                "content", Map.of("type", "text", "id", contentId, "data", "", "status", "pending")));

        StringBuilder full = new StringBuilder();
        aiService.streamTextWithTools(AGENT_TYPE + ":decisionAgent", messages, tools)
                .subscribe(
                        chunk -> {
                            full.append(chunk);
                            socketIoHandler.emit(session, namespace, "content:update", Map.of(
                                    "messageId", messageId, "contentId", contentId,
                                    "type", "text", "data", chunk,
                                    "strategy", "append", "status", "streaming"));
                        },
                        error -> {
                            log.error("制作 Agent 执行失败", error);
                            socketIoHandler.emit(session, namespace, "message:update", Map.of(
                                    "id", messageId, "status", "error",
                                    "ext", Map.of("error", error.getMessage())));
                        },
                        () -> {
                            memoryService.add(AGENT_TYPE, isolationKey, "assistant:decision",
                                    stripXmlTags(full.toString()));
                            socketIoHandler.emit(session, namespace, "content:update", Map.of(
                                    "messageId", messageId, "contentId", contentId,
                                    "type", "text", "data", (Object) null, "status", "complete"));
                            socketIoHandler.emit(session, namespace, "message:update", Map.of(
                                    "id", messageId, "status", "complete"));
                        });
    }

    private String buildProjectInfo(String projectId) {
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
