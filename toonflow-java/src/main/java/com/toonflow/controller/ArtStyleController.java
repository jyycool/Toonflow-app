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
     * 从参考图提取风格提示词（vision AI） — TS accepts images[] array
     */
    @PostMapping("/extractStylePrompt")
    public R<String> extractStylePrompt(@RequestBody Map<String, Object> body) {
        @SuppressWarnings("unchecked")
        List<String> images = body.get("images") instanceof List<?> l
                ? l.stream().map(Object::toString).collect(java.util.stream.Collectors.toList())
                : List.of();
        String system = "请根据以下图片数据，提取出图片的画风提示词，用于生成图片时指定风格，要求简洁且具有艺术性,只需要画风提示词，不需要其他内容：" +
                "\"比如：`(画风：2D动漫风格,2d animation style)`,`(画风：照片级真人超写实,photorealistic, lifelike, ultra detailed)`，" +
                "`(画风：3D国创,Chinese 3D animation style)`等,如果图片风格无法描述，可以返回`无法描述`," +
                "多张图片时，只输出一个综合的画风提示词，要求包含所有图片的共同风格特征，" +
                "输出格式必须严格按照示例中的格式，必须包含`画风`二字，且必须使用括号括起来，" +
                "括号内必须包含中文和英文的画风描述，并用逗号分隔，英文部分需要翻译成地道的英文提示词";
        try {
            String result = images.isEmpty()
                    ? aiService.generateText("universalAi", List.of(
                            new com.toonflow.ai.AiService.ChatMessage("system", system),
                            new com.toonflow.ai.AiService.ChatMessage("user", "请描述图片画风")))
                    : aiService.generateTextWithVision("universalAi", system, images);
            return R.ok(result);
        } catch (Exception e) {
            throw new com.toonflow.common.exception.BusinessException("提取风格失败: " + e.getMessage());
        }
    }
}
