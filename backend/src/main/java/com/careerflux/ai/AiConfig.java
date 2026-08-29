package com.careerflux.ai;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AiConfig {

    private static final Logger log = LoggerFactory.getLogger(AiConfig.class);

    /**
     * Resolved through {@link ObjectProvider} rather than {@code @ConditionalOnBean}
     * so the decision is made at bean-creation time, after Spring AI's
     * auto-configuration has had its chance to register a {@link ChatModel}.
     */
    @Bean
    public AiClient aiClient(ObjectProvider<ChatModel> chatModels) {
        ChatModel chatModel = chatModels.getIfAvailable();
        if (chatModel == null) {
            log.info("AI disabled: no chat model configured. Resume parsing and match narratives "
                    + "will use their deterministic fallbacks.");
            return new UnavailableAiClient();
        }
        return new SpringAiClient(chatModel);
    }
}
