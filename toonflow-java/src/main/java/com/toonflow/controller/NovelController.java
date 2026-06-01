package com.toonflow.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.toonflow.common.exception.BusinessException;
import com.toonflow.common.result.R;
import com.toonflow.entity.OEvent;
import com.toonflow.entity.OEventChapter;
import com.toonflow.entity.ONovel;
import com.toonflow.mapper.OEventChapterMapper;
import com.toonflow.mapper.OEventMapper;
import com.toonflow.mapper.ONovelMapper;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/novel")
@RequiredArgsConstructor
public class NovelController {

    private final ONovelMapper novelMapper;
    private final OEventMapper eventMapper;
    private final OEventChapterMapper eventChapterMapper;
    private final com.toonflow.ai.CleanNovelService cleanNovelService;

    @PostMapping("/addNovel")
    public R<Map<String, String>> addNovel(@Valid @RequestBody AddNovelRequest req) {
        // 获取当前最大 chapterIndex
        ONovel last = novelMapper.selectOne(
                new LambdaQueryWrapper<ONovel>()
                        .eq(ONovel::getProjectId, req.getProjectId())
                        .orderByDesc(ONovel::getChapterIndex)
                        .last("LIMIT 1"));
        int lastIndex = last != null ? last.getChapterIndex() : 0;

        List<ONovel> inserted = new java.util.ArrayList<>();
        for (AddNovelRequest.NovelItem item : req.getData()) {
            ONovel novel = new ONovel();
            novel.setProjectId(req.getProjectId());
            novel.setChapterIndex(++lastIndex);
            novel.setReel(item.getReel());
            novel.setChapter(item.getChapter());
            novel.setChapterData(item.getChapterData());
            novel.setCreateTime(System.currentTimeMillis());
            novel.setEventState(0);
            novelMapper.insert(novel);
            inserted.add(novel);
        }
        // 自动清洗生成事件（对应原项目 addNovel 触发 cleanNovel）
        cleanNovelService.start(inserted, req.getProjectId());
        return R.ok(Map.of("message", "新增原文成功"));
    }

    @GetMapping("/getNovel")
    public R<List<ONovel>> getNovel(@RequestParam Integer projectId) {
        List<ONovel> list = novelMapper.selectList(
                new LambdaQueryWrapper<ONovel>()
                        .eq(ONovel::getProjectId, projectId)
                        .orderByAsc(ONovel::getChapterIndex));
        return R.ok(list);
    }

    @PostMapping("/updateNovel")
    public R<Map<String, String>> updateNovel(@RequestBody ONovel novel) {
        novelMapper.updateById(novel);
        return R.ok(Map.of("message", "更新成功"));
    }

    @PostMapping("/delNovel")
    public R<Map<String, String>> delNovel(@RequestBody Map<String, Integer> body) {
        Integer id = body.get("id");
        if (id == null) throw new BusinessException("id不能为空");
        novelMapper.deleteById(id);
        return R.ok(Map.of("message", "删除成功"));
    }

    @PostMapping("/batchDeleteNovel")
    public R<Map<String, String>> batchDeleteNovel(@RequestBody Map<String, List<Integer>> body) {
        List<Integer> ids = body.get("ids");
        if (ids == null || ids.isEmpty()) throw new BusinessException("ids不能为空");
        novelMapper.deleteBatchIds(ids);
        return R.ok(Map.of("message", "批量删除成功"));
    }

    @GetMapping("/getNovelIndex")
    public R<List<ONovel>> getNovelIndex(@RequestParam Integer projectId) {
        List<ONovel> list = novelMapper.selectList(
                new LambdaQueryWrapper<ONovel>()
                        .eq(ONovel::getProjectId, projectId)
                        .select(ONovel::getId, ONovel::getChapterIndex, ONovel::getReel, ONovel::getChapter)
                        .orderByAsc(ONovel::getChapterIndex));
        return R.ok(list);
    }

    @GetMapping("/getNovelEventState")
    public R<List<ONovel>> getNovelEventState(@RequestParam Integer projectId) {
        List<ONovel> list = novelMapper.selectList(
                new LambdaQueryWrapper<ONovel>()
                        .eq(ONovel::getProjectId, projectId)
                        .select(ONovel::getId, ONovel::getEventState, ONovel::getErrorReason));
        return R.ok(list);
    }

    @PostMapping("/getNovelData")
    public R<List<ONovel>> getNovelData(@RequestBody Map<String, Integer> body) {
        Integer projectId = body.get("projectId");
        return R.ok(novelMapper.selectList(
                new LambdaQueryWrapper<ONovel>().eq(ONovel::getProjectId, projectId)));
    }

