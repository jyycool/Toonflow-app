package com.toonflow.ai.agent.tool;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.toonflow.ai.MemoryService;
import com.toonflow.entity.ONovel;
import com.toonflow.entity.OScript;
import com.toonflow.mapper.ONovelMapper;
import com.toonflow.mapper.OScriptMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Data-query-only tools for script execution sub-agents.
 * Does NOT include sub-agent tools (to prevent infinite recursion).
 */
@Slf4j
public class ScriptDataQueryTools {

    private final ONovelMapper novelMapper;
    private final OScriptMapper scriptMapper;
    private final MemoryService memoryService;
    private final String projectId;
    private final String isolationKey;

    private static final Map<String, String> PLAN_KEY_TO_ROLE = Map.of(
            "storySkeleton", "assistant:execution:storySkeleton",
            "adaptationStrategy", "assistant:execution:adaptationStrategy",
            "script", "assistant:execution:script"
    );

    public ScriptDataQueryTools(ONovelMapper novelMapper, OScriptMapper scriptMapper,
                                MemoryService memoryService, String projectId, String isolationKey) {
        this.novelMapper = novelMapper;
        this.scriptMapper = scriptMapper;
        this.memoryService = memoryService;
        this.projectId = projectId;
        this.isolationKey = isolationKey;
    }

    @Tool(name = "get_novel_events", description = "获取指定章节编号的章节事件")
    public String getNovelEvents(
            @ToolParam(description = "章节编号列表") List<Integer> chapterIndexs) {
        log.info("[sub-tool] get_novel_events {}", chapterIndexs);
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

    @Tool(name = "get_novel_text", description = "获取小说指定章节的原始文本内容")
    public String getNovelText(
            @ToolParam(description = "章节编号") Integer chapterIndex) {
        log.info("[sub-tool] get_novel_text {}", chapterIndex);
        ONovel novel = novelMapper.selectOne(
                new LambdaQueryWrapper<ONovel>()
                        .eq(ONovel::getProjectId, projectId)
                        .eq(ONovel::getChapterIndex, chapterIndex)
                        .last("LIMIT 1"));
        if (novel == null || novel.getChapterData() == null) return "无数据";
        return novel.getChapterData();
    }

    @Tool(name = "get_script_content", description = "根据剧本 id 列表获取剧本内容")
    public String getScriptContent(
            @ToolParam(description = "剧本 id 列表") List<String> ids) {
        log.info("[sub-tool] get_script_content {}", ids);
        if (ids == null || ids.isEmpty()) return "无数据";
        List<OScript> scripts = scriptMapper.selectList(
                new LambdaQueryWrapper<OScript>().in(OScript::getId, ids));
        if (scripts.isEmpty()) return "无数据";
        return scripts.stream()
                .map(s -> "剧本《" + s.getName() + "》:\n" + (s.getContent() != null ? s.getContent() : ""))
                .collect(Collectors.joining("\n\n"));
    }

    /**
     * Reads workbench data stored in memory by previous execution sub-agents.
     * Keys: storySkeleton | adaptationStrategy | script
     */
    @Tool(name = "get_planData", description = "获取工作区数据（storySkeleton/adaptationStrategy/script）")
    public String getPlanData(
            @ToolParam(description = "数据key: storySkeleton | adaptationStrategy | script") String key) {
        log.info("[sub-tool] get_planData key={}", key);
        String role = PLAN_KEY_TO_ROLE.get(key);
        if (role == null) return "无效的 key，支持: storySkeleton, adaptationStrategy, script";
        String content = memoryService.getLatestByRole(isolationKey, role);
        return content != null ? content : "无数据（该阶段尚未完成）";
    }
}
