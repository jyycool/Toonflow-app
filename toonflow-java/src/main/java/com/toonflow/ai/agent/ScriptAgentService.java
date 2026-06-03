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
public class ScriptAgentService {

    private final AiService aiService;
    private final MemoryService memoryService;
    private final OProjectMapper projectMapper;
    private final ONovelMapper novelMapper;
    private final OScriptMapper scriptMapper;
    private final SocketIoWebSocketHandler socketIoHandler;

    @Value("${toonflow.data-dir:${user.home}/.toonflow}")
    private String dataDir;

    private static final String AGENT_TYPE = "scriptAgent";

    public void runDecision(WebSocketSession session, String namespace, String sid,
                            String isolationKey, String projectId, String userText) {
        memoryService.add(AGENT_TYPE, isolationKey, "user", userText);

        MemoryService.MemoryContext mem = memoryService.get(isolationKey, userText);
        String memPrompt = memoryService.buildPrompt(mem);
        String projectInfo = buildProjectInfo(projectId);
        String systemPrompt = loadDecisionPrompt();

        List<AiService.ChatMessage> messages = List.of(
                new AiService.ChatMessage("system", systemPrompt),
                new AiService.ChatMessage("assistant", projectInfo + "\n" + memPrompt),
                new AiService.ChatMessage("user", userText));

        // Initial message
        String initMsgId = UUID.randomUUID().toString();
        String initContentId = UUID.randomUUID().toString();

        socketIoHandler.emit(session, namespace, "message", Map.of(
                "id", initMsgId, "role", "assistant", "name", "统筹",
                "status", "pending", "datetime", new Date().toString(),
                "content", new ArrayList<>()));
        socketIoHandler.emit(session, namespace, "content:add", Map.of(
                "messageId", initMsgId,
                "content", Map.of("type", "text", "id", initContentId, "data", "", "status", "pending")));

        // Mutable state: sub-agent tools update this when they create a new parent message
        AtomicReference<String[]> msgState = new AtomicReference<>(new String[]{initMsgId, initContentId});

        ScriptAgentTools tools = new ScriptAgentTools(
                novelMapper, scriptMapper, projectId,
                aiService, memoryService, socketIoHandler,
                session, namespace, isolationKey, dataDir, msgState);

        StringBuilder fullResponse = new StringBuilder();

        aiService.streamTextWithTools(AGENT_TYPE + ":decisionAgent", messages, tools)
                .subscribe(
                        chunk -> {
                            fullResponse.append(chunk);
                            String[] cur = msgState.get();
                            socketIoHandler.emit(session, namespace, "content:update", Map.of(
                                    "messageId", cur[0], "contentId", cur[1],
                                    "type", "text", "data", chunk,
                                    "strategy", "append", "status", "streaming"));
                        },
                        error -> {
                            log.error("剧本 Agent 执行失败", error);
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
                                    stripXmlTags(fullResponse.toString()));
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

    private String buildProjectInfo(String projectId) {
        OProject project = projectMapper.selectById(projectId);
        Long novelCount = novelMapper.selectCount(
                new LambdaQueryWrapper<ONovel>().eq(ONovel::getProjectId, projectId));
        return String.join("\n",
                "## 项目信息",
                "小说名称：" + nv(project, OProject::getName),
                "小说类型：" + nv(project, OProject::getType),
                "小说简介：" + nv(project, OProject::getIntro),
                "目标改编影视视觉手册|画风：" + nv(project, OProject::getArtStyle),
                "目标改编视频画幅：" + (project != null && project.getVideoRatio() != null ? project.getVideoRatio() : "16:9"),
                "章节数量：" + novelCount + "章");
    }

    @FunctionalInterface
    interface Getter { String get(OProject p); }

    private String nv(OProject p, Getter g) {
        return (p != null && g.get(p) != null) ? g.get(p) : "未知";
    }

    private String loadDecisionPrompt() {
        try {
            return Files.readString(Paths.get(dataDir, "skills", "script_agent_decision.md"));
        } catch (IOException e) {
            log.warn("无法读取 script_agent_decision.md: {}", e.getMessage());
            return "你是 Toonflow 的剧本改编决策 Agent，协助用户将小说改编为短剧/漫剧剧本。";
        }
    }

    private String stripXmlTags(String text) {
        if (text == null) return "";
        return text.replaceAll("<[^>]+>", "").trim();
    }
}
