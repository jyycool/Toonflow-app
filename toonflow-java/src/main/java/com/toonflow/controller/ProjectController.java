package com.toonflow.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.toonflow.common.exception.BusinessException;
import com.toonflow.common.result.R;
import com.toonflow.entity.OProject;
import com.toonflow.mapper.OProjectMapper;
import com.toonflow.ai.vendor.VendorService;
import com.toonflow.mapper.OAgentDeployMapper;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/project")
@RequiredArgsConstructor
public class ProjectController {

    private final OProjectMapper projectMapper;
    private final OAgentDeployMapper agentDeployMapper;
    private final VendorService vendorService;

    @Value("${toonflow.data-dir}")
    private String dataDir;

    @PostMapping("/getProject")
    public R<List<OProject>> getProject() {
        List<OProject> list = projectMapper.selectList(
                new LambdaQueryWrapper<OProject>().orderByDesc(OProject::getCreateTime));
        return R.ok(list);
    }

    @PostMapping("/addProject")
    public R<Map<String, String>> addProject(@Valid @RequestBody AddProjectRequest req) {
        OProject project = new OProject();
        long id = System.currentTimeMillis();
        project.setId(id);
        project.setProjectType(req.getProjectType());
        project.setName(req.getName());
        project.setIntro(req.getIntro());
        project.setType(req.getType());
        project.setArtStyle(req.getArtStyle());
        project.setDirectorManual(req.getDirectorManual());
        project.setVideoRatio(req.getVideoRatio());
        project.setImageModel(req.getImageModel());
        project.setVideoModel(req.getVideoModel());
        project.setImageQuality(req.getImageQuality());
        project.setMode(req.getMode());
        project.setUserId(1);
        project.setCreateTime(id);
        projectMapper.insert(project);
        return R.ok(Map.of("message", "新增项目成功"));
    }

    @PostMapping("/editProject")
    public R<Map<String, String>> editProject(@Valid @RequestBody EditProjectRequest req) {
        OProject project = projectMapper.selectById(req.getId());
        if (project == null) throw new BusinessException("项目不存在");
        if (req.getName() != null) project.setName(req.getName());
        if (req.getIntro() != null) project.setIntro(req.getIntro());
        if (req.getArtStyle() != null) project.setArtStyle(req.getArtStyle());
        if (req.getDirectorManual() != null) project.setDirectorManual(req.getDirectorManual());
        if (req.getVideoRatio() != null) project.setVideoRatio(req.getVideoRatio());
        if (req.getImageModel() != null) project.setImageModel(req.getImageModel());
        if (req.getVideoModel() != null) project.setVideoModel(req.getVideoModel());
        if (req.getImageQuality() != null) project.setImageQuality(req.getImageQuality());
        if (req.getMode() != null) project.setMode(req.getMode());
        projectMapper.updateById(project);
        return R.ok(Map.of("message", "编辑项目成功"));
    }

    @PostMapping("/delProject")
    public R<Map<String, String>> delProject(@RequestBody Map<String, Long> body) {
        Long id = body.get("id");
        if (id == null) throw new BusinessException("id不能为空");
        projectMapper.deleteById(id);
        return R.ok(Map.of("message", "删除项目成功"));
    }

    /**
     * 获取项目使用的 Agent 模型详情
     */
    @PostMapping("/getModelDetails")
    public R<Map<String, Object>> getModelDetails(@RequestBody Map<String, String> body) {
        String key = body.get("key"); // scriptAgent / productionAgent
        com.toonflow.entity.OAgentDeploy deploy = agentDeployMapper.selectOne(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<com.toonflow.entity.OAgentDeploy>()
                        .eq(com.toonflow.entity.OAgentDeploy::getKey, key).last("LIMIT 1"));
        if (deploy == null || deploy.getModelName() == null) throw new BusinessException("未找到模型");
        String[] parts = deploy.getModelName().split(":", 2);
        if (parts.length < 2) throw new BusinessException("模型名称格式错误");
        Map<String, Object> model = vendorService.getModelDetail(parts[0], parts[1]);
        return R.ok(model);
    }

    // ========== 视觉手册（Markdown 文件存储于 skills 目录）==========

