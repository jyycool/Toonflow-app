package com.toonflow.controller;

import com.toonflow.ai.AiService;
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
    private final AiService aiService;

    @PostMapping("/getArtStyle")
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

    /**
     * 从参考图/描述提取风格提示词（AI）
     */
    @PostMapping("/extractStylePrompt")
    public R<Map<String, Object>> extractStylePrompt(@RequestBody Map<String, Object> body) {
        String describe = (String) body.getOrDefault("describe", "");
        try {
            String prompt = aiService.generateText("universalAi", List.of(
                    new com.toonflow.ai.AiService.ChatMessage("system",
                            "你是美术风格分析专家。请根据用户提供的描述提炼出可用于图像生成的画风提示词，只输出提示词本身。"),
                    new com.toonflow.ai.AiService.ChatMessage("user", describe)));
            return R.ok(Map.of("prompt", prompt));
        } catch (Exception e) {
            return R.fail("提取风格失败: " + e.getMessage());
        }
    }
}
