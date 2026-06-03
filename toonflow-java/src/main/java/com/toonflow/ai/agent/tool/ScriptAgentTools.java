package com.toonflow.ai.agent.tool;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.toonflow.ai.AiService;
import com.toonflow.ai.MemoryService;
import com.toonflow.entity.ONovel;
import com.toonflow.entity.OScript;
import com.toonflow.mapper.ONovelMapper;
import com.toonflow.mapper.OScriptMapper;
import com.toonflow.websocket.SocketIoWebSocketHandler;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

@Slf4j
public class ScriptAgentTools {

    private final ONovelMapper novelMapper;
    private final OScriptMapper scriptMapper;
    private final String projectId;

    // Sub-agent support (may be null if not needed)
    private final AiService aiService;
    private final MemoryService memoryService;
    private final SocketIoWebSocketHandler socketIoHandler;
    private final WebSocketSession session;
    private final String namespace;
    private final String isolationKey;
    private final String dataDir;
    /** [messageId, contentId] of the currently active parent message */
    private final AtomicReference<String[]> msgState;

    /** Full constructor (with sub-agent support) */
    public ScriptAgentTools(ONovelMapper novelMapper, OScriptMapper scriptMapper, String projectId,
                            AiService aiService, MemoryService memoryService,
                            SocketIoWebSocketHandler socketIoHandler, WebSocketSession session,
                            String namespace, String isolationKey, String dataDir,
                            AtomicReference<String[]> msgState) {
        this.novelMapper = novelMapper;
        this.scriptMapper = scriptMapper;
        this.projectId = projectId;
        this.aiService = aiService;
        this.memoryService = memoryService;
        this.socketIoHandler = socketIoHandler;
        this.session = session;
        this.namespace = namespace;
        this.isolationKey = isolationKey;
        this.dataDir = dataDir;
        this.msgState = msgState;
    }

    // ──────────────────────────────────────────────
    // Data query tools
    // ──────────────────────────────────────────────

    @Tool(description = "获取指定章节编号的章节事件")
    public String getNovelEvents(
            @ToolParam(description = "章节编号列表") List<Integer> chapterIndexs) {
        log.info("[tool] getNovelEvents {}", chapterIndexs);
        List<ONovel> data = novelMapper.selectList(
                new LambdaQueryWrapper<ONovel>()
                        .eq(ONovel::getProjectId, projectId)
                        .in(ONovel::getChapterIndex, chapterIndexs));
        if (data.isEmpty()) return "无数据";
        return data.stream()
                .map(i -> "第" + i.getChapterIndex() + "章，标题:" + i.getChapter()
                        + "，事件:" + (i.getEvent() != null ? i.getEvent() : ""))
                .collect(Collectors.joining("\n"));
    }

    @Tool(description = "获取小说指定章节的原始文本内容")
    public String getNovelText(
            @ToolParam(description = "章节编号") Integer chapterIndex) {
        log.info("[tool] getNovelText {}", chapterIndex);
        ONovel novel = novelMapper.selectOne(
                new LambdaQueryWrapper<ONovel>()
                        .eq(ONovel::getProjectId, projectId)
                        .eq(ONovel::getChapterIndex, chapterIndex)
                        .last("LIMIT 1"));
        if (novel == null || novel.getChapterData() == null) return "无数据";
        return novel.getChapterData();
    }

    @Tool(description = "根据剧本 id 列表获取剧本内容")
    public String getScriptContent(
            @ToolParam(description = "剧本 id 列表") List<String> ids) {
        log.info("[tool] getScriptContent {}", ids);
        if (ids == null || ids.isEmpty()) return "无数据";
        List<OScript> scripts = scriptMapper.selectList(
                new LambdaQueryWrapper<OScript>().in(OScript::getId, ids));
        if (scripts.isEmpty()) return "无数据";
        return scripts.stream()
                .map(s -> "剧本《" + s.getName() + "》:\n" + (s.getContent() != null ? s.getContent() : ""))
                .collect(Collectors.joining("\n\n"));
    }

