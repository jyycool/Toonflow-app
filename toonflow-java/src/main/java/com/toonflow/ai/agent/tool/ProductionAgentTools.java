package com.toonflow.ai.agent.tool;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.toonflow.ai.AiService;
import com.toonflow.ai.MemoryService;
import com.toonflow.ai.vendor.MediaGenerationService;
import com.toonflow.entity.OAssets;
import com.toonflow.entity.OImageFlow;
import com.toonflow.entity.OScriptAssets;
import com.toonflow.entity.OStoryboard;
import com.toonflow.mapper.OAssetsMapper;
import com.toonflow.mapper.OImageFlowMapper;
import com.toonflow.mapper.OScriptAssetsMapper;
import com.toonflow.mapper.OStoryboardMapper;
import com.toonflow.websocket.SocketIoWebSocketHandler;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.web.socket.WebSocketSession;
import reactor.core.scheduler.Schedulers;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

@Slf4j
public class ProductionAgentTools {

    private final OAssetsMapper assetsMapper;
    private final OScriptAssetsMapper scriptAssetsMapper;
    private final OStoryboardMapper storyboardMapper;
    private final OImageFlowMapper imageFlowMapper;
    private final MediaGenerationService mediaGenerationService;
    private final String projectId;
    private final String scriptId;
    private final String imageModel;

    // Sub-agent support
    private final AiService aiService;
    private final MemoryService memoryService;
    private final SocketIoWebSocketHandler socketIoHandler;
    private final WebSocketSession session;
    private final String namespace;
    private final String isolationKey;
    private final String dataDir;
    private final AtomicReference<String[]> msgState;

