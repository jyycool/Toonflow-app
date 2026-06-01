package com.toonflow.ai;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.toonflow.entity.ONovel;
import com.toonflow.entity.OPrompt;
import com.toonflow.mapper.ONovelMapper;
import com.toonflow.mapper.OPromptMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.Semaphore;

/**
 * 小说清洗服务：将章节原文清洗为事件摘要
 * 对应原项目 src/utils/cleanNovel.ts
 *
 * 使用并发信号量限制同时处理的章节数，结果写回 o_novel。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CleanNovelService {

    private final AiService aiService;
    private final ONovelMapper novelMapper;
    private final OPromptMapper promptMapper;

    private static final int DEFAULT_CONCURRENCY = 5;

    /**
     * 异步启动事件生成
     */
    @Async
    public void start(List<ONovel> chapters, Integer projectId) {
        start(chapters, projectId, DEFAULT_CONCURRENCY);
    }

    @Async
    public void start(List<ONovel> chapters, Integer projectId, int concurrency) {
        Semaphore semaphore = new Semaphore(Math.max(1, concurrency));
        chapters.parallelStream().forEach(novel -> {
            try {
                semaphore.acquire();
                processChapter(novel);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                semaphore.release();
            }
        });
    }

    private void processChapter(ONovel novel) {
        try {
            String systemPrompt = resolveEventPrompt();
            String userContent = "请根据以下小说章节数：" + novel.getChapterIndex()
                    + "小说章节券：" + nv(novel.getReel())
                    + "小说章节名称：" + nv(novel.getChapter())
                    + "、小说章节内容生成事件摘要：\n" + nv(novel.getChapterData());

            String event = aiService.generateText("universalAi", List.of(
                    new AiService.ChatMessage("system", systemPrompt),
                    new AiService.ChatMessage("user", userContent)));

            event = stripThink(event);
            novel.setEvent(event);
            novel.setEventState(event != null && !event.isEmpty() ? 1 : -1);
            novel.setErrorReason(null);
            novelMapper.updateById(novel);
        } catch (Exception e) {
            log.error("章节 {} 事件生成失败", novel.getId(), e);
            novel.setEvent(null);
            novel.setEventState(-1);
            novel.setErrorReason(e.getMessage());
            novelMapper.updateById(novel);
        }
    }

    private String resolveEventPrompt() {
        OPrompt prompt = promptMapper.selectOne(
                new LambdaQueryWrapper<OPrompt>().eq(OPrompt::getType, "eventExtraction").last("LIMIT 1"));
        if (prompt != null) {
            if (prompt.getUseData() != null && !prompt.getUseData().isEmpty()) return prompt.getUseData();
            if (prompt.getData() != null && !prompt.getData().isEmpty()) return prompt.getData();
        }
        return "你是一个小说事件提取助手。请阅读小说章节内容，提取并总结该章节的核心事件摘要，简洁清晰。";
    }

    /**
     * 去除思考过程标签（<think>...</think>）
     * 对应原项目 src/utils/stripThink.ts
     */
    private String stripThink(String text) {
        if (text == null) return "";
        return text.replaceAll("(?s)<think>.*?</think>", "").trim();
    }

    private String nv(String s) { return s != null ? s : ""; }
}