    @Tool(description = "保存生成的剧本到项目")
    public String saveScript(
            @ToolParam(description = "剧本名称") String name,
            @ToolParam(description = "剧本内容") String content) {
        log.info("[tool] saveScript {}", name);
        OScript script = new OScript();
        script.setName(name);
        script.setContent(content);
        script.setProjectId(projectId);
        script.setCreateTime(System.currentTimeMillis());
        scriptMapper.insert(script);
        return "剧本《" + name + "》已保存，id=" + script.getId();
    }

    // ──────────────────────────────────────────────
    // Sub-agent tools
    // ──────────────────────────────────────────────

    @Tool(description = "运行执行subAgent来完成故事骨架相关任务")
    public String runSubAgentStorySkeleton(
            @ToolParam(description = "交给子Agent的任务简约描述，100字以内") String prompt) {
        log.info("[tool] runSubAgentStorySkeleton");
        String skill = readSkill("script_execution_skeleton.md");
        String formatPrompt = "\n你必须使用如下XML格式写入工作区：\n<storySkeleton>故事骨架内容</storySkeleton>";
        return runSubAgent("scriptAgent:storySkeletonAgent", "编剧",
                skill + formatPrompt, prompt + formatPrompt,
                "assistant:execution:storySkeleton");
    }

    @Tool(description = "运行执行subAgent来完成改编策略相关任务")
    public String runSubAgentAdaptationStrategy(
            @ToolParam(description = "交给子Agent的任务简约描述，100字以内") String prompt) {
        log.info("[tool] runSubAgentAdaptationStrategy");
        String skill = readSkill("script_execution_adaptation.md");
        String formatPrompt = "\n你必须使用如下XML格式写入工作区：\n<adaptationStrategy>改编策略内容</adaptationStrategy>";
        return runSubAgent("scriptAgent:adaptationStrategyAgent", "编剧",
                skill + formatPrompt, prompt + formatPrompt,
                "assistant:execution:adaptationStrategy");
    }

    @Tool(description = "运行执行subAgent来完成剧本相关任务")
    public String runSubAgentScript(
            @ToolParam(description = "交给子Agent的任务简约描述，100字以内") String prompt) {
        log.info("[tool] runSubAgentScript");
        String skill = readSkill("script_execution_script.md");
        List<OScript> scriptList = scriptMapper.selectList(
                new LambdaQueryWrapper<OScript>().eq(OScript::getProjectId, projectId)
                        .select(OScript::getId, OScript::getName));
        Long novelCount = novelMapper.selectCount(
                new LambdaQueryWrapper<ONovel>().eq(ONovel::getProjectId, projectId));
        String scriptPrompt = "## 可用剧本(ID:名称)\n" + scriptList.stream()
                .map(s -> s.getId() + ":" + (s.getName() != null ? s.getName().replaceAll("[,:]", "") : ""))
                .collect(Collectors.joining(",")) + "\n";
        String formatPrompt = "\n你必须使用如下XML格式写入工作区：\nXML不得添加任何额外标签"
                + "<scriptItem name=\"剧本名称\">剧本内容</scriptItem>";
        return runSubAgent("scriptAgent:scriptAgent", "编剧",
                skill + formatPrompt,
                scriptPrompt + "章节数量：" + novelCount + "章",
                prompt + formatPrompt,
                "assistant:execution:script");
    }

    @Tool(description = "运行监督层subAgent执行独立任务，完成后返回结果")
    public String runSupervisionAgent(
            @ToolParam(description = "交给子Agent的任务简约描述，100字以内") String prompt) {
        log.info("[tool] runSupervisionAgent");
        String skill = readSkill("script_agent_supervision.md");
        return runSubAgent("scriptAgent:supervisionAgent", "编辑",
                skill, prompt, "assistant:supervision");
    }

    // ──────────────────────────────────────────────
    // Internal helpers
    // ──────────────────────────────────────────────

    private String runSubAgent(String agentKey, String name,
                               String systemPrompt, String userPrompt, String memoryKey) {
        return runSubAgent(agentKey, name, systemPrompt, null, userPrompt, memoryKey);
    }

