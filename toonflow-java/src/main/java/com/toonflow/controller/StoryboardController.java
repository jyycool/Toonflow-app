package com.toonflow.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.toonflow.common.exception.BusinessException;
import com.toonflow.common.result.R;
import com.toonflow.entity.OStoryboard;
import com.toonflow.mapper.OStoryboardMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/production/storyboard")
@RequiredArgsConstructor
public class StoryboardController {

    private final OStoryboardMapper storyboardMapper;

    @PostMapping("/addStoryboard")
    public R<Map<String, String>> addStoryboard(@RequestBody OStoryboard storyboard) {
        storyboard.setCreateTime(System.currentTimeMillis());
        storyboardMapper.insert(storyboard);
        return R.ok(Map.of("message", "新增分镜成功"));
    }

    @GetMapping("/getStoryboardData")
    public R<List<OStoryboard>> getStoryboardData(@RequestParam Integer projectId,
                                                    @RequestParam(required = false) Integer scriptId) {
        LambdaQueryWrapper<OStoryboard> wrapper = new LambdaQueryWrapper<OStoryboard>()
                .eq(OStoryboard::getProjectId, projectId);
        if (scriptId != null) wrapper.eq(OStoryboard::getScriptId, scriptId);
        wrapper.orderByAsc(OStoryboard::getIndex);
        return R.ok(storyboardMapper.selectList(wrapper));
    }

    @PostMapping("/editStoryboardInfo")
    public R<Map<String, String>> editStoryboardInfo(@RequestBody OStoryboard storyboard) {
        storyboardMapper.updateById(storyboard);
        return R.ok(Map.of("message", "编辑分镜成功"));
    }

    @PostMapping("/batchDelete")
    public R<Map<String, String>> batchDelete(@RequestBody Map<String, List<Integer>> body) {
        List<Integer> ids = body.get("ids");
        if (ids == null || ids.isEmpty()) throw new BusinessException("ids不能为空");
        storyboardMapper.deleteBatchIds(ids);
        return R.ok(Map.of("message", "批量删除成功"));
    }

    @PostMapping("/removeFrame")
    public R<Map<String, String>> removeFrame(@RequestBody Map<String, Integer> body) {
        Integer id = body.get("id");
        storyboardMapper.deleteById(id);
        return R.ok(Map.of("message", "删除成功"));
    }

    @GetMapping("/pollingImage")
    public R<List<OStoryboard>> pollingImage(@RequestParam List<Integer> ids) {
        return R.ok(storyboardMapper.selectList(
                new LambdaQueryWrapper<OStoryboard>().in(OStoryboard::getId, ids)));
    }

    @PostMapping("/updateStoryboardUrl")
    public R<Map<String, String>> updateStoryboardUrl(@RequestBody OStoryboard storyboard) {
        storyboardMapper.updateById(storyboard);
        return R.ok(Map.of("message", "更新成功"));
    }
}
