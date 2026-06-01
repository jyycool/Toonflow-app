package com.toonflow.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.toonflow.ai.AiService;
import com.toonflow.ai.agent.ScriptAgentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Controller;

import java.util.List;
import java.util.Map;

/**
 * WebSocket Agent 消息处理器
 * 客户端发送到 /app/agent，响应推送到 /topic/agent/{sessionId}
 */
@Slf4j
@Controller
@RequiredArgsConstructor
public class AgentWebSocketHandler {

    private final SimpMessagingTemplate messagingTemplate;
    private final AiService aiService;
    private final ScriptAgentService scriptAgentService;
    private final ObjectMapper objectMapper;

    /**
     * 剧本 Agent 入口：使用记忆 + 项目信息编排
     * payload: { sessionId, isolationKey, projectId, message }
     */
    @MessageMapping("/scriptAgent")
    @Async
    public void handleScriptAgent(@Payload Map<String, Object> payload) {
        String sessionId = (String) payload.get("sessionId");
        String isolationKey = (String) payload.getOrDefault("isolationKey", sessionId);
        String userMessage = (String) payload.getOrDefault("message", "");
        Long projectId = payload.get("projectId") != null
                ? Long.valueOf(payload.get("projectId").toString()) : null;

        try {
            scriptAgentService.runDecision(sessionId, isolationKey, projectId, userMessage);
        } catch (Exception e) {
            log.error("剧本 Agent WebSocket 处理失败", e);
            messagingTemplate.convertAndSend("/topic/agent/" + sessionId,
                    Map.of("type", "error", "message", e.getMessage()));
        }
    }

    /**
     * 通用 Agent 入口：直接流式生成
     */
    @MessageMapping("/agent")
    @Async
    public void handleAgentMessage(@Payload Map<String, Object> payload) {
        String sessionId = (String) payload.get("sessionId");
        String agentType = (String) payload.getOrDefault("agentType", "scriptAgent");
        String userMessage = (String) payload.getOrDefault("message", "");

        try {
            List<AiService.ChatMessage> messages = List.of(
                    new AiService.ChatMessage("user", userMessage));

            aiService.streamText(agentType, messages)
                    .subscribe(
                            chunk -> messagingTemplate.convertAndSend(
                                    "/topic/agent/" + sessionId,
                                    Map.of("type", "chunk", "content", chunk)),
                            error -> messagingTemplate.convertAndSend(
                                    "/topic/agent/" + sessionId,
                                    Map.of("type", "error", "message", error.getMessage())),
                            () -> messagingTemplate.convertAndSend(
                                    "/topic/agent/" + sessionId,
                                    Map.of("type", "done"))
                    );
        } catch (Exception e) {
            log.error("Agent WebSocket 处理失败", e);
            messagingTemplate.convertAndSend(
                    "/topic/agent/" + sessionId,
                    Map.of("type", "error", "message", e.getMessage()));
        }
    }
}
