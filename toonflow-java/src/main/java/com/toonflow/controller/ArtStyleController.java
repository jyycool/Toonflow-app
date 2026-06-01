package com.toonflow.controller;

import com.toonflow.common.result.R;
import com.toonflow.entity.OArtStyle;
import com.toonflow.mapper.OArtStyleMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/artStyle")
@RequiredArgsConstructor
public class ArtStyleController {

    private final OArtStyleMapper artStyleMapper;

    @GetMapping("/getArtStyle")
    public R<List<OArtStyle>> getArtStyle() {
        return R.ok(artStyleMapper.selectList(null));
    }

    @PostMapping("/addArtStyle")
    public R<Map<String, String>> addArtStyle(@RequestBody OArtStyle artStyle) {
        artStyleMapper.insert(artStyle);
        return R.ok(Map.of("message", "新增风格成功"));
    }

    @PostMapping("/editArtStyle")
    public R<Map<String, String>> editArtStyle(@RequestBody OArtStyle artStyle) {
        artStyleMapper.updateById(artStyle);
        return R.ok(Map.of("message", "编辑风格成功"));
    }
}