    /**
     * 生成章节事件：将选中章节标记为待处理，由后台清洗生成事件
     * 对应原项目 novel/event/generateEvents（使用 cleanNovel）
     */
    @PostMapping("/event/generateEvents")
    public R<Map<String, String>> generateEvents(@RequestBody GenerateEventsRequest req) {
        List<ONovel> chapters = novelMapper.selectList(
                new LambdaQueryWrapper<ONovel>()
                        .eq(ONovel::getProjectId, req.getProjectId())
                        .in(ONovel::getId, req.getNovelIds()));
        if (chapters.isEmpty()) return R.ok(Map.of("message", "没有对应章节"));

        // 重置事件状态
        for (ONovel novel : chapters) {
            novel.setEventState(0);
            novel.setEvent(null);
            novelMapper.updateById(novel);
        }
        // 异步清洗生成事件
        cleanNovelService.start(chapters, req.getProjectId());
        return R.ok(Map.of("message", "已提交事件生成任务"));
    }

    // ========== 事件管理 ==========

    /**
     * 分页查询事件（联查 o_eventChapter -> o_novel 过滤 projectId）
     */
    @PostMapping("/event/getEvent")
    public R<Map<String, Object>> getEvent(@RequestBody Map<String, Object> body) {
        Integer projectId = (Integer) body.get("projectId");
        int page = body.get("page") != null ? (Integer) body.get("page") : 1;
        int limit = body.get("limit") != null ? (Integer) body.get("limit") : 10;
        String search = (String) body.get("search");

        // 查出该项目下的所有 novelId
        List<Integer> novelIds = novelMapper.selectList(
                new LambdaQueryWrapper<ONovel>()
                        .eq(ONovel::getProjectId, projectId)
                        .select(ONovel::getId))
                .stream().map(ONovel::getId).toList();

        if (novelIds.isEmpty()) {
            return R.ok(Map.of("list", List.of(), "total", 0));
        }

        // 通过 eventChapter 找到关联的 eventId
        List<Integer> eventIds = eventChapterMapper.selectList(
                new LambdaQueryWrapper<OEventChapter>()
                        .in(OEventChapter::getNovelId, novelIds)
                        .select(OEventChapter::getEventId))
                .stream().map(OEventChapter::getEventId).distinct().toList();

        if (eventIds.isEmpty()) {
            return R.ok(Map.of("list", List.of(), "total", 0));
        }

        LambdaQueryWrapper<OEvent> wrapper = new LambdaQueryWrapper<OEvent>()
                .in(OEvent::getId, eventIds);
        if (search != null && !search.isEmpty()) {
            wrapper.like(OEvent::getName, search);
        }
        long total = eventMapper.selectCount(wrapper);
        wrapper.last("LIMIT " + limit + " OFFSET " + ((page - 1) * limit));
        List<OEvent> list = eventMapper.selectList(wrapper);

        return R.ok(Map.of("list", list, "total", total));
    }

    @PostMapping("/event/deletEvent")
    public R<Map<String, String>> deletEvent(@RequestBody Map<String, Integer> body) {
        Integer id = body.get("id");
        if (id == null) throw new BusinessException("id不能为空");
        eventMapper.deleteById(id);
        eventChapterMapper.delete(new LambdaQueryWrapper<OEventChapter>().eq(OEventChapter::getEventId, id));
        return R.ok(Map.of("message", "删除事件成功"));
    }

    @PostMapping("/event/batchDeleteEvent")
    public R<Map<String, String>> batchDeleteEvent(@RequestBody Map<String, List<Integer>> body) {
        List<Integer> ids = body.get("ids");
        if (ids == null || ids.isEmpty()) throw new BusinessException("ids不能为空");
        eventMapper.deleteBatchIds(ids);
        eventChapterMapper.delete(new LambdaQueryWrapper<OEventChapter>().in(OEventChapter::getEventId, ids));
        return R.ok(Map.of("message", "删除事件成功"));
    }

    @Data
    public static class GenerateEventsRequest {
        @NotNull private Integer projectId;
        @NotNull private List<Integer> novelIds;
        private Integer concurrentCount = 5;
    }

    @Data
    public static class AddNovelRequest {
        @NotNull private Integer projectId;
        @NotNull private List<NovelItem> data;

        @Data
        public static class NovelItem {
            private Integer index;
            private String reel;
            private String chapter;
            private String chapterData;
        }
    }
}
