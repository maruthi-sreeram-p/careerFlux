package com.careerflux.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.careerflux.ai.dto.AiResumeReading;
import com.careerflux.common.error.AiUnavailableException;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/**
 * A provider error can quote the request it failed on, and that request is a
 * student's resume. Neither the log nor the exception handed back may carry it.
 */
@ExtendWith(OutputCaptureExtension.class)
class SpringAiClientPrivacyTest {

    private static final String ECHOED_INPUT =
            "400 Bad Request: could not parse 'Aarav Sharma aarav.sharma@gmail.com 9876543210'";

    private SpringAiClient client;

    @BeforeEach
    void setUp() {
        ((Logger) LoggerFactory.getLogger(SpringAiClient.class)).setLevel(Level.DEBUG);
        ChatModel model = mock(ChatModel.class);
        when(model.call(any(Prompt.class))).thenThrow(new IllegalArgumentException(ECHOED_INPUT));
        client = new SpringAiClient(model);
    }

    @Test
    @DisplayName("a failed structured call logs the exception class, never its message")
    void structuredFailureDoesNotLeak(CapturedOutput output) {
        AiUnavailableException thrown = catchThrowableOfType(AiUnavailableException.class,
                () -> client.structured("system", "Resume text", AiResumeReading.class));

        assertThat(thrown).isNotNull();
        assertThat(thrown.getCause()).describedAs("the provider's exception must not travel on").isNull();
        assertThat(thrown.getMessage()).doesNotContain("aarav");
        assertThat(output.getAll()).contains("IllegalArgumentException")
                .doesNotContain("aarav").doesNotContain("9876543210").doesNotContain("Bad Request");
    }

    @Test
    @DisplayName("a failed text call logs the exception class, never its message")
    void textFailureDoesNotLeak(CapturedOutput output) {
        AiUnavailableException thrown = catchThrowableOfType(AiUnavailableException.class,
                () -> client.text("system", "prompt"));

        assertThat(thrown).isNotNull();
        assertThat(thrown.getCause()).isNull();
        assertThat(output.getAll()).doesNotContain("aarav").doesNotContain("9876543210");
    }
}
