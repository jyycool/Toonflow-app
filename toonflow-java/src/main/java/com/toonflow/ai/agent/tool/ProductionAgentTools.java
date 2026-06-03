package com.toonflow.ai.agent.tool;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.toonflow.ai.AiService;
import com.toonflow.ai.MemoryService;
import com.toonflow.ai.vendor.MediaGenerationService;
import com.toonflow.entity.*;
import com.toonflow.mapper.*;
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
import java.util.stream.Collectors;

@Slf4j
public class ProductionAgentTools {

    private final OAssetsMapper assetsMapper;
    private final OScriptAssetsMapper scriptAssetsMapper;
    private final OStoryboardMapper storyboardMapper;
    private final OImageFlowMapper imageFlowMapper;
    private final OAgentWorkDataMapper agentWorkDataMapper;
    private final OImageMapper imageMapper;
    private final OAssets2StoryboardMapper assets2StoryboardMapper;
    private final OScriptMapper scriptMapper;
    private final MediaGenerationService mediaGenerationService;
    private final ObjectMapper objectMapper = new ObjectMapper();
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
                                OAgentWorkDataMapper agentWorkDataMapper, OImageMapper imageMapper,
                                OAssets2StoryboardMapper assets2StoryboardMapper, OScriptMapper scriptMapper,
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
        this.agentWorkDataMapper = agentWorkDataMapper;
        this.imageMapper = imageMapper;
        this.assets2StoryboardMapper = assets2StoryboardMapper;
        this.scriptMapper = scriptMapper;
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

    @Tool(name = "get_flowData",
          description = "获取工作区数据。key 可选值: script(剧本内容), scriptPlan(拍摄计划), assets(资产列表), storyboard(分镜面板), storyboardTable(分镜表)")
    public String getFlowData(@ToolParam(description = "数据key: script | scriptPlan | assets | storyboard | storyboardTable") String key) {
        log.info("[tool] getFlowData key={}", key);
        try {
            // Load saved workspace data from o_agentWorkData
            OAgentWorkData workData = agentWorkDataMapper.selectOne(
                    new LambdaQueryWrapper<OAgentWorkData>()
                            .eq(OAgentWorkData::getProjectId, projectId)
                            .eq(OAgentWorkData::getEpisodesId, scriptId)
                            .eq(OAgentWorkData::getKey, "productionAgent"));

            // For assets and storyboard, always build from live DB data
            if ("assets".equals(key)) {
                return buildAssetsJson();
            }
            if ("storyboard".equals(key)) {
                return buildStoryboardJson();
            }
            // script content always comes from o_script directly
            if ("script".equals(key)) {
                if (scriptId == null) return "（暂无剧本）";
                OScript script = scriptMapper.selectById(scriptId);
                String content = script != null && script.getContent() != null ? script.getContent() : "";
                return content.isEmpty() ? "（暂无剧本内容）" : content;
            }

            // For scriptPlan / storyboardTable: read from saved workData
            if (workData == null || workData.getData() == null) {
                return "（暂无数据）";
            }
            Map<String, Object> dataMap = objectMapper.readValue(workData.getData(), new TypeReference<>() {});
            Object val = dataMap.get(key);
            if (val == null) return "（暂无数据）";
            return val instanceof String s ? s : objectMapper.writeValueAsString(val);
        } catch (Exception e) {
            log.error("[tool] getFlowData error", e);
            return "获取数据失败: " + e.getMessage();
        }
    }

