package com.toonflow.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 向量化服务：使用 Spring AI Alibaba (DashScope) 的 EmbeddingModel
 * 对应原项目 src/utils/agent/embedding.ts（原项目用本地 ONNX all-MiniLM-L6-v2）
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EmbeddingService {

    @Autowired(required = false)
    private EmbeddingModel embeddingModel;

    private final ObjectMapper objectMapper;

    /**
     * 生成文本向量。若 embedding 服务未配置或调用失败，返回空向量（降级）。
     * 原始项目使用本地 ONNX 模型（all-MiniLM-L6-v2），不依赖外部 API Key。
     */
    public float[] embed(String text) {
        if (embeddingModel == null) {
            log.debug("EmbeddingModel 未配置，返回空向量");
            return new float[0];
        }
        try {
            return embeddingModel.embed(text);
        } catch (Exception e) {
            log.warn("Embedding 调用失败，降级为空向量（可能是 API Key 未配置）: {}", e.getMessage());
            return new float[0];
        }
    }

    /**
     * 序列化向量为 JSON 字符串（存数据库）
     */
    public String toJson(float[] vector) {
        try {
            return objectMapper.writeValueAsString(vector);
        } catch (Exception e) {
            return "[]";
        }
    }

    /**
     * 反序列化向量
     */
    public float[] fromJson(String json) {
        try {
            if (json == null || json.isEmpty()) return new float[0];
            return objectMapper.readValue(json, float[].class);
        } catch (Exception e) {
            return new float[0];
        }
    }

    /**
     * 余弦相似度（向量已归一化时等价于点积）
     */
    public double cosineSimilarity(float[] a, float[] b) {
        if (a.length == 0 || b.length == 0 || a.length != b.length) return 0.0;
        double dot = 0, normA = 0, normB = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            normA += a[i] * a[i];
            normB += b[i] * b[i];
        }
        if (normA == 0 || normB == 0) return 0.0;
        return dot / (Math.sqrt(normA) * Math.sqrt(normB));
    }
}
