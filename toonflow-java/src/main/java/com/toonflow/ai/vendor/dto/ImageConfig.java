package com.toonflow.ai.vendor.dto;

import lombok.Data;

import java.util.List;

/**
 * 图片生成配置
 * 对应原项目 ai.ts 的 ImageConfig
 */
@Data
public class ImageConfig {
    /** 提示词 */
    private String prompt;
    /** 画质（如 standard/hd） */
    private String quality;
    /** 画幅比例（如 16:9） */
    private String ratio;
    /** 分辨率（如 1024x1024），可由 ratio 推导 */
    private String resolution;
    /** 参考图（base64 或 url 列表） */
    private List<String> references;
    /** 模型 id（不含 vendorId 前缀） */
    private String modelId;
}
