package com.toonflow.ai.agent.tool;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.toonflow.entity.ONovel;
import com.toonflow.entity.OScript;
import com.toonflow.mapper.ONovelMapper;
import com.toonflow.mapper.OScriptMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 剧本 Agent 工具集
 * 对应原项目 src/agents/scriptAgent/tools.ts
 *
 * 通过 Spring AI @Tool 注解暴露给大模型自主调用。
 * 每个工具绑定到当前会话的 projectId（构造时注入）。
 */
@Slf4j
public class ScriptAgentTools {

    private final ONovelMapper novelMapper;
    private final OScriptMapper scriptMapper;
    private final Long projectId;

    public ScriptAgentTools(ONovelMapper novelMapper, OScriptMapper scriptMapper, Long projectId) {
        this.novelMapper = novelMapper;
        this.scriptMapper = scriptMapper;
        this.projectId = projectId;
    }

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
            @ToolParam(description = "剧本 id 列表") List<Integer> ids) {
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
        script.setProjectId(projectId.intValue());
        script.setCreateTime(System.currentTimeMillis());
        scriptMapper.insert(script);
        return "剧本《" + name + "》已保存，id=" + script.getId();
    }
}
