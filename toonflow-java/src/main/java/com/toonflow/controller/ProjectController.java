package com.toonflow.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.toonflow.common.exception.BusinessException;
import com.toonflow.common.result.R;
import com.toonflow.entity.OProject;
import com.toonflow.mapper.OProjectMapper;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/project")
@RequiredArgsConstructor
public class ProjectController {

    private final OProjectMapper projectMapper;

    @GetMapping("/getProject")
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
