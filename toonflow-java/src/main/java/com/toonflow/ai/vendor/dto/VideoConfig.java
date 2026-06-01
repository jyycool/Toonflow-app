package com.toonflow.ai.vendor.dto;

import lombok.Data;

import java.util.List;

/**
 * 视频生成配置
 * 对应原项目 ai.ts 的 VideoConfig
 */
@Data
public class VideoConfig {
    /** 提示词 */
    private String prompt;
    /** 时长（秒） */
    private Integer duration;
    /** 分辨率 */
    private String resolution;
    /** 画幅比例（16:9 / 9:16） */
    private String aspectRatio;
    /** 是否带音频 */
    private Boolean audio;
    /** 参考图列表（base64 或 url），用于首尾帧/图生视频 */
    private List<String> referenceList;
    /** 模型 id（不含 vendorId 前缀） */
    private String modelId;
}