    private String runSubAgent(String agentKey, String name,
                               String systemPrompt, String assistantContext,
                               String userPrompt, String memoryKey) {
        // 1. Complete current parent message
        String[] cur = msgState.get();
        completeContent(cur[0], cur[1]);
        completeMessage(cur[0]);

        // 2. Create sub-message on frontend
        String subMsgId = UUID.randomUUID().toString();
        String subContentId = UUID.randomUUID().toString();
        emitNewMessage(subMsgId, name);
        emitContentAdd(subMsgId, subContentId);

        // 3. Build messages for sub-agent
        List<AiService.ChatMessage> msgs = new ArrayList<>();
        msgs.add(new AiService.ChatMessage("system", systemPrompt));
        if (assistantContext != null) {
            msgs.add(new AiService.ChatMessage("assistant", assistantContext));
        }
        msgs.add(new AiService.ChatMessage("user", userPrompt));

        // 4. Stream sub-agent response (blocking until complete)
        StringBuilder sb = new StringBuilder();
        try {
            aiService.streamText(agentKey, msgs)
                    .doOnNext(chunk -> {
                        sb.append(chunk);
                        Map<String, Object> payload = new HashMap<>();
                        payload.put("messageId", subMsgId);
                        payload.put("contentId", subContentId);
                        payload.put("type", "text");
                        payload.put("data", chunk);
                        payload.put("strategy", "append");
                        payload.put("status", "streaming");
                        socketIoHandler.emit(session, namespace, "content:update", payload);
                    })
                    .blockLast();
        } catch (Exception e) {
            log.error("[subAgent] {} 执行失败", agentKey, e);
            Map<String, Object> errPayload = new HashMap<>();
            errPayload.put("messageId", subMsgId);
            errPayload.put("contentId", subContentId);
            errPayload.put("type", "text");
            errPayload.put("data", null);
            errPayload.put("status", "error");
            socketIoHandler.emit(session, namespace, "content:update", errPayload);
            socketIoHandler.emit(session, namespace, "message:update",
                    Map.of("id", subMsgId, "status", "error",
                            "ext", Map.of("error", e.getMessage() != null ? e.getMessage() : "子Agent执行失败")));
        }

        // 5. Complete sub-message
        completeContent(subMsgId, subContentId);
        completeMessage(subMsgId);

        // 6. Save to memory
        String responseText = stripXmlTags(sb.toString());
        if (!responseText.isBlank() && memoryService != null) {
            memoryService.add("scriptAgent", isolationKey, memoryKey, responseText);
        }

        // 7. Create new parent message for the decision agent to continue into
        String newMsgId = UUID.randomUUID().toString();
        String newContentId = UUID.randomUUID().toString();
        emitNewMessage(newMsgId, "统筹");
        emitContentAdd(newMsgId, newContentId);
        msgState.set(new String[]{newMsgId, newContentId});

        return sb.toString();
    }

    private void emitNewMessage(String msgId, String name) {
        socketIoHandler.emit(session, namespace, "message", Map.of(
                "id", msgId, "role", "assistant", "name", name,
                "status", "pending", "datetime", new Date().toString(),
                "content", new ArrayList<>()));
    }

    private void emitContentAdd(String msgId, String contentId) {
        socketIoHandler.emit(session, namespace, "content:add", Map.of(
                "messageId", msgId,
                "content", Map.of("type", "text", "id", contentId, "data", "", "status", "pending")));
    }

    private void completeContent(String msgId, String contentId) {
        Map<String, Object> p = new HashMap<>();
        p.put("messageId", msgId);
        p.put("contentId", contentId);
        p.put("type", "text");
        p.put("data", null);
        p.put("status", "complete");
        socketIoHandler.emit(session, namespace, "content:update", p);
    }

    private void completeMessage(String msgId) {
        socketIoHandler.emit(session, namespace, "message:update",
                Map.of("id", msgId, "status", "complete"));
    }

    private String readSkill(String filename) {
        try {
            return Files.readString(Paths.get(dataDir, "skills", filename));
        } catch (IOException e) {
            log.warn("无法读取技能文件 {}: {}", filename, e.getMessage());
            return "";
        }
    }

    private String stripXmlTags(String text) {
        if (text == null) return "";
        return text.replaceAll("<[^>]+>", "").trim();
    }
}
