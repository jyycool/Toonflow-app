package com.toonflow.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.toonflow.common.result.R;
import com.toonflow.entity.*;
import com.toonflow.mapper.*;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class GeneralController {

    private final OProjectMapper projectMapper;
    private final ONovelMapper novelMapper;
    private final OScriptMapper scriptMapper;
    private final OAssetsMapper assetsMapper;
    private final OStoryboardMapper storyboardMapper;
    private final OVideoMapper videoMapper;
    private final OTasksMapper tasksMapper;

    @GetMapping("/general/generalStatistics")
    public R<Map<String, Object>> generalStatistics(@RequestParam Integer projectId) {
        Map<String, Object> stats = new HashMap<>();
        stats.put("novelCount", novelMapper.selectCount(
                new LambdaQueryWrapper<ONovel>().eq(ONovel::getProjectId, projectId)));
        stats.put("scriptCount", scriptMapper.selectCount(
                new LambdaQueryWrapper<OScript>().eq(OScript::getProjectId, projectId)));
        stats.put("assetsCount", assetsMapper.selectCount(
                new LambdaQueryWrapper<OAssets>().eq(OAssets::getProjectId, projectId)));
        stats.put("storyboardCount", storyboardMapper.selectCount(
                new LambdaQueryWrapper<OStoryboard>().eq(OStoryboard::getProjectId, projectId)));
        stats.put("videoCount", videoMapper.selectCount(
                new LambdaQueryWrapper<OVideo>().eq(OVideo::getProjectId, projectId)));
        return R.ok(stats);
    }

    @GetMapping("/general/getSingleProject")
    public R<OProject> getSingleProject(@RequestParam Long id) {
        return R.ok(projectMapper.selectById(id));
    }

    @PostMapping("/general/updateProject")
    public R<Map<String, String>> updateProject(@RequestBody OProject project) {
        projectMapper.updateById(project);
        return R.ok(Map.of("message", "更新成功"));
    }

    @GetMapping("/other/getVersion")
    public R<Map<String, String>> getVersion() {
        return R.ok(Map.of("version", "1.1.7"));
    }

    @PostMapping("/other/deleteAllData")
    public R<Map<String, String>> deleteAllData(@RequestBody Map<String, Long> body) {
        Long projectId = body.get("projectId");
        if (projectId != null) {
            novelMapper.delete(new LambdaQueryWrapper<ONovel>().eq(ONovel::getProjectId, projectId));
            scriptMapper.delete(new LambdaQueryWrapper<OScript>().eq(OScript::getProjectId, projectId));
            assetsMapper.delete(new LambdaQueryWrapper<OAssets>().eq(OAssets::getProjectId, projectId));
            storyboardMapper.delete(new LambdaQueryWrapper<OStoryboard>().eq(OStoryboard::getProjectId, projectId));
            videoMapper.delete(new LambdaQueryWrapper<OVideo>().eq(OVideo::getProjectId, projectId));
            projectMapper.deleteById(projectId);
        }
        return R.ok(Map.of("message", "数据已清除"));
    }

    @GetMapping("/task/getTaskApi")
    public R<Object> getTaskApi(@RequestParam Integer projectId) {
        return R.ok(tasksMapper.selectList(
                new LambdaQueryWrapper<OTasks>().eq(OTasks::getProjectId, projectId)
                        .orderByDesc(OTasks::getStartTime)));
    }

    @PostMapping("/task/getTaskApi")
    public R<Object> getTaskApiPost(@RequestBody Map<String, Integer> body) {
        return getTaskApi(body.get("projectId"));
    }

    @PostMapping("/task/getTaskCategories")
    public R<List<String>> getTaskCategories() {
        List<OTasks> tasks = tasksMapper.selectList(
                new LambdaQueryWrapper<OTasks>().select(OTasks::getTaskClass).groupBy(OTasks::getTaskClass));
        List<String> categories = tasks.stream()
                .map(OTasks::getTaskClass)
                .filter(c -> c != null && !c.isEmpty())
                .distinct()
                .toList();
        return R.ok(categories);
    }

    @PostMapping("/task/taskDetails")
    public R<OTasks> taskDetails(@RequestBody Map<String, Integer> body) {
        return R.ok(tasksMapper.selectById(body.get("id")));
    }
}
