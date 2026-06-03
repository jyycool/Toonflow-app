package com.toonflow.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.toonflow.common.result.R;
import com.toonflow.entity.*;
import com.toonflow.mapper.*;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
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

    @PostMapping("/general/generalStatistics")
    public R<Map<String, Object>> generalStatistics(@RequestBody Map<String, Object> body) {
        String projectId = body.get("projectId") != null ? body.get("projectId").toString() : null;

        // Get scriptIds for this project
        List<OScript> scripts = scriptMapper.selectList(
                new LambdaQueryWrapper<OScript>().eq(OScript::getProjectId, projectId)
                        .select(OScript::getId));
        List<String> scriptIds = scripts.stream().map(OScript::getId).collect(java.util.stream.Collectors.toList());

        long roleCount = assetsMapper.selectCount(
                new LambdaQueryWrapper<OAssets>().eq(OAssets::getProjectId, projectId).eq(OAssets::getType, "角色"));
        long scriptCount = scriptMapper.selectCount(
                new LambdaQueryWrapper<OScript>().eq(OScript::getProjectId, projectId));
        long videoCount = scriptIds.isEmpty() ? 0 : videoMapper.selectCount(
                new LambdaQueryWrapper<OVideo>().in(OVideo::getScriptId, scriptIds));
        long storyboardCount = scriptIds.isEmpty() ? 0 : storyboardMapper.selectCount(
                new LambdaQueryWrapper<OStoryboard>().in(OStoryboard::getScriptId, scriptIds));

        Map<String, Object> stats = new HashMap<>();
        stats.put("roleCount", roleCount);
        stats.put("scriptCount", scriptCount);
        stats.put("videoCount", videoCount);
        stats.put("storyboardCount", storyboardCount);
        return R.ok(stats);
    }

    @PostMapping("/general/getSingleProject")
    public R<OProject> getSingleProject(@RequestBody Map<String, Object> body) {
        String id = body.get("id") != null ? body.get("id").toString() : null;
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
    public R<Map<String, String>> deleteAllData(@RequestBody Map<String, Object> body) {
        String projectId = body.get("projectId") != null ? body.get("projectId").toString() : null;
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

    @PostMapping("/task/getProject")
    public R<List<Map<String, Object>>> getTaskProject() {
        List<OProject> projects = projectMapper.selectList(null);
        List<Map<String, Object>> result = projects.stream()
                .filter(p -> p.getName() != null && !p.getName().isEmpty())
                .map(p -> {
                    Map<String, Object> m = new java.util.HashMap<>();
                    m.put("id", p.getId());
                    m.put("name", p.getName());
                    return m;
                })
                .distinct()
                .toList();
        return R.ok(result);
    }

    @PostMapping("/task/getTaskApi")
    public R<Map<String, Object>> getTaskApi(@RequestBody Map<String, Object> body) {
        String projectId = body.get("projectId") != null ? body.get("projectId").toString() : null;
        String state = body.get("state") != null ? body.get("state").toString() : null;
        String taskClass = body.get("taskClass") != null ? body.get("taskClass").toString() : null;
        int page = body.get("page") instanceof Number n ? n.intValue() : 1;
        int limit = body.get("limit") instanceof Number n ? n.intValue() : 10;
        int offset = (page - 1) * limit;

        LambdaQueryWrapper<OTasks> q = new LambdaQueryWrapper<OTasks>().orderByDesc(OTasks::getId);
        if (projectId != null) q.eq(OTasks::getProjectId, projectId);
        if (state != null) q.eq(OTasks::getState, state);
        if (taskClass != null) q.eq(OTasks::getTaskClass, taskClass);

        long total = tasksMapper.selectCount(q);
        q.last("LIMIT " + limit + " OFFSET " + offset);
        List<OTasks> data = tasksMapper.selectList(q);

        Map<String, Object> result = new HashMap<>();
        result.put("data", data);
        result.put("total", total);
        return R.ok(result);
    }

    @PostMapping("/task/getTaskCategories")
    public R<List<Map<String, Object>>> getTaskCategories() {
        List<OTasks> tasks = tasksMapper.selectList(
                new LambdaQueryWrapper<OTasks>().select(OTasks::getTaskClass).groupBy(OTasks::getTaskClass));
        List<Map<String, Object>> categories = tasks.stream()
                .filter(t -> t.getTaskClass() != null && !t.getTaskClass().isEmpty())
                .map(t -> { Map<String, Object> m = new HashMap<>(); m.put("taskClass", t.getTaskClass()); return m; })
                .collect(java.util.stream.Collectors.toList());
        return R.ok(categories);
    }

    @PostMapping("/task/taskDetails")
    public R<OTasks> taskDetails(@RequestBody Map<String, Object> body) {
        String id = body.get("taskId") != null ? body.get("taskId").toString()
                   : body.get("id") != null ? body.get("id").toString() : null;
        return R.ok(tasksMapper.selectById(id));
    }
}
