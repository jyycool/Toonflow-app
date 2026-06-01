package com.toonflow.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.toonflow.common.exception.BusinessException;
import com.toonflow.common.result.R;
import com.toonflow.entity.ONovel;
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

    @PostMapping("/addNovel")
    public R<Map<String, String>> addNovel(@Valid @RequestBody AddNovelRequest req) {
        // 获取当前最大 chapterIndex
        ONovel last = novelMapper.selectOne(
                new LambdaQueryWrapper<ONovel>()
                        .eq(ONovel::getProjectId, req.getProjectId())
                        .orderByDesc(ONovel::getChapterIndex)
                        .last("LIMIT 1"));
        int lastIndex = last != null ? last.getChapterIndex() : 0;

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
        }
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
