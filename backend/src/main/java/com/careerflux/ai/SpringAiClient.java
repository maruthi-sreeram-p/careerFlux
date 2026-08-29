package com.careerflux.ai;

import java.time.Duration;

import com.careerflux.common.error.AiUnavailableException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;

/**
 * Spring AI backed implementation. Instantiated by {@link AiConfig} only when a
 * {@link ChatModel} bean exists, which in practice means an API key was
 * supplied; see {@link com.careerflux.config.AiEnablementEnvironmentPostProcessor}.
 *
 * <p>Every failure is converted into {@link AiUnavailableException} so callers
 * have exactly one thing to catch, and the provider's exception types never leak
 * past this class.
 */
public class SpringAiClient implements AiClient {

    private static final Logger log = LoggerFactory.getLogger(SpringAiClient.class);
    private static final Duration SLOW_CALL_THRESHOLD = Duration.ofSeconds(15);

    private final ChatClient chatClient;
    private final String modelName;

    public SpringAiClient(ChatModel chatModel) {
        this.chatClient = ChatClient.builder(chatModel).build();
        this.modelName = resolveModelName(chatModel);
        log.info("AI enabled: chat model '{}'", modelName);
    }

    private static String resolveModelName(ChatModel chatModel) {
        try {
            var options = chatModel.getDefaultOptions();
            if (options != null && options.getModel() != null) {
                return options.getModel();
            }
        } catch (RuntimeException ex) {
            log.debug("Could not read model name from options: {}", ex.getMessage());
        }
        return chatModel.getClass().getSimpleName();
    }

    @Override
    public boolean isAvailable() {
        return true;
    }

    @Override
    public String modelName() {
        return modelName;
    }

    @Override
    public <T> T structured(String systemPrompt, String userPrompt, Class<T> type) {
        long started = System.nanoTime();
        try {
            T result = chatClient.prompt()
                    .system(systemPrompt)
                    .user(userPrompt)
                    .call()
                    .entity(type);
            if (result == null) {
                throw new AiUnavailableException("The model returned no usable output.");
            }
            logDuration(started, type.getSimpleName());
            return result;
        } catch (AiUnavailableException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            log.warn("Structured AI call for {} failed: {}", type.getSimpleName(), ex.getMessage());
            throw new AiUnavailableException("The AI service could not complete that request.", ex);
        }
    }

    @Override
    public String text(String systemPrompt, String userPrompt) {
        long started = System.nanoTime();
        try {
            String content = chatClient.prompt()
                    .system(systemPrompt)
                    .user(userPrompt)
                    .call()
                    .content();
            if (content == null || content.isBlank()) {
                throw new AiUnavailableException("The model returned no usable output.");
            }
            logDuration(started, "text");
            return content.strip();
        } catch (AiUnavailableException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            log.warn("Text AI call failed: {}", ex.getMessage());
            throw new AiUnavailableException("The AI service could not complete that request.", ex);
        }
    }

    private void logDuration(long startedNanos, String label) {
        Duration elapsed = Duration.ofNanos(System.nanoTime() - startedNanos);
        if (elapsed.compareTo(SLOW_CALL_THRESHOLD) > 0) {
            log.warn("Slow AI call ({}) took {} ms", label, elapsed.toMillis());
        } else {
            log.debug("AI call ({}) took {} ms", label, elapsed.toMillis());
        }
    }
}
