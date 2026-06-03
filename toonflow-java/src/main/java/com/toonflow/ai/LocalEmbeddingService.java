package com.toonflow.ai;

import ai.djl.huggingface.tokenizers.Encoding;
import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.toonflow.entity.OSetting;
import com.toonflow.mapper.OSettingMapper;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.LongBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Local ONNX embedding service using all-MiniLM-L6-v2 (same as original TS project).
 * Model path from o_setting: modelOnnxFile = ["all-MiniLM-L6-v2","onnx","model_fp16.onnx"]
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LocalEmbeddingService {

    private final OSettingMapper settingMapper;
    private final ObjectMapper objectMapper;

    @Value("${toonflow.data-dir:${user.home}/.toonflow}")
    private String dataDir;

    private volatile OrtEnvironment ortEnv;
    private volatile OrtSession ortSession;
    private volatile HuggingFaceTokenizer tokenizer;
    private volatile boolean initialized = false;
    private volatile boolean unavailable = false;

    public float[] embed(String text) {
        if (unavailable) return new float[0];
        try {
            ensureInitialized();
            if (unavailable) return new float[0];
            return runInference(text);
        } catch (Exception e) {
            log.warn("Local ONNX embedding failed: {}", e.getMessage());
            return new float[0];
        }
    }

    private synchronized void ensureInitialized() {
        if (initialized || unavailable) return;
        try {
            Path[] paths = resolvePaths();
            if (paths == null) {
                unavailable = true;
                return;
            }
            Path modelPath = paths[0];
            Path tokenizerPath = paths[1];

            if (!Files.exists(modelPath)) {
                log.info("ONNX model not found at {}, local embedding disabled", modelPath);
                unavailable = true;
                return;
            }
            if (!Files.exists(tokenizerPath)) {
                log.info("Tokenizer not found at {}, local embedding disabled", tokenizerPath);
                unavailable = true;
                return;
            }

            ortEnv = OrtEnvironment.getEnvironment();
            ortSession = ortEnv.createSession(modelPath.toString());
            tokenizer = HuggingFaceTokenizer.newInstance(tokenizerPath);
            initialized = true;
            log.info("Local ONNX embedding initialized: {}", modelPath);
        } catch (Exception e) {
            log.warn("Failed to initialize local ONNX embedding: {}", e.getMessage());
            unavailable = true;
        }
    }

    private Path[] resolvePaths() {
        try {
            OSetting setting = settingMapper.selectById("modelOnnxFile");
            if (setting == null || setting.getValue() == null) return null;
            List<String> parts = objectMapper.readValue(setting.getValue(),
                    objectMapper.getTypeFactory().constructCollectionType(List.class, String.class));
            if (parts.size() < 3) return null;

            // parts = ["all-MiniLM-L6-v2", "onnx", "model_fp16.onnx"]
            String modelFolder = parts.get(0);
            String subDir = parts.get(1);
            String fileName = parts.get(2);

            Path base = Paths.get(dataDir, "models", modelFolder);
            Path modelPath = base.resolve(subDir).resolve(fileName);
            Path tokenizerPath = base.resolve("tokenizer.json");
            return new Path[]{modelPath, tokenizerPath};
        } catch (Exception e) {
            log.debug("Could not resolve ONNX model paths: {}", e.getMessage());
            return null;
        }
    }

    private float[] runInference(String text) throws Exception {
        Encoding encoding = tokenizer.encode(text);
        long[] inputIds = encoding.getIds();
        long[] attentionMask = encoding.getAttentionMask();
        long[] tokenTypeIds = encoding.getTypeIds();
        int seqLen = inputIds.length;

        long[] shape = {1, seqLen};

        try (OnnxTensor inputIdsTensor = OnnxTensor.createTensor(ortEnv,
                     LongBuffer.wrap(inputIds), shape);
             OnnxTensor attMaskTensor = OnnxTensor.createTensor(ortEnv,
                     LongBuffer.wrap(attentionMask), shape);
             OnnxTensor tokenTypeTensor = OnnxTensor.createTensor(ortEnv,
                     LongBuffer.wrap(tokenTypeIds), shape)) {

            Map<String, OnnxTensor> inputs = new HashMap<>();
            inputs.put("input_ids", inputIdsTensor);
            inputs.put("attention_mask", attMaskTensor);

            // Some models require token_type_ids, others don't
            if (ortSession.getInputNames().contains("token_type_ids")) {
                inputs.put("token_type_ids", tokenTypeTensor);
            }

            try (OrtSession.Result result = ortSession.run(inputs)) {
                // last_hidden_state: [1, seqLen, hiddenDim]
                float[][][] lastHidden = (float[][][]) result.get(0).getValue();
                return meanPool(lastHidden[0], attentionMask);
            }
        }
    }

    /** Mean pooling weighted by attention_mask, then L2 normalize */
    private float[] meanPool(float[][] tokenEmbeddings, long[] attentionMask) {
        int hiddenDim = tokenEmbeddings[0].length;
        float[] sum = new float[hiddenDim];
        long maskSum = 0;
        for (int t = 0; t < tokenEmbeddings.length; t++) {
            long m = attentionMask[t];
            if (m == 0) continue;
            maskSum += m;
            for (int d = 0; d < hiddenDim; d++) {
                sum[d] += tokenEmbeddings[t][d] * m;
            }
        }
        if (maskSum == 0) return sum;
        double norm = 0;
        for (int d = 0; d < hiddenDim; d++) {
            sum[d] /= maskSum;
            norm += sum[d] * sum[d];
        }
        norm = Math.sqrt(norm);
        if (norm > 0) {
            for (int d = 0; d < hiddenDim; d++) sum[d] /= norm;
        }
        return sum;
    }

    @PreDestroy
    public void close() {
        try {
            if (tokenizer != null) tokenizer.close();
            if (ortSession != null) ortSession.close();
            if (ortEnv != null) ortEnv.close();
        } catch (Exception ignored) {}
    }
}
