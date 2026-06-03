package com.toonflow.ai;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.toonflow.entity.Memories;
import com.toonflow.entity.OSetting;
import com.toonflow.mapper.MemoriesMapper;
import com.toonflow.mapper.OSettingMapper;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * Agent 记忆服务
 * 对应原项目 src/utils/agent/memory.ts
 *
 * 记忆分三层：
 *  - shortTerm: 近期未总结的对话消息
 *  - summaries: 历史摘要（达到阈值后由 AI 压缩生成）
 *  - rag:       向量相似检索召回的相关消息
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MemoryService {

    private final MemoriesMapper memoriesMapper;
    private final OSettingMapper settingMapper;
    private final EmbeddingService embeddingService;
    private final AiService aiService;

    // 默认配置
    private static final int MESSAGES_PER_SUMMARY = 3;
    private static final int SUMMARY_MAX_LENGTH = 500;
    private static final int SHORT_TERM_LIMIT = 5;
    private static final int SUMMARY_LIMIT = 10;
    private static final int RAG_LIMIT = 3;

    /**
     * 添加一条记忆
     */
    public void add(String agentType, String isolationKey, String role, String content) {
        Memories memory = new Memories();
        memory.setId(UUID.randomUUID().toString());
        memory.setIsolationKey(isolationKey);
        memory.setType("message");
        memory.setRole(role);
        memory.setContent(content);
        memory.setSummarized(0);
        memory.setCreateTime(System.currentTimeMillis());

        // 生成向量
        float[] vector = embeddingService.embed(content);
        memory.setEmbedding(embeddingService.toJson(vector));

        memoriesMapper.insert(memory);

        // 检查是否需要触发摘要
        maybeSummarize(agentType, isolationKey);
    }

    /**
     * 获取记忆上下文（短期 + 摘要 + RAG）
     */
    public MemoryContext get(String isolationKey, String query) {
        MemoryContext ctx = new MemoryContext();

        // 短期：未总结的最近消息
        ctx.shortTerm = memoriesMapper.selectList(
                new LambdaQueryWrapper<Memories>()
                        .eq(Memories::getIsolationKey, isolationKey)
                        .eq(Memories::getType, "message")
                        .eq(Memories::getSummarized, 0)
                        .orderByDesc(Memories::getCreateTime)
                        .last("LIMIT " + SHORT_TERM_LIMIT));

        // 摘要
        ctx.summaries = memoriesMapper.selectList(
                new LambdaQueryWrapper<Memories>()
                        .eq(Memories::getIsolationKey, isolationKey)
                        .eq(Memories::getType, "summary")
                        .orderByDesc(Memories::getCreateTime)
                        .last("LIMIT " + SUMMARY_LIMIT));

        // RAG：向量相似检索
        if (query != null && !query.isEmpty()) {
            float[] queryVec = embeddingService.embed(query);
            if (queryVec.length > 0) {
                List<Memories> all = memoriesMapper.selectList(
                        new LambdaQueryWrapper<Memories>()
                                .eq(Memories::getIsolationKey, isolationKey)
                                .eq(Memories::getType, "message"));
                ctx.rag = all.stream()
                        .sorted(Comparator.comparingDouble((Memories m) ->
                                embeddingService.cosineSimilarity(queryVec,
                                        embeddingService.fromJson(m.getEmbedding()))).reversed())
                        .limit(RAG_LIMIT)
                        .toList();
            }
        }
        return ctx;
    }

    /**
     * 构建记忆提示词
     */
    public String buildPrompt(MemoryContext mem) {
        StringBuilder sb = new StringBuilder();
        if (mem.rag != null && !mem.rag.isEmpty()) {
            sb.append("[相关记忆]\n");
            mem.rag.forEach(r -> sb.append(r.getContent()).append("\n"));
        }
        if (mem.summaries != null && !mem.summaries.isEmpty()) {
            if (sb.length() > 0) sb.append("\n\n");
            sb.append("[历史摘要]\n");
            for (int i = 0; i < mem.summaries.size(); i++) {
                sb.append(i + 1).append(". ").append(mem.summaries.get(i).getContent()).append("\n");
            }
        }
        if (mem.shortTerm != null && !mem.shortTerm.isEmpty()) {
            if (sb.length() > 0) sb.append("\n\n");
            sb.append("[近期对话]\n");
            mem.shortTerm.forEach(m -> sb.append(m.getRole()).append(": ").append(m.getContent()).append("\n"));
        }
        return "## Memory\n以下是你对用户的记忆，可作为参考但不要主动提及：\n" + sb;
    }

    /**
     * 清除记忆
     */
    public void clear(String isolationKey) {
        memoriesMapper.delete(new LambdaQueryWrapper<Memories>()
                .eq(Memories::getIsolationKey, isolationKey));
    }

    /**
     * 深度检索记忆（keyword文本匹配 + 向量相似召回）
     */
    public List<Memories> deepRetrieve(String isolationKey, String keyword) {
        // text match
        List<Memories> results = memoriesMapper.selectList(
                new LambdaQueryWrapper<Memories>()
                        .eq(Memories::getIsolationKey, isolationKey)
                        .like(Memories::getContent, keyword)
                        .orderByDesc(Memories::getCreateTime)
                        .last("LIMIT 10"));
        // vector match supplement
        float[] vec = embeddingService.embed(keyword);
        if (vec.length > 0) {
            List<Memories> all = memoriesMapper.selectList(
                    new LambdaQueryWrapper<Memories>()
                            .eq(Memories::getIsolationKey, isolationKey));
            all.stream()
                    .filter(m -> results.stream().noneMatch(r -> r.getId().equals(m.getId())))
                    .sorted(Comparator.comparingDouble((Memories m) ->
                            embeddingService.cosineSimilarity(vec, embeddingService.fromJson(m.getEmbedding()))).reversed())
                    .limit(5)
                    .forEach(results::add);
        }
        return results;
    }

    /**
     * 获取最近一条指定 role 的记忆内容（用于 get_planData）
     */
    public String getLatestByRole(String isolationKey, String role) {
        List<Memories> list = memoriesMapper.selectList(
                new LambdaQueryWrapper<Memories>()
                        .eq(Memories::getIsolationKey, isolationKey)
                        .eq(Memories::getRole, role)
                        .orderByDesc(Memories::getCreateTime)
                        .last("LIMIT 1"));
        return list.isEmpty() ? null : list.get(0).getContent();
    }

    /**
     * 累积到阈值时由 AI 压缩生成摘要
     */
    private void maybeSummarize(String agentType, String isolationKey) {
        Long unsummarizedCount = memoriesMapper.selectCount(
                new LambdaQueryWrapper<Memories>()
                        .eq(Memories::getIsolationKey, isolationKey)
                        .eq(Memories::getType, "message")
                        .eq(Memories::getSummarized, 0));

        if (unsummarizedCount < MESSAGES_PER_SUMMARY) return;

        List<Memories> toSummarize = memoriesMapper.selectList(
                new LambdaQueryWrapper<Memories>()
                        .eq(Memories::getIsolationKey, isolationKey)
                        .eq(Memories::getType, "message")
                        .eq(Memories::getSummarized, 0)
                        .orderByAsc(Memories::getCreateTime)
                        .last("LIMIT " + MESSAGES_PER_SUMMARY));

        try {
            StringBuilder contents = new StringBuilder();
            for (int i = 0; i < toSummarize.size(); i++) {
                contents.append(i + 1).append(". ").append(toSummarize.get(i).getContent()).append("\n");
            }

            String summaryText = aiService.generateText(agentType, List.of(
                    new AiService.ChatMessage("system",
                            "你是一个记忆压缩助手。请将以下多条记忆内容压缩为一段简洁的摘要，不超过"
                                    + SUMMARY_MAX_LENGTH + "个字符。只输出摘要内容，不要加任何前缀或解释。"),
                    new AiService.ChatMessage("user", contents.toString())));

            if (summaryText.length() > SUMMARY_MAX_LENGTH) {
                summaryText = summaryText.substring(0, SUMMARY_MAX_LENGTH);
            }

            // 保存摘要
            Memories summary = new Memories();
            summary.setId(UUID.randomUUID().toString());
            summary.setIsolationKey(isolationKey);
            summary.setType("summary");
            summary.setContent(summaryText);
            summary.setEmbedding(embeddingService.toJson(embeddingService.embed(summaryText)));
            summary.setCreateTime(System.currentTimeMillis());
            memoriesMapper.insert(summary);

            // 标记已总结
            for (Memories m : toSummarize) {
                m.setSummarized(1);
                memoriesMapper.updateById(m);
            }
        } catch (Exception e) {
            log.error("生成摘要失败", e);
        }
    }

    @Data
    public static class MemoryContext {
        private List<Memories> shortTerm = new ArrayList<>();
        private List<Memories> summaries = new ArrayList<>();
        private List<Memories> rag = new ArrayList<>();
    }
}
