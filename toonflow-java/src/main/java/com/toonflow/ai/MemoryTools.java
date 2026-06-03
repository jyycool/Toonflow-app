package com.toonflow.ai;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Exposes MemoryService.deepRetrieve as a Spring AI @Tool.
 * Instantiated per-agent-call (holds isolationKey).
 */
@Slf4j
public class MemoryTools {

    private final MemoryService memoryService;
    private final String isolationKey;

    public MemoryTools(MemoryService memoryService, String isolationKey) {
        this.memoryService = memoryService;
        this.isolationKey = isolationKey;
    }

    @Tool(name = "deepRetrieve", description = "深度检索记忆：当你需要回忆与某个关键词相关的详细历史信息时使用此工具")
    public Map<String, Object> deepRetrieve(
            @ToolParam(description = "要检索的关键词") String keyword) {
        log.info("[tool] deepRetrieve keyword={}", keyword);
        var results = memoryService.deepRetrieve(isolationKey, keyword);
        if (results.isEmpty()) {
            return Map.of("found", false, "message", "未找到相关记忆");
        }
        List<String> contents = results.stream().map(m -> m.getContent()).collect(Collectors.toList());
        return Map.of("found", true, "memories", contents);
    }
}
