package com.toonflow.ai;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.toonflow.common.exception.BusinessException;
import com.toonflow.entity.OAgentDeploy;
import com.toonflow.entity.OSetting;
import com.toonflow.entity.OVendorConfig;
import com.toonflow.mapper.OAgentDeployMapper;
import com.toonflow.mapper.OSettingMapper;
import com.toonflow.mapper.OVendorConfigMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * AI 服务层：支持动态供应商配置，对接 Spring AI Alibaba (DashScope) 和 OpenAI 兼容接口
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiService {

    private final OAgentDeployMapper agentDeployMapper;
    private final OSettingMapper settingMapper;
    private final OVendorConfigMapper vendorConfigMapper;
    private final ObjectMapper objectMapper;

    // Spring AI Alibaba 原生 ChatClient（DashScope）
    private final ChatClient dashscopeChatClient;

    /**
     * 根据 agentType 解析模型名称（格式: vendorId:modelName）
     */
    public String resolveModelName(String agentType) {
        OSetting modeSetting = settingMapper.selectById("agentUseMode");
        String mode = modeSetting != null ? modeSetting.getValue() : "0";

        if ("1".equals(mode)) {
            // 高级配置：精确匹配
            OAgentDeploy deploy = agentDeployMapper.selectOne(
                    new LambdaQueryWrapper<OAgentDeploy>().eq(OAgentDeploy::getKey, agentType));
            if (deploy == null || deploy.getModelName() == null)
                throw new BusinessException("高级配置模式下，未找到对应的模型配置: " + agentType);
            return deploy.getModelName();
        } else {
            // 简易配置：取父级配置
            String parentKey = agentType.contains(":") ? agentType.split(":")[0] : agentType;
            OAgentDeploy deploy = agentDeployMapper.selectOne(
                    new LambdaQueryWrapper<OAgentDeploy>().eq(OAgentDeploy::getKey, parentKey));
            if (deploy == null || deploy.getModelName() == null)
                throw new BusinessException("简易配置模式下，未找到部署配置: " + agentType);
            return deploy.getModelName();
        }
    }

    /**
     * 构建 ChatModel（根据供应商配置动态创建）
     * vendorModelName 格式: "vendorId:modelId"
     */
    public ChatModel buildChatModel(String vendorModelName) {
        String[] parts = vendorModelName.split(":", 2);
        if (parts.length < 2) throw new BusinessException("模型名称格式错误，应为 vendorId:modelId");
        String vendorId = parts[0];
        String modelId = parts[1];

        OVendorConfig vendorConfig = vendorConfigMapper.selectById(vendorId);
        if (vendorConfig == null) throw new BusinessException("供应商配置不存在: " + vendorId);
        if (vendorConfig.getEnable() == null || vendorConfig.getEnable() == 0)
            throw new BusinessException("供应商未启用: " + vendorId);

        // 解析供应商输入参数（apiKey, baseUrl 等）
        try {
            @SuppressWarnings("unchecked")
            Map<String, String> inputs = objectMapper.readValue(
                    vendorConfig.getInputValues() != null ? vendorConfig.getInputValues() : "{}",
                    Map.class);
            String apiKey = inputs.getOrDefault("apiKey", "");
            String baseUrl = inputs.getOrDefault("baseUrl", "https://api.openai.com");

            OpenAiApi openAiApi = OpenAiApi.builder()
                    .baseUrl(baseUrl)
                    .apiKey(apiKey)
                    .build();
            OpenAiChatOptions options = OpenAiChatOptions.builder()
                    .model(modelId)
                    .build();
            return OpenAiChatModel.builder()
                    .openAiApi(openAiApi)
                    .defaultOptions(options)
                    .build();
        } catch (Exception e) {
            throw new BusinessException("构建模型失败: " + e.getMessage());
        }
    }

    /**
     * 文本生成（同步）
     */
    public String generateText(String agentType, List<ChatMessage> messages) {
        String modelName = resolveModelName(agentType);
        ChatModel model = buildChatModel(modelName);

        List<Message> springMessages = convertMessages(messages);
        ChatResponse response = model.call(new Prompt(springMessages));
        return response.getResult().getOutput().getText();
    }

    /**
     * 文本生成（流式）
     */
    public Flux<String> streamText(String agentType, List<ChatMessage> messages) {
        String modelName = resolveModelName(agentType);
        ChatModel model = buildChatModel(modelName);

        List<Message> springMessages = convertMessages(messages);
        return model.stream(new Prompt(springMessages))
                .map(resp -> {
                    if (resp.getResult() != null && resp.getResult().getOutput() != null) {
                        String text = resp.getResult().getOutput().getText();
                        return text != null ? text : "";
                    }
                    return "";
                });
    }

    /**
     * 带工具调用的流式生成
     * @param toolObjects 含 @Tool 注解方法的对象（如 ScriptAgentTools）
     */
    public Flux<String> streamTextWithTools(String agentType, List<ChatMessage> messages,
                                            Object... toolObjects) {
        String modelName = resolveModelName(agentType);
        ChatModel model = buildChatModel(modelName);

        ChatClient chatClient = ChatClient.builder(model).build();
        var spec = chatClient.prompt().messages(convertMessages(messages));
        if (toolObjects != null && toolObjects.length > 0) {
            spec = spec.tools(toolObjects);
        }
        return spec.stream().content();
    }

    /**
     * 带工具调用的同步生成
     */
    public String generateTextWithTools(String agentType, List<ChatMessage> messages,
                                        Object... toolObjects) {
        String modelName = resolveModelName(agentType);
        ChatModel model = buildChatModel(modelName);

        ChatClient chatClient = ChatClient.builder(model).build();
        var spec = chatClient.prompt().messages(convertMessages(messages));
        if (toolObjects != null && toolObjects.length > 0) {
            spec = spec.tools(toolObjects);
        }
        return spec.call().content();
    }

    /**
     * 使用 DashScope 生成文本（直接使用 Spring AI Alibaba）
     */
    public String generateWithDashScope(String systemPrompt, String userPrompt) {
        return dashscopeChatClient.prompt()
                .system(systemPrompt)
                .user(userPrompt)
                .call()
                .content();
    }

    private List<Message> convertMessages(List<ChatMessage> messages) {
        List<Message> result = new ArrayList<>();
        for (ChatMessage msg : messages) {
            result.add(switch (msg.getRole()) {
                case "system" -> new SystemMessage(msg.getContent());
                case "assistant" -> new AssistantMessage(msg.getContent());
                default -> new UserMessage(msg.getContent());
            });
        }
        return result;
    }

    public record ChatMessage(String role, String content) {
        public String getRole() { return role; }
        public String getContent() { return content; }
    }
}