    public ProductionAgentTools(OAssetsMapper assetsMapper, OScriptAssetsMapper scriptAssetsMapper,
                                OStoryboardMapper storyboardMapper, OImageFlowMapper imageFlowMapper,
                                MediaGenerationService mediaGenerationService,
                                String projectId, String scriptId, String imageModel,
                                AiService aiService, MemoryService memoryService,
                                SocketIoWebSocketHandler socketIoHandler, WebSocketSession session,
                                String namespace, String isolationKey, String dataDir,
                                AtomicReference<String[]> msgState) {
        this.assetsMapper = assetsMapper;
        this.scriptAssetsMapper = scriptAssetsMapper;
        this.storyboardMapper = storyboardMapper;
        this.imageFlowMapper = imageFlowMapper;
        this.mediaGenerationService = mediaGenerationService;
        this.projectId = projectId;
        this.scriptId = scriptId;
        this.imageModel = imageModel;
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
    // Data / operation tools
    // ──────────────────────────────────────────────

    @Tool(description = "获取图片流程数据")
    public String getFlowData(@ToolParam(description = "流程 id") String flowId) {
        log.info("[tool] getFlowData {}", flowId);
        OImageFlow flow = imageFlowMapper.selectById(flowId);
        return flow != null && flow.getFlowData() != null ? flow.getFlowData() : "无数据";
    }

    @Tool(description = "新增或更新衍生资产（id 为空则新增）")
    public String addDeriveAsset(
            @ToolParam(description = "关联的资产 ID") String assetsId,
            @ToolParam(description = "衍生资产 ID，新增时传 null") String id,
            @ToolParam(description = "衍生资产名称") String name,
            @ToolParam(description = "衍生资产描述") String desc) {
        log.info("[tool] addDeriveAsset name={} id={}", name, id);
        // Normalise: LLM sometimes sends "null" string
        String normalizedId = ("null".equals(id) || "".equals(id)) ? null : id;

        OAssets parent = assetsMapper.selectById(assetsId);
        if (parent == null) return "关联的资产不存在";

        if (normalizedId != null) {
            OAssets asset = assetsMapper.selectById(normalizedId);
            if (asset == null) return "衍生资产不存在";
            asset.setAssetsId(assetsId);
            asset.setName(name);
            asset.setType(parent.getType());
            asset.setDescribe(desc);
            assetsMapper.updateById(asset);
            return "已更新衍生资产，ID: " + normalizedId;
        } else {
            OAssets asset = new OAssets();
            asset.setAssetsId(assetsId);
            asset.setProjectId(projectId);
            asset.setName(name);
            asset.setType(parent.getType());
            asset.setDescribe(desc);
            asset.setStartTime(System.currentTimeMillis());
            assetsMapper.insert(asset);
            if (scriptId != null) {
                OScriptAssets sa = new OScriptAssets();
                sa.setScriptId(scriptId);
                sa.setAssetId(asset.getId());
                scriptAssetsMapper.insert(sa);
            }
            return "已新增衍生资产，ID: " + asset.getId();
        }
    }

    @Tool(description = "删除衍生资产")
    public String delDeriveAsset(
            @ToolParam(description = "衍生资产 ID") String id) {
        log.info("[tool] delDeriveAsset {}", id);
        assetsMapper.deleteById(id);
        if (scriptId != null) {
            scriptAssetsMapper.delete(new LambdaQueryWrapper<OScriptAssets>()
                    .eq(OScriptAssets::getScriptId, scriptId)
                    .eq(OScriptAssets::getAssetId, id));
        }
        return "已删除衍生资产，ID: " + id;
    }

    @Tool(description = "生成衍生资产图片")
    public String generateDeriveAsset(
            @ToolParam(description = "需要生成的衍生资产 ID 列表") List<String> ids) {
        log.info("[tool] generateDeriveAsset {}", ids);
        if (ids == null || ids.isEmpty()) return "无可生成的资产";
        int success = 0;
        for (String id : ids) {
            OAssets asset = assetsMapper.selectById(id);
            if (asset == null) continue;
            try {
                mediaGenerationService.generateImage(imageModel, asset.getPrompt(), "1024x1024");
                success++;
            } catch (Exception e) {
                log.error("衍生资产 {} 生成失败: {}", id, e.getMessage());
            }
        }
        return "已生成 " + success + "/" + ids.size() + " 个衍生资产图片";
    }

    @Tool(description = "生成分镜图片，传入真实的分镜 ID 列表，支持批量")
    public String generateStoryboard(
            @ToolParam(description = "分镜 ID 列表") List<String> ids) {
        log.info("[tool] generateStoryboard {}", ids);
        if (ids == null || ids.isEmpty()) return "无可生成的分镜";
        for (String id : ids) {
            OStoryboard sb = storyboardMapper.selectById(id);
            if (sb != null) {
                sb.setState("生成中");
                sb.setShouldGenerateImage(1);
                storyboardMapper.updateById(sb);
            }
        }
        return "已提交 " + ids.size() + " 个分镜的图片生成任务";
    }

    // ──────────────────────────────────────────────
    // Sub-agent tools
    // ──────────────────────────────────────────────

    @Tool(description = "运行执行subAgent来完成衍生资产分析与信息写入相关任务")
    public String runSubAgentDeriveAssets(
            @ToolParam(description = "交给子Agent的任务简约描述，100字以内") String prompt) {
        log.info("[tool] runSubAgentDeriveAssets");
        return runSubAgent("productionAgent:deriveAssetsAgent", "执行导演",
                readSkill("production_execution_derive_assets.md"),
                null, prompt, "assistant:execution");
    }

    @Tool(description = "运行执行subAgent来完成衍生资产图片生成相关任务")
    public String runSubAgentGenerateAssets(
            @ToolParam(description = "交给子Agent的任务简约描述，100字以内") String prompt) {
        log.info("[tool] runSubAgentGenerateAssets");
        return runSubAgent("productionAgent:generateAssetsAgent", "执行导演",
                readSkill("production_execution_generate_assets.md"),
                null, prompt, "assistant:execution");
    }

    @Tool(description = "运行执行subAgent来完成导演规划相关任务")
    public String runSubAgentDirectorPlan(
            @ToolParam(description = "交给子Agent的任务简约描述，100字以内") String prompt) {
        log.info("[tool] runSubAgentDirectorPlan");
        String formatPrompt = "\n你必须使用如下XML格式写入工作区：\n```\n<scriptPlan>内容</scriptPlan>\n```";
        return runSubAgent("productionAgent:directorPlanAgent", "执行导演",
                readSkill("production_execution_director_plan.md") + formatPrompt,
                null, prompt + formatPrompt, "assistant:execution");
    }

    @Tool(description = "运行执行subAgent来完成分镜图生成相关任务")
    public String runSubAgentStoryboardGen(
            @ToolParam(description = "交给子Agent的任务简约描述，100字以内") String prompt) {
        log.info("[tool] runSubAgentStoryboardGen");
        return runSubAgent("productionAgent:storyboardGenAgent", "执行导演",
                readSkill("production_execution_storyboard_gen.md"),
                null, prompt, "assistant:execution");
    }

    @Tool(description = "运行执行subAgent来完成分镜面板写入相关任务")
    public String runSubAgentStoryboardPanel(
            @ToolParam(description = "交给子Agent的任务简约描述，100字以内") String prompt) {
        log.info("[tool] runSubAgentStoryboardPanel");
        String formatPrompt = "\n你必须使用如下XML格式写入工作区：\n```\n" +
                "<storyboardItem videoDesc='视频描述' prompt=提示词内容 track='分组' " +
                "shouldGenerateImage='true/false' duration='视频推荐时间' " +
                "associateAssetsIds='[该分镜所需的资产ID列表]'></storyboardItem>\n```";
        return runSubAgent("productionAgent:storyboardPanelAgent", "执行导演",
                readSkill("production_execution_storyboard_panel.md") + formatPrompt,
                null, prompt + formatPrompt, "assistant:execution");
    }

    @Tool(description = "运行执行subAgent来完成分镜表构建相关任务")
    public String runSubAgentStoryboardTable(
            @ToolParam(description = "交给子Agent的任务简约描述，100字以内") String prompt) {
        log.info("[tool] runSubAgentStoryboardTable");
        String formatPrompt = "\n你必须使用如下XML格式写入工作区：\n```\n<storyboardTable>内容</storyboardTable>\n```";
        return runSubAgent("productionAgent:storyboardTableAgent", "执行导演",
                readSkill("production_execution_storyboard_table.md") + formatPrompt,
                null, prompt + formatPrompt, "assistant:execution");
    }

    @Tool(description = "运行监督层subAgent执行独立任务，完成后返回结果")
    public String runSupervisionAgent(
            @ToolParam(description = "交给子Agent的任务简约描述，100字以内") String prompt) {
        log.info("[tool] runSupervisionAgent");
        return runSubAgent("productionAgent:supervisionAgent", "监制",
                readSkill("production_agent_supervision.md"),
                null, prompt, "assistant:supervision");
    }

    // ──────────────────────────────────────────────
    // Internal helpers
    // ──────────────────────────────────────────────

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

        // 3. Build messages
        List<AiService.ChatMessage> msgs = new ArrayList<>();
        msgs.add(new AiService.ChatMessage("system", systemPrompt));
        if (assistantContext != null) {
            msgs.add(new AiService.ChatMessage("assistant", assistantContext));
        }
        msgs.add(new AiService.ChatMessage("user", userPrompt));

        // 4. Stream sub-agent response.
        // Use CountDownLatch + subscribeOn(boundedElastic) to avoid blocking the
        // Reactor event-loop thread that Spring AI uses for tool execution.
        StringBuilder sb = new StringBuilder();
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<Throwable> subError = new AtomicReference<>();

        aiService.streamText(agentKey, msgs)
                .subscribeOn(Schedulers.boundedElastic())
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
                .doOnError(subError::set)
                .doFinally(signal -> latch.countDown())
                .subscribe();

        try {
            latch.await(10, TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        if (subError.get() != null) {
            log.error("[subAgent] {} 执行失败", agentKey, subError.get());
            Map<String, Object> errPayload = new HashMap<>();
            errPayload.put("messageId", subMsgId);
            errPayload.put("contentId", subContentId);
            errPayload.put("type", "text");
            errPayload.put("data", null);
            errPayload.put("status", "error");
            socketIoHandler.emit(session, namespace, "content:update", errPayload);
            socketIoHandler.emit(session, namespace, "message:update",
                    Map.of("id", subMsgId, "status", "error",
                            "ext", Map.of("error", subError.get().getMessage() != null
                                    ? subError.get().getMessage() : "子Agent执行失败")));
        }

        // 5. Complete sub-message
        completeContent(subMsgId, subContentId);
        completeMessage(subMsgId);

        // 6. Save to memory
        String responseText = stripXmlTags(sb.toString());
        if (!responseText.isBlank() && memoryService != null) {
            memoryService.add("productionAgent", isolationKey, memoryKey, responseText);
        }

        // 7. Create new parent message for the decision agent to continue into
        String newMsgId = UUID.randomUUID().toString();
        String newContentId = UUID.randomUUID().toString();
        emitNewMessage(newMsgId, "视频策划");
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
