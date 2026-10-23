package com.careerflux.candidate.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

import com.careerflux.candidate.domain.CandidateProfile;
import com.careerflux.candidate.repository.CandidateProfileRepository;
import com.careerflux.common.error.BadRequestException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

class PersonalEmailVerificationTest {

    private final CandidateProfileRepository profiles = mock(CandidateProfileRepository.class);
    private final JavaMailSender sender = mock(JavaMailSender.class);
    @SuppressWarnings("unchecked")
    private final ObjectProvider<JavaMailSender> provider = mock(ObjectProvider.class);
    private PersonalEmailVerification verification;
    private CandidateProfile profile;

    @BeforeEach
    void setUp() {
        when(provider.getIfAvailable()).thenReturn(sender);
        verification = new PersonalEmailVerification(profiles, provider, "from@x.test", "https://app.test/");
        profile = new CandidateProfile();
        profile.setPersonalEmail("me@example.com");
    }

    private String sentToken() {
        ArgumentCaptor<SimpleMailMessage> sent = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(sender).send(sent.capture());
        String text = sent.getValue().getText();
        int at = text.indexOf("token=");
        return text.substring(at + 6, at + 6 + 64);
    }

    @Test
    @DisplayName("a new address is unconfirmed and gets a link; only the token's hash is stored")
    void beginSendsLink() {
        profile.setPersonalEmailVerifiedAt(Instant.now());
        verification.begin(profile);

        assertThat(profile.getPersonalEmailVerifiedAt()).isNull();
        String token = sentToken();
        assertThat(profile.getPersonalEmailTokenHash()).isEqualTo(PersonalEmailVerification.digest(token))
                .isNotEqualTo(token);
    }

    @Test
    @DisplayName("following the link confirms the address and uses the token up")
    void confirms() {
        verification.begin(profile);
        String token = sentToken();
        when(profiles.findByPersonalEmailTokenHash(PersonalEmailVerification.digest(token)))
                .thenReturn(Optional.of(profile));

        verification.confirm(token);

        assertThat(profile.getPersonalEmailVerifiedAt()).isNotNull();
        assertThat(profile.getPersonalEmailTokenHash()).isNull();
    }

    @Test
    @DisplayName("an expired or unknown link is refused and confirms nothing")
    void refuses() {
        verification.begin(profile);
        String token = sentToken();
        profile.setPersonalEmailTokenExpiresAt(Instant.now().minus(1, ChronoUnit.MINUTES));
        when(profiles.findByPersonalEmailTokenHash(PersonalEmailVerification.digest(token)))
                .thenReturn(Optional.of(profile));

        assertThatThrownBy(() -> verification.confirm(token)).isInstanceOf(BadRequestException.class);
        assertThat(profile.getPersonalEmailVerifiedAt()).isNull();
        assertThatThrownBy(() -> verification.confirm("nope")).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> verification.confirm(" ")).isInstanceOf(BadRequestException.class);
    }

    @Test
    @DisplayName("clearing the address clears confirmation and sends nothing")
    void clearing() {
        profile.setPersonalEmailVerifiedAt(Instant.now());
        profile.setPersonalEmail(null);
        verification.begin(profile);

        assertThat(profile.getPersonalEmailVerifiedAt()).isNull();
        org.mockito.Mockito.verifyNoInteractions(sender);
    }

    @Test
    @DisplayName("a mail failure does not fail the caller")
    void sendFailureIsContained() {
        org.mockito.Mockito.doThrow(new org.springframework.mail.MailSendException("down"))
                .when(sender).send(any(SimpleMailMessage.class));
        verification.begin(profile);
        assertThat(profile.getPersonalEmailTokenHash()).isNotNull();
    }
}
