package com.toonflow.config;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AiConfig {

    /**
     * 默认 ChatClient：使用 Spring AI Alibaba 注入的 DashScope ChatModel
     */
    @Bean
    public ChatClient dashscopeChatClient(ChatModel chatModel) {
        return ChatClient.builder(chatModel).build();
    }
}