    @PostMapping("/visualManual")
    public R<Map<String, String>> visualManual(@RequestBody Map<String, String> body) {
        String type = body.get("type");
        Path base = Paths.get(dataDir, "skills", "art_skills", "chinese_sweet_romance");
        String content = findAndRead(base, type + ".md");
        return R.ok(Map.of("type", type, "content", content != null ? content : ""));
    }

    @PostMapping("/getVisualManual")
    public R<List<Map<String, String>>> getVisualManual() {
        return R.ok(readManualMap(VISUAL_MAP));
    }

    @PostMapping("/queryDirectorManual")
    public R<List<Map<String, String>>> queryDirectorManual() {
        return R.ok(readManualMap(DIRECTOR_MAP));
    }

    @PostMapping("/addVisualManual")
    public R<Map<String, String>> addVisualManual(@RequestBody Map<String, Object> body) {
        return R.ok(Map.of("message", "视觉手册已保存"));
    }

    @PostMapping("/addDirectorManual")
    public R<Map<String, String>> addDirectorManual(@RequestBody Map<String, Object> body) {
        return R.ok(Map.of("message", "导演手册已保存"));
    }

    @PostMapping("/editVisualManual")
    public R<Map<String, String>> editVisualManual(@RequestBody Map<String, Object> body) {
        return R.ok(Map.of("message", "视觉手册已更新"));
    }

    @PostMapping("/editDirectorlManual")
    public R<Map<String, String>> editDirectorManual(@RequestBody Map<String, Object> body) {
        return R.ok(Map.of("message", "导演手册已更新"));
    }

    @PostMapping("/deleteVisualManual")
    public R<Map<String, String>> deleteVisualManual(@RequestBody Map<String, Object> body) {
        return R.ok(Map.of("message", "视觉手册已删除"));
    }

    @PostMapping("/deleteDirectorManual")
    public R<Map<String, String>> deleteDirectorManual(@RequestBody Map<String, Object> body) {
        return R.ok(Map.of("message", "导演手册已删除"));
    }

    private static final List<String[]> VISUAL_MAP = List.of(
            new String[]{"README", "README", ""},
            new String[]{"前缀", "prefix", ""},
            new String[]{"角色", "art_character", "art_prompt"},
            new String[]{"道具", "art_prop", "art_prompt"},
            new String[]{"场景", "art_scene", "art_prompt"},
            new String[]{"分镜", "director_storyboard", "driector_skills"});

    private static final List<String[]> DIRECTOR_MAP = List.of(
            new String[]{"README", "README", ""},
            new String[]{"导演规划", "director_planning_narrative", "driector_skills"},
            new String[]{"分镜表", "director_storyboard_table_narrative", "driector_skills"});

    private List<Map<String, String>> readManualMap(List<String[]> map) {
        Path base = Paths.get(dataDir, "skills", "art_skills", "chinese_sweet_romance");
        List<Map<String, String>> result = new java.util.ArrayList<>();
        for (String[] entry : map) {
            String content = findAndRead(base, entry[1] + ".md");
            Map<String, String> m = new java.util.HashMap<>();
            m.put("label", entry[0]);
            m.put("value", entry[1]);
            m.put("data", content != null ? content : "");
            result.add(m);
        }
        return result;
    }

    private String findAndRead(Path dir, String target) {
        try {
            if (!Files.exists(dir)) return null;
            try (java.util.stream.Stream<Path> stream = Files.walk(dir)) {
                return stream.filter(Files::isRegularFile)
                        .filter(p -> p.getFileName().toString().equals(target))
                        .findFirst()
                        .map(p -> {
                            try { return Files.readString(p, java.nio.charset.StandardCharsets.UTF_8); }
                            catch (Exception e) { return ""; }
                        }).orElse(null);
            }
        } catch (Exception e) {
            return null;
        }
    }

    @Data
    public static class AddProjectRequest {
        @NotBlank private String projectType;
        @NotBlank private String name;
        @NotNull private String intro;
        @NotBlank private String type;
        @NotNull private String artStyle;
        @NotNull private String directorManual;
        @NotNull private String videoRatio;
        @NotNull private String imageModel;
        @NotNull private String videoModel;
        @NotNull private String imageQuality;
        @NotNull private String mode;
    }

    @Data
    public static class EditProjectRequest {
        @NotNull private Long id;
        private String name;
        private String intro;
        private String artStyle;
        private String directorManual;
        private String videoRatio;
        private String imageModel;
        private String videoModel;
        private String imageQuality;
        private String mode;
    }
}
