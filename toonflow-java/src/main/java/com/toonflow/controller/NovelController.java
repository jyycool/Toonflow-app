package com.toonflow.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
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

import java.util.*;
import java.util.stream.Collectors;

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
        ONovel last = novelMapper.selectOne(
                new LambdaQueryWrapper<ONovel>()
                        .eq(ONovel::getProjectId, req.getProjectId())
                        .orderByDesc(ONovel::getChapterIndex)
                        .last("LIMIT 1"));
        int lastIndex = last != null ? last.getChapterIndex() : 0;

        List<ONovel> inserted = new ArrayList<>();
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
        cleanNovelService.start(inserted, req.getProjectId());
        return R.ok(Map.of("message", "新增原文成功"));
    }

    @PostMapping("/getNovel")
    public R<Map<String, Object>> getNovel(@RequestBody Map<String, Object> body) {
        String projectId = body.get("projectId") != null ? body.get("projectId").toString() : null;
        int page = body.get("page") != null ? ((Number) body.get("page")).intValue() : 1;
        int limit = body.get("limit") != null ? ((Number) body.get("limit")).intValue() : 20;
        String search = (String) body.get("search");

        LambdaQueryWrapper<ONovel> wrapper = new LambdaQueryWrapper<ONovel>()
                .eq(ONovel::getProjectId, projectId)
                .orderByAsc(ONovel::getChapterIndex);
        if (search != null && !search.isEmpty()) {
            wrapper.like(ONovel::getChapter, search);
        }

        Page<ONovel> pageObj = novelMapper.selectPage(new Page<>(page, limit), wrapper);

        List<Map<String, Object>> items = pageObj.getRecords().stream().map(n -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", n.getId());
            m.put("projectId", n.getProjectId());
            m.put("index", n.getChapterIndex());
            m.put("reel", n.getReel());
            m.put("chapter", n.getChapter());
            m.put("chapterData", n.getChapterData());
            m.put("event", n.getEvent());
            m.put("eventState", n.getEventState());
            m.put("errorReason", n.getErrorReason());
            m.put("createTime", n.getCreateTime());
            return m;
        }).collect(Collectors.toList());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("data", items);
        result.put("total", pageObj.getTotal());
        return R.ok(result);
    }

    @PostMapping("/getNovelEventState")
    public R<List<Map<String, Object>>> getNovelEventState(@RequestBody Map<String, Object> body) {
        @SuppressWarnings("unchecked")
        List<Object> rawIds = (List<Object>) body.get("ids");
        if (rawIds == null || rawIds.isEmpty()) return R.ok(List.of());
        List<String> ids = rawIds.stream().map(Object::toString).collect(java.util.stream.Collectors.toList());

        List<ONovel> list = novelMapper.selectList(
                new LambdaQueryWrapper<ONovel>()
                        .in(ONovel::getId, ids)
                        .ne(ONovel::getEventState, 0)
                        .select(ONovel::getId, ONovel::getEvent, ONovel::getEventState, ONovel::getErrorReason));

        List<Map<String, Object>> result = list.stream().map(n -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", n.getId());
            m.put("event", n.getEvent());
            m.put("eventState", n.getEventState());
            m.put("errorReason", n.getErrorReason());
            return m;
        }).collect(Collectors.toList());

        return R.ok(result);
    }

    @PostMapping("/getNovelIndex")
    public R<List<Map<String, Object>>> getNovelIndex(@RequestBody Map<String, Object> body) {
        String projectId = body.get("projectId") != null ? body.get("projectId").toString() : null;
        List<ONovel> list = novelMapper.selectList(
                new LambdaQueryWrapper<ONovel>()
                        .eq(ONovel::getProjectId, projectId)
                        .select(ONovel::getId, ONovel::getChapterIndex, ONovel::getChapter)
                        .orderByAsc(ONovel::getChapterIndex));

        List<Map<String, Object>> result = list.stream().map(n -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", n.getId());
            m.put("index", n.getChapterIndex());
            m.put("chapter", n.getChapter());
            return m;
        }).collect(Collectors.toList());

        return R.ok(result);
    }

    @PostMapping("/updateNovel")
    public R<Map<String, String>> updateNovel(@RequestBody Map<String, Object> body) {
        String id = body.get("id") != null ? body.get("id").toString() : null;
        if (id == null) throw new BusinessException("id不能为空");

        ONovel novel = new ONovel();
        novel.setId(id);
        if (body.get("index") != null) {
            novel.setChapterIndex(((Number) body.get("index")).intValue());
        }
        if (body.get("reel") != null) {
            novel.setReel((String) body.get("reel"));
        }
        if (body.get("chapter") != null) {
            novel.setChapter((String) body.get("chapter"));
        }
        if (body.get("chapterData") != null) {
            novel.setChapterData((String) body.get("chapterData"));
        }
        if (body.get("event") != null) {
            novel.setEvent((String) body.get("event"));
        }
        novelMapper.updateById(novel);
        return R.ok(Map.of("message", "更新成功"));
    }

    @PostMapping("/delNovel")
    public R<Map<String, String>> delNovel(@RequestBody Map<String, Object> body) {
        String id = body.get("id") != null ? body.get("id").toString() : null;
        if (id == null) throw new BusinessException("id不能为空");
        novelMapper.deleteById(id);
        return R.ok(Map.of("message", "删除成功"));
    }

    @PostMapping("/batchDeleteNovel")
    public R<Map<String, String>> batchDeleteNovel(@RequestBody Map<String, Object> body) {
        @SuppressWarnings("unchecked")
        List<Object> rawIds2 = (List<Object>) body.get("ids");
        if (rawIds2 == null || rawIds2.isEmpty()) throw new BusinessException("ids不能为空");
        List<String> ids = rawIds2.stream().map(Object::toString).collect(java.util.stream.Collectors.toList());
        novelMapper.deleteBatchIds(ids);
        return R.ok(Map.of("message", "批量删除成功"));
    }

    @PostMapping("/getNovelData")
    public R<List<ONovel>> getNovelData(@RequestBody Map<String, Object> body) {
        String projectId = body.get("projectId") != null ? body.get("projectId").toString() : null;
        return R.ok(novelMapper.selectList(
                new LambdaQueryWrapper<ONovel>().eq(ONovel::getProjectId, projectId)));
    }

    @PostMapping("/event/generateEvents")
    public R<Map<String, String>> generateEvents(@RequestBody Map<String, Object> body) {
        String projectId = body.get("projectId") != null ? body.get("projectId").toString() : null;
        @SuppressWarnings("unchecked")
        List<Object> rawNovelIds = (List<Object>) body.get("novelIds");
        List<String> novelIds = rawNovelIds != null
                ? rawNovelIds.stream().map(Object::toString).collect(Collectors.toList()) : List.of();
        if (novelIds.isEmpty()) return R.ok(Map.of("message", "没有对应章节"));

        List<ONovel> chapters = novelMapper.selectList(
                new LambdaQueryWrapper<ONovel>()
                        .eq(ONovel::getProjectId, projectId)
                        .in(ONovel::getId, novelIds));
        if (chapters.isEmpty()) return R.ok(Map.of("message", "没有对应章节"));

        for (ONovel novel : chapters) {
            novel.setEventState(0);
            novel.setEvent(null);
            novelMapper.updateById(novel);
        }
        cleanNovelService.start(chapters, projectId);
        return R.ok(Map.of("message", "已提交事件生成任务"));
    }

    @PostMapping("/event/getEvent")
    public R<Map<String, Object>> getEvent(@RequestBody Map<String, Object> body) {
        String projectId = body.get("projectId") != null ? body.get("projectId").toString() : null;
        String search = body.get("search") != null ? body.get("search").toString() : null;
        int page = body.get("page") instanceof Number n ? n.intValue() : 1;
        int limit = body.get("limit") instanceof Number n ? n.intValue() : 20;
        int offset = (page - 1) * limit;

        List<ONovel> novels = novelMapper.selectList(
                new LambdaQueryWrapper<ONovel>()
                        .eq(ONovel::getProjectId, projectId)
                        .select(ONovel::getId, ONovel::getChapterIndex));

        if (novels.isEmpty()) return R.ok(Map.of("list", List.of(), "total", 0));

        Map<String, Integer> novelChapterIndexMap = novels.stream()
                .collect(Collectors.toMap(ONovel::getId, ONovel::getChapterIndex));
        List<String> novelIds = new ArrayList<>(novelChapterIndexMap.keySet());

        List<OEventChapter> eventChapters = eventChapterMapper.selectList(
                new LambdaQueryWrapper<OEventChapter>().in(OEventChapter::getNovelId, novelIds));

        if (eventChapters.isEmpty()) return R.ok(Map.of("list", List.of(), "total", 0));

        Map<String, List<Integer>> eventChapterIndexes = new LinkedHashMap<>();
        for (OEventChapter ec : eventChapters) {
            Integer chapterIndex = novelChapterIndexMap.get(ec.getNovelId());
            if (chapterIndex != null)
                eventChapterIndexes.computeIfAbsent(ec.getEventId(), k -> new ArrayList<>()).add(chapterIndex);
        }

        List<String> eventIds = new ArrayList<>(eventChapterIndexes.keySet());

        LambdaQueryWrapper<OEvent> eventQ = new LambdaQueryWrapper<OEvent>().in(OEvent::getId, eventIds);
        if (search != null && !search.isBlank()) eventQ.like(OEvent::getName, search);
        List<OEvent> allEvents = eventMapper.selectList(eventQ);
        long total = allEvents.size();

        // Manual pagination (no native offset support without SQL)
        List<OEvent> paged = allEvents.stream().skip(offset).limit(limit).collect(Collectors.toList());

        List<Map<String, Object>> list = paged.stream().map(e -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", e.getId());
            m.put("eventName", e.getName());
            m.put("detail", e.getDetail());
            m.put("createTime", e.getCreateTime());
            m.put("chapters", eventChapterIndexes.getOrDefault(e.getId(), List.of()));
            return m;
        }).collect(Collectors.toList());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("list", list);
        result.put("total", total);
        return R.ok(result);
    }

    @PostMapping("/event/deletEvent")
    public R<Map<String, String>> deletEvent(@RequestBody Map<String, Object> body) {
        String id = body.get("id") != null ? body.get("id").toString() : null;
        if (id == null) throw new BusinessException("id不能为空");
        eventMapper.deleteById(id);
        eventChapterMapper.delete(new LambdaQueryWrapper<OEventChapter>().eq(OEventChapter::getEventId, id));
        return R.ok(Map.of("message", "删除事件成功"));
    }

    @PostMapping("/event/batchDeleteEvent")
    public R<Map<String, String>> batchDeleteEvent(@RequestBody Map<String, Object> body) {
        @SuppressWarnings("unchecked") List<Object> rawEventIds = (List<Object>) body.get("ids");
        if (rawEventIds == null || rawEventIds.isEmpty()) throw new BusinessException("ids不能为空");
        List<String> ids = rawEventIds.stream().map(Object::toString).collect(java.util.stream.Collectors.toList());
        eventMapper.deleteBatchIds(ids);
        eventChapterMapper.delete(new LambdaQueryWrapper<OEventChapter>().in(OEventChapter::getEventId, ids));
        return R.ok(Map.of("message", "删除事件成功"));
    }

    @Data
    public static class GenerateEventsRequest {
        @NotNull private String projectId;
        @NotNull private List<String> novelIds;
        private Integer concurrentCount = 5;
    }

    @Data
    public static class AddNovelRequest {
        @NotNull private String projectId;
        @NotNull private List<NovelItem> data;

        @Data
        public static class NovelItem {
            private Long index;
            private String reel;
            private String chapter;
            private String chapterData;
        }
    }
}
