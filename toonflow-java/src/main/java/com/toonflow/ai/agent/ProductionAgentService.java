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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

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

    @Value("${toonflow.data-dir:${user.home}/.toonflow}")
    private String dataDir;

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
        String projectInfo = buildProjectInfo(projectId, scriptId);

        List<AiService.ChatMessage> messages = List.of(
                new AiService.ChatMessage("system", loadDecisionPrompt()),
                new AiService.ChatMessage("assistant", projectInfo + "\n" + memPrompt),
                new AiService.ChatMessage("user", userText));

        // Initial message
        String initMsgId = UUID.randomUUID().toString();
        String initContentId = UUID.randomUUID().toString();

        socketIoHandler.emit(session, namespace, "message", Map.of(
                "id", initMsgId, "role", "assistant", "name", "视频策划",
                "status", "pending", "datetime", new Date().toString(),
                "content", new ArrayList<>()));
        socketIoHandler.emit(session, namespace, "content:add", Map.of(
                "messageId", initMsgId,
                "content", Map.of("type", "text", "id", initContentId, "data", "", "status", "pending")));

        AtomicReference<String[]> msgState = new AtomicReference<>(new String[]{initMsgId, initContentId});

        OProject project = projectMapper.selectById(projectId);
        String imageModel = project != null ? project.getImageModel() : null;

        ProductionAgentTools tools = new ProductionAgentTools(
                assetsMapper, scriptAssetsMapper, storyboardMapper, imageFlowMapper,
                mediaGenerationService, projectId, scriptId, imageModel,
                aiService, memoryService, socketIoHandler,
                session, namespace, isolationKey, dataDir, msgState);

        StringBuilder full = new StringBuilder();

        aiService.streamTextWithTools(AGENT_TYPE + ":decisionAgent", messages, tools)
                .subscribe(
                        chunk -> {
                            full.append(chunk);
                            String[] cur = msgState.get();
                            socketIoHandler.emit(session, namespace, "content:update", Map.of(
                                    "messageId", cur[0], "contentId", cur[1],
                                    "type", "text", "data", chunk,
                                    "strategy", "append", "status", "streaming"));
                        },
                        error -> {
                            log.error("制作 Agent 执行失败", error);
                            String[] cur = msgState.get();
                            Map<String, Object> errContent = new HashMap<>();
                            errContent.put("messageId", cur[0]);
                            errContent.put("contentId", cur[1]);
                            errContent.put("type", "text");
                            errContent.put("data", null);
                            errContent.put("status", "error");
                            socketIoHandler.emit(session, namespace, "content:update", errContent);
                            socketIoHandler.emit(session, namespace, "message:update", Map.of(
                                    "id", cur[0], "status", "error",
                                    "ext", Map.of("error", error.getMessage() != null ? error.getMessage() : "未知错误")));
                        },
                        () -> {
                            memoryService.add(AGENT_TYPE, isolationKey, "assistant:decision",
                                    stripXmlTags(full.toString()));
                            String[] cur = msgState.get();
                            Map<String, Object> doneContent = new HashMap<>();
                            doneContent.put("messageId", cur[0]);
                            doneContent.put("contentId", cur[1]);
                            doneContent.put("type", "text");
                            doneContent.put("data", null);
                            doneContent.put("status", "complete");
                            socketIoHandler.emit(session, namespace, "content:update", doneContent);
                            socketIoHandler.emit(session, namespace, "message:update",
                                    Map.of("id", cur[0], "status", "complete"));
                        });
    }

    private String buildProjectInfo(String projectId, String scriptId) {
        OProject project = projectMapper.selectById(projectId);
        if (project == null) return "## 项目信息\n（项目不存在）";
        // Parse "vendorId:modelName" → only take the model name part
        String imageModelName = parseModelName(project.getImageModel());
        String videoModelName = parseModelName(project.getVideoModel());
        String modelInfo = "项目使用的模型如下：\n图像模型：" + imageModelName + "\n视频模型：" + videoModelName;
        StringBuilder sb = new StringBuilder();
        sb.append("## 项目信息\n");
        sb.append("项目ID：").append(nv(projectId)).append("\n");
        sb.append("项目名称：").append(nv(project.getName())).append("\n");
        sb.append("项目类型：").append(nv(project.getType())).append("\n");
        sb.append("画风：").append(nv(project.getArtStyle())).append("\n");
        sb.append("画幅：").append(nv(project.getVideoRatio())).append("\n");
        if (scriptId != null) sb.append("当前剧本ID：").append(scriptId).append("\n");
        sb.append(modelInfo);
        return sb.toString();
    }

    private String parseModelName(String vendorModel) {
        if (vendorModel == null) return "未知";
        int idx = vendorModel.indexOf(':');
        return idx >= 0 ? vendorModel.substring(idx + 1) : vendorModel;
    }

    private String loadDecisionPrompt() {
        try {
            return Files.readString(Paths.get(dataDir, "skills", "production_agent_decision.md"));
        } catch (IOException e) {
            log.warn("无法读取 production_agent_decision.md: {}", e.getMessage());
            return "你是 Toonflow 的制作总导演决策 Agent，负责把剧本制作成视频成片。";
        }
    }

    private String nv(String s) { return s != null ? s : "未知"; }

    private String stripXmlTags(String text) {
        return text == null ? "" : text.replaceAll("<[^>]+>", "").trim();
    }
}