    private String buildAssetsJson() throws Exception {
        // Load script-linked assets
        List<OScriptAssets> relations = scriptId != null
                ? scriptAssetsMapper.selectList(new LambdaQueryWrapper<OScriptAssets>().eq(OScriptAssets::getScriptId, scriptId))
                : List.of();
        List<String> assetIds = relations.stream().map(OScriptAssets::getAssetId).distinct().collect(Collectors.toList());
        if (assetIds.isEmpty()) return "[]";

        List<OAssets> parents = assetsMapper.selectList(
                new LambdaQueryWrapper<OAssets>().in(OAssets::getId, assetIds).isNull(OAssets::getAssetsId));
        List<OAssets> children = assetsMapper.selectList(
                new LambdaQueryWrapper<OAssets>().in(OAssets::getAssetsId, assetIds));

        // Batch load images
        Set<String> imgIds = new HashSet<>();
        parents.forEach(a -> { if (a.getImageId() != null) imgIds.add(a.getImageId()); });
        children.forEach(a -> { if (a.getImageId() != null) imgIds.add(a.getImageId()); });
        Map<String, String> imgPathMap = imgIds.isEmpty() ? Map.of() :
                imageMapper.selectList(new LambdaQueryWrapper<OImage>().in(OImage::getId, imgIds)
                        .select(OImage::getId, OImage::getFilePath))
                        .stream().collect(Collectors.toMap(OImage::getId, i -> i.getFilePath() != null ? i.getFilePath() : ""));

        Map<String, List<Map<String, Object>>> childByParent = children.stream()
                .collect(Collectors.groupingBy(OAssets::getAssetsId,
                        Collectors.mapping(c -> {
                            Map<String, Object> m = new LinkedHashMap<>();
                            m.put("id", c.getId()); m.put("assetsId", c.getAssetsId());
                            m.put("name", c.getName() != null ? c.getName() : "");
                            m.put("type", c.getType()); m.put("prompt", c.getPrompt() != null ? c.getPrompt() : "");
                            m.put("desc", c.getDescribe() != null ? c.getDescribe() : "");
                            m.put("src", c.getImageId() != null ? imgPathMap.getOrDefault(c.getImageId(), "") : "");
                            m.put("state", "未生成");
                            return m;
                        }, Collectors.toList())));

        List<Map<String, Object>> result = parents.stream().map(a -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", a.getId()); m.put("name", a.getName() != null ? a.getName() : "");
            m.put("type", a.getType()); m.put("prompt", a.getPrompt() != null ? a.getPrompt() : "");
            m.put("desc", a.getDescribe() != null ? a.getDescribe() : "");
            m.put("src", a.getImageId() != null ? imgPathMap.getOrDefault(a.getImageId(), "") : "");
            m.put("flowId", a.getFlowId());
            m.put("derive", childByParent.getOrDefault(a.getId(), List.of()));
            return m;
        }).collect(Collectors.toList());
        return objectMapper.writeValueAsString(result);
    }

    private String buildStoryboardJson() throws Exception {
        List<OStoryboard> sbs = storyboardMapper.selectList(
                new LambdaQueryWrapper<OStoryboard>()
                        .eq(OStoryboard::getScriptId, scriptId)
                        .orderByAsc(OStoryboard::getIndex));
        if (sbs.isEmpty()) return "[]";
        List<String> sbIds = sbs.stream().map(OStoryboard::getId).collect(Collectors.toList());
        Map<String, List<String>> a2sMap = new HashMap<>();
        assets2StoryboardMapper.selectList(new LambdaQueryWrapper<OAssets2Storyboard>()
                .in(OAssets2Storyboard::getStoryboardId, sbIds))
                .forEach(r -> a2sMap.computeIfAbsent(r.getStoryboardId(), k -> new ArrayList<>()).add(r.getAssetId()));
        List<Map<String, Object>> result = sbs.stream().map(s -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", s.getId()); m.put("index", s.getIndex());
            m.put("duration", s.getDuration() != null ? Double.parseDouble(s.getDuration()) : 0.0);
            m.put("prompt", s.getPrompt() != null ? s.getPrompt() : "");
            m.put("associateAssetsIds", a2sMap.getOrDefault(s.getId(), List.of()));
            m.put("src", s.getFilePath() != null ? s.getFilePath() : "");
            m.put("state", s.getState()); m.put("videoDesc", s.getVideoDesc());
            m.put("shouldGenerateImage", s.getShouldGenerateImage());
            m.put("reason", s.getReason() != null ? s.getReason() : "");
            m.put("flowId", s.getFlowId());
            return m;
        }).collect(Collectors.toList());
        return objectMapper.writeValueAsString(result);
    }

    @Tool(name = "add_deriveAsset", description = "新增或更新衍生资产（id 为空则新增）")
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

    @Tool(name = "del_deriveAsset", description = "删除衍生资产")
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

    @Tool(name = "generate_deriveAsset", description = "生成衍生资产图片")
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

    @Tool(name = "generate_storyboard", description = "生成分镜图片，传入真实的分镜 ID 列表，支持批量")
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

    @Tool(name = "run_sub_agent_derive_assets", description = "运行执行subAgent来完成衍生资产分析与信息写入相关任务")
    public String runSubAgentDeriveAssets(
            @ToolParam(description = "交给子Agent的任务简约描述，100字以内") String prompt) {
        log.info("[tool] runSubAgentDeriveAssets");
        return runSubAgent("productionAgent:deriveAssetsAgent", "执行导演",
                readSkill("production_execution_derive_assets.md"),
                null, prompt, "assistant:execution");
    }

    @Tool(name = "run_sub_agent_generate_assets", description = "运行执行subAgent来完成衍生资产图片生成相关任务")
    public String runSubAgentGenerateAssets(
            @ToolParam(description = "交给子Agent的任务简约描述，100字以内") String prompt) {
        log.info("[tool] runSubAgentGenerateAssets");
        return runSubAgent("productionAgent:generateAssetsAgent", "执行导演",
                readSkill("production_execution_generate_assets.md"),
                null, prompt, "assistant:execution");
    }

    @Tool(name = "run_sub_agent_director_plan", description = "运行执行subAgent来完成导演规划相关任务")
    public String runSubAgentDirectorPlan(
            @ToolParam(description = "交给子Agent的任务简约描述，100字以内") String prompt) {
        log.info("[tool] runSubAgentDirectorPlan");
        String formatPrompt = "\n你必须使用如下XML格式写入工作区：\n```\n<scriptPlan>内容</scriptPlan>\n```";
        return runSubAgent("productionAgent:directorPlanAgent", "执行导演",
                readSkill("production_execution_director_plan.md") + formatPrompt,
                null, prompt + formatPrompt, "assistant:execution");
    }

    @Tool(name = "run_sub_agent_storyboard_gen", description = "运行执行subAgent来完成分镜图生成相关任务")
    public String runSubAgentStoryboardGen(
            @ToolParam(description = "交给子Agent的任务简约描述，100字以内") String prompt) {
        log.info("[tool] runSubAgentStoryboardGen");
        return runSubAgent("productionAgent:storyboardGenAgent", "执行导演",
                readSkill("production_execution_storyboard_gen.md"),
                null, prompt, "assistant:execution");
    }

    @Tool(name = "run_sub_agent_storyboard_panel", description = "运行执行subAgent来完成分镜面板写入相关任务")
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

    @Tool(name = "run_sub_agent_storyboard_table", description = "运行执行subAgent来完成分镜表构建相关任务")
    public String runSubAgentStoryboardTable(
            @ToolParam(description = "交给子Agent的任务简约描述，100字以内") String prompt) {
        log.info("[tool] runSubAgentStoryboardTable");
        String formatPrompt = "\n你必须使用如下XML格式写入工作区：\n```\n<storyboardTable>内容</storyboardTable>\n```";
        return runSubAgent("productionAgent:storyboardTableAgent", "执行导演",
                readSkill("production_execution_storyboard_table.md") + formatPrompt,
                null, prompt + formatPrompt, "assistant:execution");
    }

    @Tool(name = "run_sub_agent_supervision", description = "运行监督层subAgent执行独立任务，完成后返回结果")
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
