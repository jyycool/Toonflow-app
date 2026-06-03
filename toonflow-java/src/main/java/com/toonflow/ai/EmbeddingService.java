package com.toonflow.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 向量化服务：优先使用本地 ONNX 模型（all-MiniLM-L6-v2），与原项目一致。
 * 对应原项目 src/utils/agent/embedding.ts
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EmbeddingService {

    private final LocalEmbeddingService localEmbeddingService;
    private final ObjectMapper objectMapper;

    /**
     * 生成文本向量。使用本地 ONNX 模型，若模型未安装则返回空向量。
     */
    public float[] embed(String text) {
        return localEmbeddingService.embed(text);
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
